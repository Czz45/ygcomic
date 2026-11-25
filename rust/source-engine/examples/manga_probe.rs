//! 离线探针：验证现有 extract_content 能否从漫画源 ruleContent 抽出图片 URL。
//! 不联网，只用内嵌 HTML + 真实漫画规则。若 images 非空 => 不用改 Rust/so。
//! 用法：cargo run --example manga_probe

use source_engine::content::extract_content;
use source_engine::http::UreqFetcher;
use source_engine::rule::RuleCtx;

fn main() {
    let fetcher = UreqFetcher { timeout_secs: 5, proxy: None };
    let base = "https://www.misskon.com/chapter/123.html";

    // 用例1：MissKon 真实规则 `.photo-item@img@all`，img 带 src / data-src
    let html1 = r#"<div class="photo-item">
        <img src="https://img.misskon.com/001.jpg">
        <img data-src="https://img.misskon.com/002.jpg">
        <img src="/003.jpg">
    </div>"#;
    let rule1 = ".photo-item@img@all";

    // 用例2：`<js>` 把 URL 列表拼成 <img> HTML（韩漫大全/包子漫画写法）
    let html2 = r#"<div id="cartoon-content">IMG_LIST</div>"#;
    let rule2 = "id.cartoon-content@html@js:result.split('\\n').map(function(x){return '<img src=\"'+x+'\">' }).join('\\n')";
    // 这个用例需要真实 HTML 里有 URL 列表，离线用一个变体：直接给一段已拼好的 <img> HTML + @html
    let html2b = r#"<div id="cartoon-content"><img src="https://img.hanman.com/a1.jpg"><img src="https://img.hanman.com/a2.jpg"></div>"#;
    let rule2b = "id.cartoon-content@html";

    // 用例3：`img@src` 直接抽
    let html3 = r#"<amp-img src="https://pics.baozi.com/x1.jpg"></amp-img><amp-img src="https://pics.baozi.com/x2.jpg"></amp-img>"#;
    let rule3 = "img@src";

    for (name, rule, html) in [
        ("case1 misskon @img@all", rule1, html1),
        ("case2b hanman @html", rule2b, html2b),
        ("case3 baozi img@src", rule3, html3),
        ("case2 js拼img(占位URL列表)", rule2, html2),
    ] {
        let ctx = RuleCtx {
            base_url: base,
            header: "",
            book_url: "",
            book_name: "",
            chapter_url: base,
            fetcher: &fetcher,
        };
        let (text, images) = extract_content(rule, html, &ctx);
        println!("=== {} ===", name);
        println!("  images.len={} {:?}", images.len(), images);
        println!("  text.len={} text={:?}", text.len(), text.chars().take(120).collect::<String>());
        println!();
    }
}
