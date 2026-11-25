//! 内容质量降权规则实测探针（只读，不改引擎）
//! 对真实书源搜同一本书，抓目录数章数 + 抽前 N 章正文测字数分布，
//! 验证两条规则能否抓坏源（章数 0 / 正文极短<100字）且不误杀好源。
//! 并发版：8 个 worker 从任务队列取源并行抓，30 源约几分钟。
//! 用法：YG_PROXY=127.0.0.1:17891 cargo run --example quality_probe -- "关键词" "最多源数" "每源抽章数" [并发数]
//! 输出：C:\Users\Administrator\.dsh\im\quality_probe_out.txt

use source_engine::api::{search_with, BookSource};
use source_engine::http::{abs_url, Fetcher, UreqFetcher};
use source_engine::rule::{get_list, get_string, RuleCtx};
use std::sync::{mpsc, Arc, Mutex};

fn clean_len(s: &str) -> usize {
    // 与 api::clean_text 近似：折叠连续空行后去首尾，字数用字符数（中文）统计
    let re = regex::Regex::new(r"\n{3,}").unwrap();
    let folded = re.replace_all(s, "\n\n");
    folded.trim().chars().count()
}

/// 探测单个源，成功返回一行结果（逻辑与原串行版 for-body 逐字一致）
fn probe_source(
    idx: usize,
    item: &serde_json::Value,
    key: &str,
    fetcher: &UreqFetcher,
    sample_n: usize,
) -> Option<String> {
    let name = item.get("bookSourceName").and_then(|v| v.as_str()).unwrap_or("?").to_string();
    let item_json = item.to_string();
    let src = BookSource::from_json(&item_json).ok()?;
    // 跳过书源引擎明显无搜索/目录规则的（本地书源等）
    if src.search_url.trim().is_empty() || src.t_chapter_list.trim().is_empty() {
        return None;
    }

    // 1) 搜索
    let books = search_with(&src, key, 1, fetcher).ok()?;
    if books.is_empty() { return None; }
    // 优先取书名精确含关键词的一本，否则第一本
    let book = books.iter().find(|b| b.name.contains(key)).unwrap_or(&books[0]);

    let mut line = format!("[{:02}] {} | book={}", idx, name, book.name);
    let c = RuleCtx {
        base_url: &book.book_url,
        header: &src.header,
        book_url: &book.book_url,
        book_name: &book.name,
        chapter_url: "",
        fetcher,
    };

    // 2) 详情拿 toc_url
    let detail_html = match fetcher.get(&book.book_url, &src.header, "") {
        Ok(h) => h,
        Err(e) => {
            line.push_str(&format!(" | ERR detail: {}", e));
            return Some(line);
        }
    };
    let toc_raw = get_string(&src.d_toc_url, &detail_html, &c);
    let toc_url = if !toc_raw.trim().is_empty() {
        abs_url(&book.book_url, toc_raw.trim())
    } else if let Some(read_url) = source_engine::api::detect_meta_content(&detail_html, "og:novel:read_url") {
        abs_url(&book.book_url, &read_url)
    } else {
        book.book_url.clone()
    };

    // 3) 目录数章数
    let toc_html = if toc_url == book.book_url {
        detail_html.clone()
    } else {
        match fetcher.get(&toc_url, &src.header, "") {
            Ok(h) => h,
            Err(e) => {
                line.push_str(&format!(" | ERR toc: {}", e));
                return Some(line);
            }
        }
    };
    let c2 = RuleCtx { base_url: &toc_url, ..c };
    let chapters = get_list(&src.t_chapter_list, &toc_html, &c2);
    let nch = chapters.len();
    line.push_str(&format!(" | 章数={}", nch));

    // 4) 抽前 N 章正文测字数
    let mut lens: Vec<usize> = Vec::new();
    for ch in chapters.iter().take(sample_n) {
        let ch_url_raw = get_string(&src.t_chapter_url, ch, &c2);
        if ch_url_raw.trim().is_empty() { continue; }
        let ch_url = abs_url(&toc_url, ch_url_raw.trim());
        let html = match fetcher.get(&ch_url, &src.header, "") {
            Ok(h) => h,
            Err(_) => continue,
        };
        let c3 = RuleCtx {
            base_url: &ch_url,
            header: &src.header,
            book_url: &book.book_url,
            book_name: &book.name,
            chapter_url: &ch_url,
            fetcher,
        };
        let (seg, _imgs) = source_engine::content::extract_content(&src.c_content, &html, &c3);
        lens.push(clean_len(&seg));
    }
    let short = lens.iter().filter(|&&n| n > 0 && n < 100).count();
    let empty = lens.iter().filter(|&&n| n == 0).count();
    if !lens.is_empty() {
        line.push_str(&format!(" | 样章字数={:?}", lens));
        line.push_str(&format!(" | 极短(<100)={} 空={}", short, empty));
    } else {
        line.push_str(" | 样章正文全空/抓取失败");
    }
    Some(line)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let key = args.get(1).cloned().unwrap_or_else(|| "斗破苍穹".to_string());
    let max_sources: usize = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(40);
    let sample_n: usize = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(3);
    let workers: usize = args.get(4).and_then(|s| s.parse().ok()).unwrap_or(8);
    let proxy = std::env::var("YG_PROXY").unwrap_or_else(|_| "127.0.0.1:17891".to_string());

    let json_path = r"C:\Users\Administrator\.dsh\profiles\desktop\bookSource.json";
    let raw = std::fs::read_to_string(json_path).expect("read bookSource.json failed");
    let val: serde_json::Value = serde_json::from_str(&raw).expect("parse json failed");
    let arr = val.as_array().unwrap();

    // 任务队列：(原始序号, 源 JSON)；序列号用于结果排序
    let (tx, rx) = mpsc::channel::<(usize, serde_json::Value)>();
    let mut total_tasks = 0usize;
    for (i, item) in arr.iter().enumerate() {
        if total_tasks >= max_sources { break; }
        tx.send((i + 1, item.clone())).unwrap();
        total_tasks += 1;
    }
    drop(tx);

    let results: Arc<Mutex<Vec<(usize, String)>>> = Arc::new(Mutex::new(Vec::new()));
    let rx = Arc::new(Mutex::new(rx));
    let done = Arc::new(Mutex::new(0usize));

    std::thread::scope(|s| {
        for _ in 0..workers {
            let rx = Arc::clone(&rx);
            let results = Arc::clone(&results);
            let done = Arc::clone(&done);
            let proxy = proxy.clone();
            let key = key.clone();
            // 每个 worker 独立 fetcher（ureq Agent 不共享，避免代理连接串扰）
            let fetcher = UreqFetcher { timeout_secs: 15, proxy: Some(proxy.clone()) };
            s.spawn(move || loop {
                let task = rx.lock().unwrap().recv().ok();
                let (idx, item) = match task {
                    Some(t) => t,
                    None => break,
                };
                if let Some(line) = probe_source(idx, &item, &key, &fetcher, sample_n) {
                    results.lock().unwrap().push((idx, line));
                }
                let mut d = done.lock().unwrap();
                *d += 1;
                eprintln!("progress {}/{}", *d, total_tasks);
            });
        }
    });

    let mut results = results.lock().unwrap().clone();
    results.sort_by_key(|(idx, _)| *idx);
    let processed = results.len();

    let mut out = String::new();
    out.push_str(&format!(
        "关键词={} 最多源数={} 每源抽章数={} 并发={} 总源数={} 实出结果={}\n\n",
        key, max_sources, sample_n, workers, arr.len(), processed
    ));
    let summary: Vec<String> = results.iter().map(|(_, l)| l.clone()).collect();
    out.push_str(&results.iter().map(|(_, l)| l.as_str()).collect::<Vec<_>>().join("\n"));
    out.push_str("\n===== 章数分布汇总（章数跨源异常判定依据）=====\n");
    out.push_str(&summary.join("\n"));
    out.push('\n');

    let out_path = r"C:\Users\Administrator\.dsh\im\quality_probe_out.txt";
    std::fs::write(out_path, &out).expect("write quality_probe_out.txt failed");
    println!("done -> {} (processed {} sources)", out_path, processed);
}
