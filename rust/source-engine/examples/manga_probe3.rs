//! 探针3：真实漫画HTML(<img data-src>)+真规则形态，直接 get_string 确认 @img@data-src@js 拼图链能出 <img>。
use source_engine::http::UreqFetcher;
use source_engine::rule::{get_string, RuleCtx};

fn main() {
    let fetcher = UreqFetcher { timeout_secs: 5, proxy: None };
    let base = "https://www.hanmandq.com/chapter/1.html";
    // 韩漫大全真实页面形态：容器里是若干 <img data-src="URL">
    let html = r#"<div id="cartoon-content">
<img data-src="https://img.hanman.com/a1.jpg">
<img data-src="https://img.hanman.com/a2.jpg">
<img data-src="https://img.hanman.com/a3.jpg">
</div>"#;
    let rules = [
        ("C1 img@data-src", "id.cartoon-content@img@data-src"),
        ("C2 img@data-src@js拼img", "id.cartoon-content@img@data-src@js:result.split('\\n').map(function(x){return x.trim()===''?'':'<img src=\"'+x.trim()+'\">'}).filter(function(x){return x!==''}).join('\\n')"),
        ("C3 img@all", "id.cartoon-content@img@all"),
    ];
    for (name, rule) in rules {
        let ctx = RuleCtx { base_url: base, header: "", book_url: "", book_name: "", chapter_url: base, fetcher: &fetcher };
        let out = get_string(rule, html, &ctx);
        println!("=== {} ===\n  out.len={} out={:?}\n  has<img>={}\n", name, out.len(), out.chars().take(300).collect::<String>(), out.contains("<img"));
    }
}
