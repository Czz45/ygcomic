//! 离线单元测试 —— 验证规则引擎与正文抽取逻辑（不发网络）。

use crate::api::BookSource;
use crate::http::{build_url, eval_arith, Fetcher};
use crate::rule::{get_list, get_string, RuleCtx};

struct DummyFetcher;
impl Fetcher for DummyFetcher {
    fn get(&self, _url: &str, _h: &str, _c: &str) -> Result<String, String> {
        Ok(String::new())
    }
}

/// 返回固定假 JSON 的 fetcher，用于验证酷我这类 JSON API 源的解析链路（不依赖网络）。
struct JsonMockFetcher;
impl Fetcher for JsonMockFetcher {
    fn get(&self, _url: &str, _h: &str, _c: &str) -> Result<String, String> {
        Ok(r#"{
          "code": 200,
          "data": [
            {"book_id":14228,"title":"斗破苍穹","author_name":"天蚕土豆","cover_url":"http://img/1.jpg","intro":"斗气世界","all_words":5324000,"category_name":"玄幻","status":50},
            {"book_id":14229,"title":"斗破苍穹前传","author_name":"天蚕土豆","cover_url":"http://img/2.jpg","intro":"前传","all_words":1234000,"category_name":"玄幻","status":50},
            {"book_id":14230,"title":"武动乾坤","author_name":"天蚕土豆","cover_url":"http://img/3.jpg","intro":"武动","all_words":3000000,"category_name":"玄幻","status":50}
          ]
        }"#.to_string())
    }
}

/// 酷我小说这类 JSON API 源：字段规则里含 {{$.field}} 内插模板
/// （bookUrl=/novels/api/book/{{$.book_id}}）。修前 get_string 不认识 {{...}}，
/// 整源解析 0 本；修后应解析出 3 本，且 book_url 拼出真实路径。
#[test]
fn json_api_field_template_interpolation() {
    let src = BookSource::from_json(r#"{
      "bookSourceName":"酷我小说","bookSourceUrl":"http://appi.kuwo.cn",
      "searchUrl":"/novels/api/book/search?keyword={{key}}&pi={{page}}&ps=30",
      "ruleSearch":{
        "bookList":"$.data","name":"$.title",
        "bookUrl":"/novels/api/book/{{$.book_id}}",
        "author":"$.author_name","coverUrl":"$.cover_url","intro":"$.intro",
        "wordCount":"$.all_words","kind":"{{$.category_name}},{{$.status}}"
      }
    }"#).unwrap();
    let books = crate::api::search_with(&src, "斗破苍穹", 1, &JsonMockFetcher).unwrap();
    assert_eq!(books.len(), 3, "JSON API 源应解析出 3 本，实际 {:?}", books);
    assert_eq!(books[0].name, "斗破苍穹");
    assert!(books[0].book_url.contains("/novels/api/book/14228"), "book_url 应拼出真实 id，实际 {}", books[0].book_url);
    assert_eq!(books[0].author, "天蚕土豆");
}

fn ctx<'a>(base: &'a str, f: &'a DummyFetcher) -> RuleCtx<'a> {
    RuleCtx {
        base_url: base,
        header: "",
        book_url: "",
        book_name: "",
        chapter_url: "",
        fetcher: f,
    }
}

const SEARCH_HTML: &str = r#"
<html><body>
<div class="result-list">
  <div class="item">
    <a class="title" href="/book/1">斗破苍穹</a>
    <span class="author">天蚕土豆</span>
    <span class="kind">玄幻</span>
  </div>
  <div class="item">
    <a class="title" href="/book/2">武动乾坤</a>
    <span class="author">天蚕土豆</span>
  </div>
</div>
</body></html>
"#;

