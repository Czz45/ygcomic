use source_engine::http::{Fetcher, UreqFetcher};
use source_engine::rule::{get_list, RuleCtx};

fn main() {
    let url = std::env::args().nth(1).expect("url");
    let f = UreqFetcher::default();
    let html = f.get(&url, "", "").expect("fetch");
    println!("html_len={}", html.len());
    let ctx = RuleCtx {
        base_url: &url,
        header: "",
        book_url: &url,
        book_name: "",
        chapter_url: "",
        fetcher: &f,
    };
    let rules = [
        ".l-box@.pure-g!0@.comics-chapters||.pure-g@.comics-chapters",
        ".pure-g@.comics-chapters",
        "#chapter-items@.comics-chapters",
        ".comics-chapters",
        "class.comics-chapters",
    ];
    for r in rules {
        let list = get_list(r, &html, &ctx);
        println!("rule=[{}] count={} first={:?}", r, list.len(), list.first().map(|s| &s[..s.len().min(80)]));
    }
    let (text, imgs) = source_engine::content::extract_content("amp-img@data-src", &html, &ctx);
    println!("content text_len={} images={} first={:?}", text.len(), imgs.len(), imgs.first());
}
