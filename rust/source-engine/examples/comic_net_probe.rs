//! 漫画源真实网络全链路探针（只读，不改引擎）
//! 对 yg_comic_verify_sources.json 里的验证漫画源发真实 HTTP 请求，
//! 走 搜索→详情→目录→图片章节 全链路，重点验证 extract_content 能否抽出图片 URL 列表。
//! 用法：YG_PROXY=127.0.0.1:17891 cargo run --example comic_net_probe -- "关键词" "每源抽章数"
//! 输出：C:\Users\Administrator\.dsh\im\comic_net_probe_out.txt

use source_engine::api::{search_with, BookSource};
use source_engine::http::{abs_url, Fetcher, UreqFetcher};
use source_engine::rule::{get_list, get_string, RuleCtx};

fn probe_source(
    idx: usize,
    item: &serde_json::Value,
    key: &str,
    fetcher: &UreqFetcher,
    sample_n: usize,
) -> String {
    let name = item.get("bookSourceName").and_then(|v| v.as_str()).unwrap_or("?").to_string();
    let url = item.get("bookSourceUrl").and_then(|v| v.as_str()).unwrap_or("?").to_string();
    let line_prefix = format!("[{:02}] {} <{}>", idx, name, url);

    let src = match BookSource::from_json(&item.to_string()) {
        Ok(s) => s,
        Err(e) => return format!("{} | ERR BookSource::from_json: {}", line_prefix, e),
    };

    let mut out = String::new();
    out.push_str(&line_prefix);
    out.push_str(&format!(
        " | search_url={} toc_rule={} content_rule={}",
        if src.search_url.trim().is_empty() { "空" } else { "有" },
        if src.t_chapter_list.trim().is_empty() { "空" } else { "有" },
        if src.c_content.trim().is_empty() { "空" } else { "有" },
    ));

    // 1) 搜索（漫画源可能没有 search_url，跳过则用 explore / book_url 直连）
    let book_opt = if src.search_url.trim().is_empty() {
        out.push_str(" | [搜索跳过:无searchUrl]");
        None
    } else {
        match search_with(&src, key, 1, fetcher) {
            Ok(books) if !books.is_empty() => {
                let b = books.iter().find(|b| b.name.contains(key)).unwrap_or(&books[0]).clone();
                out.push_str(&format!(" | 搜到{}本 取《{}》", books.len(), b.name));
                Some(b)
            }
            Ok(_) => { out.push_str(" | [搜索0本]"); None }
            Err(e) => { out.push_str(&format!(" | ERR search: {}", e)); None }
        }
    };

    // 没有搜索结果就跳过该源（验证源都配了 searchUrl，理论上能搜到）
    let book = match book_opt {
        Some(b) => b,
        None => {
            out.push_str(" | [搜索无结果，跳过]");
            return out;
        }
    };

    let c = RuleCtx {
        base_url: &book.book_url,
        header: &src.header,
        book_url: &book.book_url,
        book_name: &book.name,
        chapter_url: "",
        fetcher,
    };

    // 2) 详情 → toc_url
    let detail_html = match fetcher.get(&book.book_url, &src.header, "") {
        Ok(h) => h,
        Err(e) => { out.push_str(&format!(" | ERR detail: {}", e)); return out; }
    };
    let toc_raw = get_string(&src.d_toc_url, &detail_html, &c);
    let toc_url = if !toc_raw.trim().is_empty() {
        abs_url(&book.book_url, toc_raw.trim())
    } else if let Some(read_url) = source_engine::api::detect_meta_content(&detail_html, "og:novel:read_url") {
        abs_url(&book.book_url, &read_url)
    } else {
        book.book_url.clone()
    };
    out.push_str(&format!(" | detail_html={}B toc={}", detail_html.len(), toc_url));

    // 3) 目录 → chapters
    let toc_html = if toc_url == book.book_url {
        detail_html.clone()
    } else {
        match fetcher.get(&toc_url, &src.header, "") {
            Ok(h) => h,
            Err(e) => { out.push_str(&format!(" | ERR toc: {}", e)); return out; }
        }
    };
    let c2 = RuleCtx { base_url: &toc_url, ..c };
    let chapters = get_list(&src.t_chapter_list, &toc_html, &c2);
    out.push_str(&format!(" | toc_html={}B 章数={}", toc_html.len(), chapters.len()));

    // 4) 图片章节 → extract_content 抽图片 URL（核心验证点）
    let mut total_imgs = 0usize;
    let mut sample_urls: Vec<String> = Vec::new();
    let mut chapters_done = 0usize;
    for ch in chapters.iter().take(sample_n) {
        let ch_url_raw = get_string(&src.t_chapter_url, ch, &c2);
        if ch_url_raw.trim().is_empty() { continue; }
        let ch_url = abs_url(&toc_url, ch_url_raw.trim());
        let html = match fetcher.get(&ch_url, &src.header, "") {
            Ok(h) => h,
            Err(e) => { out.push_str(&format!(" | ERR ch[{}]:{}", ch_url, e)); continue; }
        };
        let c3 = RuleCtx {
            base_url: &ch_url,
            header: &src.header,
            book_url: &book.book_url,
            book_name: &book.name,
            chapter_url: &ch_url,
            fetcher,
        };
        let (seg, imgs) = source_engine::content::extract_content(&src.c_content, &html, &c3);
        chapters_done += 1;
        total_imgs += imgs.len();
        out.push_str(&format!(
            "\n     章html={}B seg={}字 图={}张",
            html.len(), seg.chars().count(), imgs.len()
        ));
        for u in imgs.iter().take(3) {
            out.push_str(&format!("\n       - {}", u));
        }
        if sample_urls.len() < 3 { sample_urls.extend(imgs.into_iter().take(3)); }
    }
    out.push_str(&format!(
        "\n   ✅ 汇总: 抽{}章 共{}张图{}",
        chapters_done, total_imgs,
        if total_imgs > 0 { " —— 抽图成功" } else { " —— ⚠️未抽到图" }
    ));
    out
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let key = args.get(1).cloned().unwrap_or_else(|| "斗破苍穹".to_string());
    let sample_n: usize = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(2);
    let json_path = args.get(3).cloned()
        .unwrap_or_else(|| r"C:\Users\Administrator\.dsh\im\yiove_premium_sources.json".to_string());
    let out_path = args.get(4).cloned()
        .unwrap_or_else(|| r"C:\Users\Administrator\.dsh\im\comic_net_probe_out.txt".to_string());
    let proxy = std::env::var("YG_PROXY").unwrap_or_else(|_| "127.0.0.1:17891".to_string());
    let fetcher = UreqFetcher { timeout_secs: 20, proxy: Some(proxy.clone()) };

    let raw = std::fs::read_to_string(&json_path).expect("read source list json failed");
    let arr: Vec<serde_json::Value> = serde_json::from_str(&raw).expect("parse json failed");

    let mut out = String::new();
    out.push_str(&format!(
        "漫画源真实网络全链路探针 | 关键词={} 每源抽章数={} 代理={}\n源数={}\n\n",
        key, sample_n, proxy, arr.len()
    ));
    for (i, item) in arr.iter().enumerate() {
        eprintln!(" probing {}/{} ...", i + 1, arr.len());
        let line = probe_source(i + 1, item, &key, &fetcher, sample_n);
        out.push_str(&line);
        out.push_str("\n\n");
        println!("{}", line);
    }

    std::fs::write(&out_path, &out).expect("write probe out failed");
    println!("done -> {}", out_path);
}