#[test]
fn css_search_list() {
    let f = DummyFetcher;
    let c = ctx("https://x.com", &f);
    // 列表选择器：CSS
    let items = get_list("@css:.result-list .item", SEARCH_HTML, &c);
    assert_eq!(items.len(), 2, "应选到2条");

    // 对每条单独再套子规则
    let name = get_string("@css:.title@text", &items[0], &c);
    assert_eq!(name, "斗破苍穹");
    let href = get_string("@css:a.title@href", &items[0], &c);
    assert_eq!(href, "https://x.com/book/1", "相对URL应转绝对");
}

#[test]
fn legado_default_syntax() {
    let f = DummyFetcher;
    let c = ctx("https://x.com", &f);
    // legado 默认语法 class.x@tag.a@text
    let name = get_string("class.title@text", SEARCH_HTML, &c);
    assert_eq!(name, "斗破苍穹");
}

#[test]
fn jsonpath_extract() {
    let f = DummyFetcher;
    let c = ctx("https://x.com", &f);
    let json = r#"{"data":{"list":[{"name":"书A","url":"/a"},{"name":"书B","url":"/b"}]}}"#;
    let names = get_list("@json:$.data.list", json, &c);
    assert_eq!(names.len(), 2);
    let first_name = get_string("@json:$.data.list[0].name", json, &c);
    assert_eq!(first_name, "书A");
}

#[test]
fn content_with_images() {
    let f = DummyFetcher;
    let c = ctx("https://img.com/", &f);
    let html = r#"<div id="content">
        <p>第一段文字</p>
        <p>第二段<img src="/pic/1.jpg"/>中间文字</p>
        <br/>
        <p>第三段</p>
    </div>"#;
    let (text, images) = crate::content::extract_content("@css:#content", html, &c);
    assert_eq!(images.len(), 1, "应抓到1张图");
    assert_eq!(images[0], "https://img.com/pic/1.jpg");
    assert!(text.contains("第一段文字"));
    assert!(text.contains("第二段"));
    assert!(text.contains('\u{FFFC}'), "图片应有占位符");
    assert!(text.contains("第三段"));
    // 段落之间应有换行
    assert!(text.contains("第一段文字\n"), "段落间应有换行: got={:?}", text);
}

#[test]
fn url_template() {
    // {{key}} + 算术 {{(page-1)*20}}
    let u = build_url("https://x.com/search?q={{key}}&start={{ (page-1)*20 }}&p={{page}}", "斗破 苍穹", 2, "https://x.com", "https://x.com");
    assert!(u.contains("q=%E6%96%97%E7%A0%B4"), "关键词应urlencode: {u}");
    assert!(u.contains("start=20"), "算术(2-1)*20=20: {u}");
    assert!(u.contains("p=2"));
}

#[test]
fn url_js_segment_eval() {
    // <js> 段：脚本用 result 变量累积，避免整段被 URL 编码（棉花小说/存档书库类）
    let u = build_url(
        "https://x.com/search?q=<js>result=result+'abc'</js>",
        "斗破苍穹",
        1,
        "https://x.com",
        "https://x.com",
    );
    assert!(u.contains("q=abc"), "<js> 段应求值: {u}");

    // @js: 段吞掉后面全部（爱书库类）
    let u2 = build_url(
        "https://x.com/@js:result='list'+'/1'",
        "斗破苍穹",
        1,
        "https://x.com",
        "https://x.com",
    );
    assert!(u2.contains("list/1"), "@js 段应求值: {u2}");

    // {{JS}} 非算术表达式走 JS 求值（天天小说 source.getKey 类）
    let u3 = build_url(
        "https://x.com/s?q={{key+'_x'}}",
        "abc",
        1,
        "https://x.com",
        "https://x.com",
    );
    assert!(u3.contains("abc_x"), "{{JS}} 应求值: {u3}");
}

#[test]
fn arith() {
    assert_eq!(eval_arith("(page-1)*20", 3), Some(40));
    assert_eq!(eval_arith("page*2+5", 4), Some(13));
    assert_eq!(eval_arith("100", 9), Some(100));
}

