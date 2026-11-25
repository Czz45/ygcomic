//! 目录解析探针：拿真实书源跑 search -> detail -> toc，检查目录结构是否健康。
//! 用法：cargo run --example toc_dump -- "源名子串" "书名关键词" [最多测几个源]
//! 输出：C:\Users\Administrator\.dsh\im\toc_dump_out.txt

use std::collections::HashMap;
use std::fmt::Write as _;
use source_engine::api::{self, BookSource};

fn main() {
    let filter = std::env::args().nth(1).unwrap_or_default();
    let key = std::env::args().nth(2).unwrap_or_else(|| "斗破苍穹".to_string());
    let limit: usize = std::env::args().nth(3).and_then(|s| s.parse().ok()).unwrap_or(5);

    let json_path = r"C:\Users\Administrator\.dsh\profiles\desktop\bookSource.json";
    let raw = std::fs::read_to_string(json_path).expect("read bookSource.json failed");
    let val: serde_json::Value = serde_json::from_str(&raw).expect("parse json failed");
    let arr = val.as_array().expect("not array").clone();

    let mut out = String::new();
    let mut tested = 0usize;
    for item in arr.iter() {
        if tested >= limit { break; }
        let name = item.get("bookSourceName").and_then(|v| v.as_str()).unwrap_or("?");
        if !filter.is_empty() && !name.contains(&filter) {
            continue;
        }
        let src = match BookSource::from_json(&item.to_string()) {
            Ok(s) => s,
            Err(e) => { let _ = writeln!(out, "[PARSE_FAIL] {} {}", name, e); continue; }
        };
        tested += 1;
        let _ = writeln!(out, "\n===== [{}] {} =====", tested, name);
        let _ = writeln!(out, "toc_rule_list={}", src.t_chapter_list);
        let _ = writeln!(out, "toc_rule_name={}", src.t_chapter_name);
        let _ = writeln!(out, "toc_rule_url={}", src.t_chapter_url);

        let books = match api::search(&src, &key, 1) {
            Ok(b) => b,
            Err(e) => { let _ = writeln!(out, "[SEARCH_ERR] {}", e); continue; }
        };
        if books.is_empty() { let _ = writeln!(out, "[SEARCH_EMPTY]"); continue; }
        let book = &books[0];
        let _ = writeln!(out, "book={} author={} url={}", book.name, book.author, book.book_url);

        let detail = match api::detail(&src, &book.book_url) {
            Ok(d) => d,
            Err(e) => { let _ = writeln!(out, "[DETAIL_ERR] {}", e); continue; }
        };
        let _ = writeln!(out, "toc_url={}", detail.toc_url);

        let chapters = match api::toc(&src, &detail.toc_url) {
            Ok(c) => c,
            Err(e) => { let _ = writeln!(out, "[TOC_ERR] {}", e); continue; }
        };
        let _ = writeln!(out, "count={}", chapters.len());

        // 结构体检
        let mut url_map: HashMap<&str, usize> = HashMap::new();
        let mut title_map: HashMap<&str, usize> = HashMap::new();
        let mut empty_title = 0usize;
        let mut empty_url = 0usize;
        let mut relative_url = 0usize;
        let mut volume_like = 0usize;
        let mut latest_like = 0usize;
        for c in chapters.iter() {
            *url_map.entry(c.url.as_str()).or_insert(0) += 1;
            *title_map.entry(c.title.as_str()).or_insert(0) += 1;
            if c.title.trim().is_empty() { empty_title += 1; }
            if c.url.trim().is_empty() { empty_url += 1; }
            if !c.url.starts_with("http") { relative_url += 1; }
            if c.title.contains('卷') { volume_like += 1; }
            if c.title.contains("最新") { latest_like += 1; }
        }
        let dup_url: Vec<(&&str, &usize)> = url_map.iter().filter(|(_, v)| **v > 1).collect();
        let dup_title: Vec<(&&str, &usize)> = title_map.iter().filter(|(_, v)| **v > 1).collect();
        let _ = writeln!(out, "empty_title={} empty_url={} relative_url={} volume_like={} latest_like={}",
            empty_title, empty_url, relative_url, volume_like, latest_like);
        let _ = writeln!(out, "unique_url={} unique_title={} dup_url_groups={} dup_title_groups={}",
            url_map.len(), title_map.len(), dup_url.len(), dup_title.len());
        let mut dups: Vec<String> = dup_url.iter().map(|(u, n)| format!("x{} {}", n, u)).collect();
        dups.sort();
        for d in dups.iter().take(8) { let _ = writeln!(out, "  DUP_URL {}", d); }
        let mut dts: Vec<String> = dup_title.iter().map(|(t, n)| format!("x{} {}", n, t)).collect();
        dts.sort();
        for d in dts.iter().take(8) { let _ = writeln!(out, "  DUP_TITLE {}", d); }

        let _ = writeln!(out, "-- 前 12 条 --");
        for (i, c) in chapters.iter().take(12).enumerate() {
            let _ = writeln!(out, "  #{} title={:?} url={}", i, c.title, &c.url[..c.url.len().min(90)]);
        }
        let _ = writeln!(out, "-- 后 6 条 --");
        let start = chapters.len().saturating_sub(6);
        for (i, c) in chapters.iter().enumerate().skip(start) {
            let _ = writeln!(out, "  #{} title={:?} url={}", i, c.title, &c.url[..c.url.len().min(90)]);
        }
    }
    if tested == 0 { out.push_str("[NO_MATCH] 没有匹配的书源\n"); }
    std::fs::write(r"C:\Users\Administrator\.dsh\im\toc_dump_out.txt", &out).expect("write out");
    println!("{}", out);
}
