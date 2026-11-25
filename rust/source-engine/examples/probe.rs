//! 逐源探针：用真实网络对每个书源搜索关键词，定位哪些源失败/漏结果。
//! 仅开发机原生测试用，生产不编译进 JNI（examples 不进 .so）。
//! 用法：cargo run --example probe -- [源名过滤子串] [关键词] [书源json路径]
//!   可选参数1：源名过滤子串（默认全部）
//!   可选参数2：关键词（默认 斗破苍穹）
//!   可选参数3：书源 json 路径（默认 bookSource.json，可传 mochen_sources.json / yiove_sources.json）

use source_engine::api::{search_with, BookSource};
use source_engine::http::UreqFetcher;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let filter = args.get(1).cloned().unwrap_or_default();
    let key = args.get(2).cloned().unwrap_or_else(|| "斗破苍穹".to_string());
    let proxy = std::env::var("YG_PROXY").unwrap_or_else(|_| "127.0.0.1:17891".to_string());

    let json_path = args.get(3).cloned().unwrap_or_else(|| {
        r"C:\Users\Administrator\.dsh\profiles\desktop\bookSource.json".to_string()
    });
    let raw = std::fs::read_to_string(json_path).expect("read bookSource.json failed");
    let val: serde_json::Value = serde_json::from_str(&raw).expect("parse bookSource.json failed");
    let arr = val.as_array().expect("bookSource.json is not an array");

    // 短超时 fetcher（8s），走显式代理；生产 UreqFetcher::default() 不受影响
    let fetcher = UreqFetcher { timeout_secs: 8, proxy: Some(proxy.clone()) };

    let mut report = String::new();
    report.push_str(&format!("keyword={} proxy={} total_sources={}\n\n", key, proxy, arr.len()));

    let mut tested = 0usize;
    let mut ok_cnt = 0usize;
    for item in arr {
        let name = item.get("bookSourceName").and_then(|v| v.as_str()).unwrap_or("?").to_string();
        if !filter.is_empty() && !name.contains(&filter) {
            continue;
        }
        let item_json = item.to_string();
        let src = match BookSource::from_json(&item_json) {
            Ok(s) => s,
            Err(e) => {
                report.push_str(&format!("[PARSE_FAIL] {} -> {}\n", name, e));
                continue;
            }
        };
        if src.search_url.is_empty() {
            report.push_str(&format!("[NO_SEARCH_URL] {}\n", name));
            continue;
        }
        tested += 1;
        let t0 = std::time::Instant::now();
        match search_with(&src, &key, 1, &fetcher) {
            Ok(books) => {
                if books.is_empty() {
                    report.push_str(&format!("[EMPTY] {} | {:.1}s | url={}\n", name, t0.elapsed().as_secs_f64(), src.search_url));
                } else {
                    ok_cnt += 1;
                    let names: Vec<String> = books.iter().take(8).map(|b| format!("{}({})", b.name, b.author)).collect();
                    let exact = books.iter().filter(|b| b.name.contains(&key)).count();
                    report.push_str(&format!("[OK n={} exact={} {:.1}s] {} | {}\n", books.len(), exact, t0.elapsed().as_secs_f64(), name, names.join(", ")));
                }
            }
            Err(e) => {
                report.push_str(&format!("[ERR] {} | {:.1}s | {} | url={}\n", name, t0.elapsed().as_secs_f64(), e, src.search_url));
            }
        }
    }
    report.push_str(&format!("\n=== tested={} ok={} ===\n", tested, ok_cnt));

    let out = r"C:\Users\Administrator\.dsh\im\probe_out.txt";
    std::fs::write(out, &report).expect("write probe_out.txt failed");
    println!("done. tested={} ok={} -> {}", tested, ok_cnt, out);
}