#[test]
fn yueyou_real_page_extraction() {
    // 用 Windows 原生抓取的真实阅友搜索页验证规则链修复：
    //   - author 的 ##\s 净化生效
    //   - bookUrl 的 @onclick@js: 链式生效（IIFE return 修复）
    let path = r"C:\Users\Administrator\.dsh\im\srctest\yueyou_search.html";
    let html = match std::fs::read_to_string(path) {
        Ok(h) => h,
        Err(_) => return, // CI 无该 fixture 时跳过
    };
    let f = DummyFetcher;
    let c = ctx("https://sma.yueyouxs.com", &f);
    let items = get_list(".v-list-item", &html, &c);
    assert_eq!(items.len(), 5, "应取到 5 个真实条目");
    let name = get_string(".v-title@text", &items[0], &c);
    let author = get_string(".v-author@text##\\s", &items[0], &c);
    let book_url = get_string("@onclick@js:result.match(/\\('(.*?)', '', ''\\)/)[1]", &items[0], &c);
    assert_eq!(name, "灵破苍穹");
    assert_eq!(author, "心跳的瞬间", "##\\s 净化应去掉 &nbsp; 空白");
    assert_eq!(book_url, "/b/229093.html", "@onclick@js 链应抠出真实 URL");
}

#[test]
fn yueyou_parse_book_list_filters_and_sorts() {
    let path = r"C:\Users\Administrator\.dsh\im\srctest\yueyou_search.html";
    let html = match std::fs::read_to_string(path) {
        Ok(h) => h,
        Err(_) => return,
    };
    let js = r#"{
        "bookSourceName":"阅友小说",
        "bookSourceUrl":"https://sma.yueyouxs.com",
        "searchUrl":"/s/1.html?keyword={{key}}&page={{page}}",
        "ruleSearch":{
            "bookList":".v-list-item",
            "name":".v-title@text",
            "author":".v-author@text##\\s",
            "bookUrl":"@onclick@js:result.match(/\\('(.*?)', '', ''\\)/)[1]",
            "kind":".base-label@text",
            "wordCount":".v-words@text",
            "coverUrl":"img@src"
        },
        "ruleBookInfo":{"name":".face-info-title@text","tocUrl":".sumchapter@a@href"},
        "ruleToc":{"chapterList":".catalog_ls@li@a","chapterName":"@css:a@text","chapterUrl":"@css:a@href"},
        "ruleContent":{"content":".con@html"}
    }"#;
    let src = BookSource::from_json(js).expect("解析成功");
    let f = DummyFetcher;
    let c = ctx("https://sma.yueyouxs.com", &f);
    let books = crate::api::parse_book_list(&src, &html, &c, "斗破苍穹", true).unwrap();
    // 5 个真实条目都应有书名、作者、绝对化 bookUrl
    assert_eq!(books.len(), 5, "应保留 5 个有效条目");
    for b in &books {
        assert!(!b.name.is_empty(), "书名不能为空");
        assert!(!b.author.is_empty(), "作者不能为空");
        assert!(b.book_url.starts_with("https://"), "bookUrl 应绝对化: {}", b.book_url);
    }
    // 相关度排序：含"苍穹"的书应排在"破天"之前
    let names: Vec<&str> = books.iter().map(|b| b.name.as_str()).collect();
    let pos_cangqiong = names.iter().position(|n| n.contains("苍穹")).unwrap_or(99);
    let pos_potian = names.iter().position(|n| *n == "破天").unwrap_or(99);
    assert!(pos_cangqiong < pos_potian, "含'苍穹'的应排在'破天'之前: {:?}", names);
}

#[test]
fn relevance_scoring() {
    use crate::api::relevance;
    assert!(relevance("斗破苍穹", "斗破苍穹") > relevance("灵破苍穹", "斗破苍穹"));
    assert!(relevance("斗破苍穹", "斗破苍穹") > relevance("破天", "斗破苍穹"));
    assert!(relevance("斗破苍穹", "斗破苍穹") > relevance("神动苍穹", "斗破苍穹"));
    assert_eq!(relevance("完全不相关", "斗破苍穹"), 0);
}

