//! 探针2：直接看 get_string 对真实漫画规则的输出，定位 ensure_html_extract 追加 @html 是否破坏 JS 拼图链。
use source_engine::http::UreqFetcher;
use source_engine::rule::{get_string, RuleCtx};

fn main() {
    let fetcher = UreqFetcher { timeout_secs: 5, proxy: None };
    let base = "https://www.hanmandq.com/chapter/1.html";
    let html = r#"<div id="cartoon-content">
https://img.hanman.com/a1.jpg
https://img.hanman.com/a2.jpg
</div>"#;

    // 韩漫大全真实规则：@img@data-src@js 拼 <img>（这里 data-src 用文本行的 URL 模拟）
    let rules = [
        ("A 纯@html", "id.cartoon-content@html"),
        ("B @html@js拼img", "id.cartoon-content@html@js:result.trim().split('\\n').map(function(x){return x.trim()===''?'':'<img src=\"'+x.trim()+'\">'}).filter(function(x){return x!==''}).join('\\n')"),
        ("C img@data-src@js(真规则形)", "id.cartoon-content@img@data-src@js:result.split('\\n').map(function(x){return '<img src=\"'+x+'\">'}).join('\\n')"),
        ("D <js>段拼img", "id.cartoon-content@html<js>result.trim().split('\\n').map(function(x){return x.trim()===''?'':'<img src=\"'+x.trim()+'\">'}).filter(function(x){return x!==''}).join('\\n')</js>"),
    ];
    for (name, rule) in rules {
        let ctx = RuleCtx { base_url: base, header: "", book_url: "", book_name: "", chapter_url: base, fetcher: &fetcher };
        let out = get_string(rule, html, &ctx);
        println!("=== {} ===\n  rule={}\n  out.len={} out={:?}\n  has<img>={}\n", name, rule, out.len(), out.chars().take(200).collect::<String>(), out.contains("<img"));
    }
}
