//! 复现测试：用 Windows 原生抓取的真实阅友搜索页，验证规则引擎缺陷。
//! 运行：cargo run --example repro_yueyou

use source_engine::http::Fetcher;
use source_engine::rule::{get_list, get_string, RuleCtx};

struct DummyFetcher;
impl Fetcher for DummyFetcher {
    fn get(&self, _url: &str, _h: &str, _c: &str) -> Result<String, String> {
        Ok(String::new())
    }
}

fn main() {
    let path = r"C:\Users\Administrator\.dsh\im\srctest\yueyou_search.html";
    let html = std::fs::read_to_string(path).expect("读取 yueyou_search.html 失败");
    println!("HTML bytes = {}", html.len());

    let fetcher = DummyFetcher;
    let ctx = RuleCtx {
        base_url: "https://sma.yueyouxs.com",
        header: "",
        book_url: "",
        book_name: "",
        chapter_url: "",
        fetcher: &fetcher,
    };

    // bookList = .v-list-item
    let items = get_list(".v-list-item", &html, &ctx);
    println!("bookList items = {}", items.len());

    let rules: Vec<(&str, &str)> = vec![
        ("name", ".v-title@text"),
        ("author", ".v-author@text##\\s"),
        ("kind", ".base-label@text"),
        ("wordCount", ".v-words@text"),
        ("coverUrl", "img@src"),
        ("bookUrl", "@onclick@js:result.match(/\\('(.*?)', '', ''\\)/)[1]"),
    ];

    for (i, item) in items.iter().enumerate() {
        println!("--- item {} ---", i);
        for (label, rule) in &rules {
            println!("  {:10} = {:?}", label, get_string(rule, item, &ctx));
        }
        if i == 0 {
            println!("  [diag] onclick attr = {:?}", get_string("onclick", item, &ctx));
            println!("  [diag] onclick@js:return result = {:?}",
                get_string("onclick@js:result", item, &ctx));
            println!("  [diag] js literal 'hello' = {:?}",
                get_string("@js:'hello'", item, &ctx));
            println!("  [diag] js 1+1 = {:?}",
                get_string("@js:1+1", item, &ctx));
            println!("  [diag] js result = {:?}",
                get_string("@js:result", item, &ctx));
            println!("  [diag] full onclick html head = {:?}", &item[..item.len().min(160)]);
        }
    }
}