#[test]
fn chained_css_table_tbody_tr() {
    // 独步小说真实页面结构：.table > tbody > tr，验证链式 CSS 选择器能取到 3 个 tr。
    let html = r#"<html><body>
    <table class="table table-condensed table-hover">
      <thead><tr><th>书名</th><th>作者</th></tr></thead>
      <tbody>
        <tr><td><a href="/book/p1125/" class="orange">《斗破之马气大陆（斗破苍穹之马气大陆）》</a></td><td>江南夜色下</td></tr>
        <tr><td><a href="/book/p6561/">《斗破苍穹前传之药老传奇》</a></td><td>天蚕土豆</td></tr>
        <tr><td><a href="/book/p14228/">《斗破苍穹》</a></td><td>天蚕土豆</td></tr>
      </tbody>
    </table></body></html>"#;
    let f = DummyFetcher;
    let c = ctx("https://www.dbxsd.com", &f);
    let items = get_list(".table@tbody@tr", html, &c);
    assert_eq!(items.len(), 3, "链式 CSS .table@tbody@tr 应取到 3 个 tr, got={:?}", items);
    let name = get_string("a@text", &items[2], &c);
    assert_eq!(name, "《斗破苍穹》", "第3个 tr 书名应解析出来, got={}", name);
}

#[test]
fn html5ever_fragment_tbody() {
    // 诊断用：确认 html5ever 对孤立 <tbody>/<tr> 片段的解析行为。
    use scraper::{Html, Selector};
    let full = r#"<html><body><table><tbody><tr><td>x</td></tr><tr><td>y</td></tr></tbody></table></body></html>"#;
    let d = Html::parse_document(full);
    let tr = Selector::parse("tr").unwrap();
    assert_eq!(d.select(&tr).count(), 2, "完整 document 应有 2 个 tr");
    // 取 tbody 的 outer_html 再单独 parse（模拟 run_steps 逐步 parse）
    let tbody_sel = Selector::parse("tbody").unwrap();
    let tbody_html = d.select(&tbody_sel).next().unwrap().html();
    let d2 = Html::parse_document(&tbody_html);
    let cnt = d2.select(&tr).count();
    println!("[frag] tbody_html={} reparse tr count={}", tbody_html, cnt);
}

#[test]
fn parse_source_json() {
    let js = r#"{
        "bookSourceName":"测试源",
        "bookSourceUrl":"https://src.com",
        "searchUrl":"https://src.com/s?q={{key}}",
        "ruleSearch":{"bookList":"@css:.item","name":"@css:.title@text","bookUrl":"@css:.title@href"},
        "ruleBookInfo":{"name":"@css:h1@text","tocUrl":"@css:#toc@href"},
        "ruleToc":{"chapterList":"@css:#list a","chapterName":"@css:a@text","chapterUrl":"@css:a@href"},
        "ruleContent":{"content":"@css:#content"}
    }"#;
    let src = BookSource::from_json(js).expect("解析成功");
    assert_eq!(src.name, "测试源");
    assert_eq!(src.s_book_list, "@css:.item");
    assert_eq!(src.c_content, "@css:#content");
    assert_eq!(src.t_chapter_url, "@css:a@href");
}

