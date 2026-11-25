//! 真实网络诊断：对单个书源抓详情页 + 目录页 HTML，用修复后的规则解析，
//! 验证书名/作者/分类/字数/最新/简介/封面/目录章数是否全部正确。
//! 用法：YG_PROXY=127.0.0.1:17891 cargo run --example diag_detail -- "源名子串" "关键词"
//! 输出写到 C:\Users\Administrator\.dsh\im\diag_detail_out.txt

use source_engine::api::{search_with, BookSource};
use source_engine::http::{abs_url, Fetcher, UreqFetcher};
use source_engine::rule::{get_list, get_string, RuleCtx};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let filter = args.get(1).cloned().unwrap_or_default();
    let key = args.get(2).cloned().unwrap_or_else(|| "斗破苍穹".to_string());
    let proxy = std::env::var("YG_PROXY").unwrap_or_else(|_| "127.0.0.1:17891".to_string());

    let json_path = r"C:\Users\Administrator\.dsh\profiles\desktop\bookSource.json";
    let raw = std::fs::read_to_string(json_path).expect("read bookSource.json failed");
    let val: serde_json::Value = serde_json::from_str(&raw).expect("parse json failed");
    let arr = val.as_array().unwrap();

    let fetcher = UreqFetcher { timeout_secs: 20, proxy: Some(proxy.clone()) };
    let mut out = String::new();

    for item in arr {
        let name = item.get("bookSourceName").and_then(|v| v.as_str()).unwrap_or("?").to_string();
        let item_json = item.to_string();
        let src = match BookSource::from_json(&item_json) {
            Ok(s) => s,
            Err(e) => { out.push_str(&format!("[PARSE_FAIL] {} {}\n", name, e)); continue; }
        };
        if !filter.is_empty() && !name.contains(&filter) && !src.url.contains(&filter) {
            continue;
        }
        out.push_str(&format!("\n========== {} ==========\n", name));
        out.push_str(&format!("url={}\n", src.url));

        // 1) 搜索拿第一本书的 URL
        let books = match search_with(&src, &key, 1, &fetcher) {
            Ok(b) => b,
            Err(e) => { out.push_str(&format!("[SEARCH_ERR] {}\n", e)); continue; }
        };
        if books.is_empty() { out.push_str("[SEARCH_EMPTY]\n"); continue; }
        let book = &books[0];
        out.push_str(&format!("book.name={:?} book.author={:?} book.url={}\n",
            book.name, book.author, book.book_url));

        // 2) 抓详情页 HTML
        let detail_html = match fetcher.get(&book.book_url, &src.header, "") {
            Ok(h) => h,
            Err(e) => { out.push_str(&format!("[DETAIL_FETCH_ERR] {}\n", e)); continue; }
        };
        out.push_str(&format!("detail_html_len={}\n", detail_html.len()));
        let safe: String = name.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '_' }).collect();
        let _ = std::fs::write(format!(r"C:\Users\Administrator\.dsh\im\diag_page_{}_detail.html", safe), &detail_html);

        let c = RuleCtx {
            base_url: &book.book_url,
            header: &src.header,
            book_url: &book.book_url,
            book_name: &book.name,
            chapter_url: "",
            fetcher: &fetcher,
        };
        // 3) 用修复后的规则解析详情字段
        out.push_str(&format!("  d_name       = {:?}\n", get_string(&src.d_name, &detail_html, &c)));
        out.push_str(&format!("  d_author     = {:?}\n", get_string(&src.d_author, &detail_html, &c)));
        out.push_str(&format!("  d_kind       = {:?}\n", get_string(&src.d_kind, &detail_html, &c)));
        out.push_str(&format!("  d_word_count = {:?}\n", get_string(&src.d_word_count, &detail_html, &c)));
        out.push_str(&format!("  d_last_chap  = {:?}\n", get_string(&src.d_last_chapter, &detail_html, &c)));
        let intro = get_string(&src.d_intro, &detail_html, &c);
        out.push_str(&format!("  d_intro      = {:?} (len={})\n", intro.chars().take(60).collect::<String>(), intro.len()));
        let cover = get_string(&src.d_cover, &detail_html, &c);
        out.push_str(&format!("  d_cover      = {:?}\n", cover));

        // 4) 目录：优先 d_toc_url，空则探测 og:novel:read_url，再回退 book_url（与 api::detail 一致）
        let toc_raw = get_string(&src.d_toc_url, &detail_html, &c);
        let toc_url = if !toc_raw.trim().is_empty() {
            abs_url(&book.book_url, toc_raw.trim())
        } else if let Some(read_url) = source_engine::api::detect_meta_content(&detail_html, "og:novel:read_url") {
            abs_url(&book.book_url, &read_url)
        } else {
            book.book_url.clone()
        };
        out.push_str(&format!("  toc_url      = {}\n", toc_url));
        let toc_html = if toc_url == book.book_url {
            detail_html.clone()
        } else {
            match fetcher.get(&toc_url, &src.header, "") {
                Ok(h) => h,
                Err(e) => { out.push_str(&format!("[TOC_FETCH_ERR] {}\n", e)); continue; }
            }
        };
        out.push_str(&format!("  toc_html_len = {}\n", toc_html.len()));
        let _ = std::fs::write(format!(r"C:\Users\Administrator\.dsh\im\diag_page_{}_toc.html", safe), &toc_html);
        let c2 = RuleCtx { base_url: &toc_url, ..c };
        let chapters = get_list(&src.t_chapter_list, &toc_html, &c2);
        out.push_str(&format!("  >>> CHAPTER COUNT = {}\n", chapters.len()));
        for (i, ch) in chapters.iter().take(5).enumerate() {
            let t = get_string(&src.t_chapter_name, ch, &c2);
            let u = get_string(&src.t_chapter_url, ch, &c2);
            out.push_str(&format!("      [{}] {:?} -> {:?}\n", i, t, u));
        }
    }

    let out_path = r"C:\Users\Administrator\.dsh\im\diag_detail_out.txt";
    std::fs::write(out_path, &out).expect("write diag_detail_out.txt failed");
    println!("done -> {}", out_path);
}
