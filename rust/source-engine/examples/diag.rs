//! 诊断探针：对单个书源抓真实 HTML，dump 原始响应 + 规则解析中间结果。
//! 用法：YG_PROXY=127.0.0.1:17891 cargo run --example diag -- "源名子串" "关键词"
//! 输出写到 C:\Users\Administrator\.dsh\im\diag_out.txt

use source_engine::api::BookSource;
use source_engine::http::{split_request_options, Fetcher, UreqFetcher};
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

    let fetcher = UreqFetcher { timeout_secs: 15, proxy: Some(proxy.clone()) };
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
        out.push_str(&format!("===== {} =====\n", name));
        out.push_str(&format!("bookSourceUrl={}\nsearch_url={}\n", src.url, src.search_url));
        out.push_str(&format!("ruleSearch.bookList={}\n", src.s_book_list));
        out.push_str(&format!("ruleSearch.name={}\n", src.s_name));
        out.push_str(&format!("ruleSearch.bookUrl={}\n", src.s_book_url));
        out.push_str(&format!("ruleSearch.coverUrl={}\n", src.s_cover));

        let (tmpl, method, body, charset) = split_request_options(&src.search_url, &key, 1);
        let url = source_engine::http::build_url(&tmpl, &key, 1, &src.url, &src.url);
        out.push_str(&format!("--> method={} body={} charset={}\n--> final_url={}\n", method, body, charset, url));

        let html = match fetcher.request(&url, &src.header, &method, &body, &charset) {
            Ok(h) => h,
            Err(e) => { out.push_str(&format!("[FETCH_ERR] {}\n\n", e)); continue; }
        };
        out.push_str(&format!("--> html_len={}\n", html.len()));
        // 保存原始 HTML 到独立文件
        let safe: String = name.chars().map(|c| if c.is_alphanumeric() || c=='_' {c} else {'_'}).collect();
        let html_path = format!(r"C:\Users\Administrator\.dsh\im\diag_{}.html", safe);
        let _ = std::fs::write(&html_path, &html);
        out.push_str(&format!("--> html_saved={}\n", html_path));
        out.push_str(&format!("--> html_head={}\n", html.chars().take(600).collect::<String>()));

        let ctx = RuleCtx {
            base_url: &url,
            header: &src.header,
            book_url: "",
            book_name: "",
            chapter_url: "",
            fetcher: &fetcher,
        };
        let list = get_list(&src.s_book_list, &html, &ctx);
        out.push_str(&format!("--> get_list n={}\n", list.len()));
        let mut nonempty = 0usize;
        for (i, item_html) in list.iter().enumerate() {
            let n = get_string(&src.s_name, item_html, &ctx);
            let u = get_string(&src.s_book_url, item_html, &ctx);
            let cv = get_string(&src.s_cover, item_html, &ctx);
            if !n.trim().is_empty() { nonempty += 1; }
            if i < 40 {
                out.push_str(&format!("    [{}] name={:?} url={:?} cover={:?} item_head={}\n",
                    i, n, u, cv, item_html.chars().take(160).collect::<String>()));
            }
        }
        out.push_str(&format!("--> name_nonempty={} / {}\n\n", nonempty, list.len()));
    }

    let out_path = r"C:\Users\Administrator\.dsh\im\diag_out.txt";
    std::fs::write(out_path, &out).expect("write diag_out.txt failed");
    println!("done -> {}", out_path);
}