/// 复现 m13042 bug：og:novel meta 标签的书名/作者/封面解析出整串 <meta ...>，
/// 目录解析 0 章。三种规则变体都要取到 meta 的 content 属性而非 inner_html/整串。
#[test]
fn meta_content_attribute_extraction() {
    let f = DummyFetcher;
    let c = ctx("http://example.com/book/1", &f);
    let html = r##"<!DOCTYPE html><html><head>
<meta charset="utf-8">
<meta content="斗破苍穹" property="og:novel:book_name">
<meta content="天蚕土豆" property="og:novel:author">
<meta content="玄幻魔法" property="og:novel:category">
<meta content="https://img.example.com/cover.jpg" property="og:image">
<meta content="这里是属于斗气的世界" property="og:description">
</head><body>
<div class="border_chapter"><ul>
<li><a href="/1.html">第一章 斗之气</a></li>
<li><a href="/2.html">第二章 疑问</a></li>
</ul></div>
<ul class="list-charts">
<li><a href="/3.html">第三章</a></li>
</ul>
</body></html>"##;

    // 变体1：大写 @CSS: + 单引号（穿越小说🎃）
    let name1 = get_string("@CSS:meta[property='og:novel:book_name']@content", html, &c);
    assert_eq!(name1, "斗破苍穹", "大写@CSS:单引号应取meta content属性, got={}", name1);
    // 变体2：小写 @css: + 双引号（言情小说阁）
    let name2 = get_string("@css:meta[property=\"og:novel:book_name\"]@content", html, &c);
    assert_eq!(name2, "斗破苍穹", "小写@css:双引号应取meta content属性, got={}", name2);
    // 变体3：无CSS:前缀直接 [property=...]（第三源）
    let name3 = get_string("[property=\"og:novel:book_name\"]@content", html, &c);
    assert_eq!(name3, "斗破苍穹", "[property=]前缀应取meta content属性, got={}", name3);

    // 作者 / 封面 / 分类 / 简介
    assert_eq!(get_string("@CSS:meta[property='og:novel:author']@content", html, &c), "天蚕土豆");
    assert_eq!(get_string("@CSS:meta[property='og:image']@content", html, &c), "https://img.example.com/cover.jpg");
    assert_eq!(get_string("@CSS:meta[property='og:novel:category']@content", html, &c), "玄幻魔法");
    assert_eq!(get_string("@CSS:meta[property='og:description']@content", html, &c), "这里是属于斗气的世界");

    // 目录：两种变体都要解析出章节
    let toc1 = get_list("@CSS:.border_chapter li", html, &c);
    assert!(toc1.len() >= 2, "大写@CSS:目录应>=2章, got={} {:?}", toc1.len(), toc1);
    let toc2 = get_list("@css:ul.list-charts li", html, &c);
    assert!(toc2.len() >= 1, "小写@css:目录应>=1章, got={} {:?}", toc2.len(), toc2);
}

#[test]
fn diag_chuanyue_toc_rules() {
    // 复刻穿越小说🎃真实目录页结构
    let f = DummyFetcher;
    let c = ctx("http://www.kk169.la/html/246/246813/", &f);
    let html = r##"<html><body><div class="border_chapter"><ul>
<li class="chapter"><a href="http://www.kk169.la/html/246/246813/13051315.html" title="第一章" alt="第一章 斗之气">第一章 斗之气</a></li>
<li class="chapter"><a href="http://www.kk169.la/html/246/246813/13051316.html" title="第二章" alt="第二章 斗气大陆">第二章 斗气大陆</a></li>
<li class="chapter"><a href="http://www.kk169.la/html/246/246813/13051317.html" title="第三章" alt="第三章">第三章</a></li>
</ul></div></body></html>"##;

    // 1) 纯选择器（无 js 块）
    let items1 = get_list("li.chapter@a", html, &c);
    eprintln!("[diag] li.chapter@a count={} items={:?}", items1.len(), items1);
    // 2) 带 js 后处理块（真实规则 chapterList）
    let rule_list = "li.chapter@a\n<js>\nvar r=result;\nvar v=book.getVariable(\"custom\")\nvar b=[];\nif(v!=\"\"){ for(i=0;i<r.length-v;i++){ b.push(r[i]); } result=b; } result\n</js>";
    let items2 = get_list(rule_list, html, &c);
    eprintln!("[diag] with-js count={} items={:?}", items2.len(), items2);
    // 核心断言：<js> 后处理块执行失败时须优雅降级，保留已解析章节，不能清空成 0
    assert_eq!(items2.len(), items1.len(), "<js>后处理失败不应清空章节: with-js={} pure={}", items2.len(), items1.len());

    if let Some(first) = items1.first() {
        eprintln!("[diag] first item html={}", first);
        // 3) chapterName/chapterUrl 无@简写
        let name = get_string("text", first, &c);
        let url = get_string("href", first, &c);
        eprintln!("[diag] get_string(text)=[{}] get_string(href)=[{}]", name, url);
        // 4) 带@版本对照
        let name_at = get_string("@text", first, &c);
        let url_at = get_string("@href", first, &c);
        eprintln!("[diag] get_string(@text)=[{}] get_string(@href)=[{}]", name_at, url_at);
    }
}

