//! 逐源定位探针：串行对每个书源搜索「斗破苍穹」，每源立即 flush 打印 index/名字/URL/耗时/结果数。
//! 崩溃时最后打印的那一行就是触发 native 崩溃的罪魁源。
//! 仅开发机原生测试用，不进 .so。
//! 用法：cargo run --example hunt -- [起始index] [结束index(不含)] [超时秒数]
//!   例：cargo run --example hunt -- 0 300 5

use source_engine::api::{search_with, BookSource};
use source_engine::http::UreqFetcher;
use std::io::Write;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let start: usize = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(0);
    let end: usize = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(300);
    let timeout: u64 = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(5);
    let key = "斗破苍穹";
    let proxy = std::env::var("YG_PROXY").unwrap_or_else(|_| "127.0.0.1:17891".to_string());

    // argv[4] 可覆盖书源 JSON 路径；默认 yiove_sources.json
    let json_path = args.get(4).cloned().unwrap_or_else(|| {
        r"C:\Users\Administrator\.dsh\im\yiove_sources.json".to_string()
    });
    let raw = std::fs::read_to_string(&json_path).expect("read sources json failed");
    let val: serde_json::Value = serde_json::from_str(&raw).expect("parse json failed");
    let arr = val.as_array().expect("not an array");

    let fetcher = UreqFetcher { timeout_secs: timeout, proxy: Some(proxy.clone()) };
    let log_path = r"C:\Users\Administrator\.dsh\im\hunt_out.txt";
    let mut log = std::fs::File::create(log_path).expect("create hunt_out.txt failed");

    let end = end.min(arr.len());
    writeln!(log, "key={} proxy={} total={} range=[{},{}]", key, proxy, arr.len(), start, end).ok();
    log.flush().ok();

    let mut slow: Vec<(usize, String, f64)> = Vec::new();
    for idx in start..end {
        let item = &arr[idx];
        let name = item.get("bookSourceName").and_then(|v| v.as_str()).unwrap_or("?").to_string();
        let url = item.get("bookSourceUrl").and_then(|v| v.as_str()).unwrap_or("?").to_string();

        // 崩溃时最后 flush 的这一行就是罪魁源
        let head = format!("[{idx}] {name} | {url}");
        print!("START {head} ... ");
        std::io::stdout().flush().ok();
        writeln!(log, "START {head}").ok();
        log.flush().ok();

        let item_json = item.to_string();
        let t0 = std::time::Instant::now();
        let result = match BookSource::from_json(&item_json) {
            Err(e) => format!("PARSE_FAIL {}", e),
            Ok(src) => {
                if src.search_url.is_empty() {
                    "NO_SEARCH_URL".to_string()
                } else {
                    match search_with(&src, key, 1, &fetcher) {
                        Ok(books) => format!("OK n={}", books.len()),
                        Err(e) => format!("ERR {}", e),
                    }
                }
            }
        };
        let secs = t0.elapsed().as_secs_f64();
        println!("{result} ({secs:.1}s)");
        std::io::stdout().flush().ok();
        writeln!(log, "  -> {result} ({secs:.1}s)").ok();
        log.flush().ok();
        if secs > 3.0 {
            slow.push((idx, name.clone(), secs));
        }
    }

    writeln!(log, "\n=== SLOW (>3s) ===").ok();
    for (idx, name, secs) in &slow {
        writeln!(log, "[{idx}] {name} {secs:.1}s").ok();
    }
    log.flush().ok();
    println!("done range=[{start},{end}) -> {log_path}");
}
