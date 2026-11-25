//! 端到端演示：对一个本地 HTTP 服务（fixture 页面）跑 搜索→详情→目录→正文。
//! 用法：先 `python -m http.server 8910` 于 fixture 目录，再 `cargo run --example e2e`。

use source_engine::api::{self, BookSource};

fn main() {
    // 测试环境里宿主机设了 HTTP(S)_PROXY（会劫持本地 127.0.0.1 请求），清掉。
    std::env::remove_var("HTTP_PROXY");
    std::env::remove_var("HTTPS_PROXY");
    std::env::remove_var("http_proxy");
    std::env::remove_var("https_proxy");
    std::env::remove_var("ALL_PROXY");
    std::env::remove_var("all_proxy");
    let base = "http://127.0.0.1:8910";
    let source_json = r#"{
        "bookSourceName": "本地测试源",
        "bookSourceUrl": "http://127.0.0.1:8910",
        "searchUrl": "http://127.0.0.1:8910/search.html?keyword={{key}}",
        "ruleSearch": {
            "bookList": "@css:.book-list .book",
            "name": "@css:.name@text",
            "author": "@css:.author@text",
            "kind": "@css:.kind@text",
            "bookUrl": "@css:.name@href"
        },
        "ruleBookInfo": {
            "name": "@css:h1.book-name@text",
            "author": "@css:.info .a@text",
            "kind": "@css:.info .k@text",
            "intro": "@css:#intro@text",
            "tocUrl": "@css:#toc-link@href"
        },
        "ruleToc": {
            "chapterList": "@css:#list a",
            "chapterName": "@css:a@text",
            "chapterUrl": "@css:a@href"
        },
        "ruleContent": {
            "content": "@css:#content",
            "title": "@css:h1.title@text"
        }
    }"#;

    let src = BookSource::from_json(source_json).expect("解析源失败");
    println!("=== 源: {} ({}) ===", src.name, src.url);

    println!("\n--- 1) 搜索 '斗破' ---");
    let books = api::search(&src, "斗破", 1).expect("搜索失败");
    for b in &books {
        println!("  {} / {} / {} / {}", b.name, b.author, b.kind, b.book_url);
    }

    if let Some(first) = books.first() {
        println!("\n--- 2) 详情 ---");
        let d = api::detail(&src, &first.book_url).expect("详情失败");
        println!("  书名={} 作者={} 分类={} 简介={}", d.name, d.author, d.kind, d.intro);
        println!("  目录URL={}", d.toc_url);

        println!("\n--- 3) 目录 ---");
        let toc = api::toc(&src, &d.toc_url).expect("目录失败");
        for c in &toc {
            println!("  {} -> {}", c.title, c.url);
        }

        println!("\n--- 4) 正文（第2章，含图片）---");
        if let Some(ch) = toc.get(1) {
            let content = api::content(&src, &ch.url).expect("正文失败");
            println!("  标题: {}", content.title);
            println!("  正文:\n{}", content.text);
            println!("  图片({}张): {:?}", content.images.len(), content.images);
            assert!(content.text.contains('\u{FFFC}'), "正文应含图片占位符");
            assert_eq!(content.images.len(), 1, "应抽到1张图");
        }
    }
    println!("\n=== 端到端 OK ===");
    let _ = base;
}