/// m13448: 作者字段「作者:作者:本名」须循环剥离所有重复前缀（legado 原生只去一层）。
#[test]
fn format_author_strips_all_repeated_prefixes() {
    use crate::api::format_author;
    assert_eq!(format_author("作者:作者:天蚕土豆"), "天蚕土豆");
    assert_eq!(format_author("作者：作者:作者：天蚕土豆"), "天蚕土豆");
    assert_eq!(format_author("  作者 : 作 者 : 天蚕土豆  "), "天蚕土豆");
    assert_eq!(format_author("作者:天蚕土豆 著"), "天蚕土豆");
    assert_eq!(format_author("天蚕土豆"), "天蚕土豆");
    assert_eq!(format_author(""), "");
}

/// m13448: 书名粘连的「 作者...」/「 某某 著」须去掉。
#[test]
fn format_name_strips_author_tail() {
    use crate::api::format_name;
    assert_eq!(format_name("斗破苍穹 作者:天蚕土豆"), "斗破苍穹");
    assert_eq!(format_name("斗破苍穹 天蚕土豆 著"), "斗破苍穹");
    assert_eq!(format_name("斗破苍穹"), "斗破苍穹");
}

/// m16589: 发现页规则须从 ruleExplore 读，缺失字段逐字段回退 ruleSearch。
/// 37 源 ruleExplore.bookList != ruleSearch.bookList，之前用 search 规则解析发现页→0 本。
#[test]
fn explore_rule_fields_from_rule_explore_with_fallback() {
    let src = BookSource::from_json(r#"{
      "bookSourceName":"独步","bookSourceUrl":"http://x",
      "ruleSearch":{
        "bookList":".table@tbody@tr","name":"a.1@text",
        "author":"a.2@text","kind":".cat@text","coverUrl":"img@src"
      },
      "ruleExplore":{
        "bookList":".media","name":"h3@text"
      }
    }"#).unwrap();
    // 显式配置的字段取 ruleExplore
    assert_eq!(src.e_book_list, ".media", "bookList 应取 ruleExplore");
    assert_eq!(src.e_name, "h3@text", "name 应取 ruleExplore");
    // 缺失字段回退 ruleSearch
    assert_eq!(src.e_author, "a.2@text", "author 应回退 ruleSearch");
    assert_eq!(src.e_kind, ".cat@text", "kind 应回退 ruleSearch");
    assert_eq!(src.e_cover, "img@src", "coverUrl 应回退 ruleSearch");
    // 搜索规则本身不受影响
    assert_eq!(src.s_book_list, ".table@tbody@tr");
}

/// m16589: 无 ruleExplore 时，所有 e_* 字段应等于对应 s_*（全回退）。
#[test]
fn explore_rule_fields_fallback_all_when_no_rule_explore() {
    let src = BookSource::from_json(r#"{
      "bookSourceName":"x","bookSourceUrl":"http://x",
      "ruleSearch":{"bookList":".list","name":"h2@text","author":".au@text"}
    }"#).unwrap();
    assert_eq!(src.e_book_list, src.s_book_list);
    assert_eq!(src.e_name, src.s_name);
    assert_eq!(src.e_author, src.s_author);
}
