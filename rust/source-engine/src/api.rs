//! 高层书源 API —— 把书源 JSON + 规则块 + 网络 + 规则引擎串成四步：
//!   search（搜索） / explore（发现） / detail（详情） / toc（目录） / content（正文）
//!
//! 书源 JSON 字段名对齐 legado。这里用一个轻量包装读取。

use crate::http::{build_url, split_request_options, Fetcher, UreqFetcher};
use crate::model::*;
use crate::rule::{get_list, get_string, RuleCtx};
use serde_json::Value;

/// 解析后的书源（只保留本引擎用到的字段）
#[derive(Debug, Clone)]
pub struct BookSource {
    pub name: String,
    pub url: String,
    pub header: String,
    pub search_url: String,
    pub explore_url: String,
    // —— search 规则 ——
    pub s_book_list: String,
    pub s_name: String,
    pub s_author: String,
    pub s_kind: String,
    pub s_word_count: String,
    pub s_last_chapter: String,
    pub s_intro: String,
    pub s_cover: String,
    pub s_book_url: String,
    // —— explore（发现）规则：空则回退到对应 s_*，对齐 legado 逐字段回退 ——
    pub e_book_list: String,
    pub e_name: String,
    pub e_author: String,
    pub e_kind: String,
    pub e_word_count: String,
    pub e_last_chapter: String,
    pub e_intro: String,
    pub e_cover: String,
    pub e_book_url: String,
    // —— detail 规则 ——
    pub d_name: String,
    pub d_author: String,
    pub d_kind: String,
    pub d_word_count: String,
    pub d_last_chapter: String,
    pub d_intro: String,
    pub d_cover: String,
    pub d_toc_url: String,
    // —— toc 规则 ——
    pub t_chapter_list: String,
    pub t_chapter_name: String,
    pub t_chapter_url: String,
    // —— content 规则 ——
    pub c_content: String,
    pub c_next_url: String,
    pub c_title: String,
}

impl BookSource {
    /// 从 legado 书源 JSON 文本解析
    pub fn from_json(json: &str) -> Result<Self, String> {
        let v: Value = serde_json::from_str(json).map_err(|e| format!("书源 JSON 解析失败: {e}"))?;
        let g = |path: &[&str]| -> String {
            let mut cur = &v;
            for k in path {
                cur = match cur.get(*k) {
                    Some(c) => c,
                    None => return String::new(),
                };
            }
            match cur {
                Value::String(s) => s.clone(),
                Value::Null => String::new(),
                other => other.to_string(),
            }
        };
        // 发现规则逐字段回退：ruleExplore.<field> 为空则用 ruleSearch.<field>（对齐 legado）
        let ge = |field: &str| -> String {
            let e = g(&["ruleExplore", field]);
            if e.trim().is_empty() { g(&["ruleSearch", field]) } else { e }
        };
        let header = match v.get("header") {
            Some(Value::String(s)) => s.clone(),
            Some(Value::Object(m)) => Value::Object(m.clone()).to_string(),
            _ => String::new(),
        };
        Ok(BookSource {
            name: g(&["bookSourceName"]),
            url: g(&["bookSourceUrl"]),
            header,
            search_url: g(&["searchUrl"]),
            explore_url: g(&["exploreUrl"]),
            s_book_list: g(&["ruleSearch", "bookList"]),
            s_name: g(&["ruleSearch", "name"]),
            s_author: g(&["ruleSearch", "author"]),
            s_kind: g(&["ruleSearch", "kind"]),
            s_word_count: g(&["ruleSearch", "wordCount"]),
            s_last_chapter: g(&["ruleSearch", "lastChapter"]),
            s_intro: g(&["ruleSearch", "intro"]),
            s_cover: g(&["ruleSearch", "coverUrl"]),
            s_book_url: g(&["ruleSearch", "bookUrl"]),
            e_book_list: ge("bookList"),
            e_name: ge("name"),
            e_author: ge("author"),
            e_kind: ge("kind"),
            e_word_count: ge("wordCount"),
            e_last_chapter: ge("lastChapter"),
            e_intro: ge("intro"),
            e_cover: ge("coverUrl"),
            e_book_url: ge("bookUrl"),
            d_name: g(&["ruleBookInfo", "name"]),
            d_author: g(&["ruleBookInfo", "author"]),
            d_kind: g(&["ruleBookInfo", "kind"]),
            d_word_count: g(&["ruleBookInfo", "wordCount"]),
            d_last_chapter: g(&["ruleBookInfo", "lastChapter"]),
            d_intro: g(&["ruleBookInfo", "intro"]),
            d_cover: g(&["ruleBookInfo", "coverUrl"]),
            d_toc_url: g(&["ruleBookInfo", "tocUrl"]),
            t_chapter_list: g(&["ruleToc", "chapterList"]),
            t_chapter_name: g(&["ruleToc", "chapterName"]),
            t_chapter_url: g(&["ruleToc", "chapterUrl"]),
            c_content: g(&["ruleContent", "content"]),
            c_next_url: g(&["ruleContent", "nextContentUrl"]),
            c_title: g(&["ruleContent", "title"]),
        })
    }
}

fn ctx<'a>(src: &'a BookSource, base: &'a str, fetcher: &'a dyn Fetcher) -> RuleCtx<'a> {
    RuleCtx {
        base_url: base,
        header: &src.header,
        book_url: "",
        book_name: "",
        chapter_url: "",
        fetcher,
    }
}

/// 搜索：返回该源下的书籍列表（生产入口，直连）
pub fn search(src: &BookSource, key: &str, page: i64) -> Result<Vec<SearchBook>, String> {
    let fetcher = UreqFetcher::default();
    search_with(src, key, page, &fetcher)
}

/// 搜索：可注入 fetcher（开发机原生测试走代理用）。生产不调用。
pub fn search_with<F: crate::http::Fetcher>(
    src: &BookSource,
    key: &str,
    page: i64,
    fetcher: &F,
) -> Result<Vec<SearchBook>, String> {
    if src.search_url.is_empty() {
        return Err("该源未配置 searchUrl".into());
    }
    let (tmpl, method, body, charset) = split_request_options(&src.search_url, key, page);
    let url = build_url(&tmpl, key, page, &src.url, &src.url);
    let html = fetch_with_options(fetcher, &url, &src.header, &method, &body, &charset)?;
    let base = url.clone();
    let c = ctx(src, &base, fetcher);
    parse_book_list(src, &html, &c, key, true)
}

/// 发现：kind_url 是某个分类的 URL，用 ruleExplore（回退 ruleSearch）解析该分类的书籍列表
pub fn explore(src: &BookSource, kind_url: &str, page: i64) -> Result<Vec<SearchBook>, String> {
    let fetcher = UreqFetcher::default();
    let (tmpl, method, body, charset) = split_request_options(kind_url, "", page);
    let url = build_url(&tmpl, "", page, &src.url, &src.url);
    let html = fetch_with_options(&fetcher, &url, &src.header, &method, &body, &charset)?;
    let base = url.clone();
    let c = ctx(src, &base, &fetcher);
    // 发现页规则与搜索页不同（37 源 ruleExplore.bookList != ruleSearch.bookList）。
    // 构造一个把 s_* 换成 e_* 的副本来复用 parse_book_list，避免改动其它入口。
    let mut esrc = src.clone();
    esrc.s_book_list = src.e_book_list.clone();
    esrc.s_name = src.e_name.clone();
    esrc.s_author = src.e_author.clone();
    esrc.s_kind = src.e_kind.clone();
    esrc.s_word_count = src.e_word_count.clone();
    esrc.s_last_chapter = src.e_last_chapter.clone();
    esrc.s_intro = src.e_intro.clone();
    esrc.s_cover = src.e_cover.clone();
    esrc.s_book_url = src.e_book_url.clone();
    parse_book_list(&esrc, &html, &c, "", false)
}

pub(crate) fn parse_book_list(
    src: &BookSource,
    html: &str,
    c: &RuleCtx,
    key: &str,
    is_search: bool,
) -> Result<Vec<SearchBook>, String> {
    let items = get_list(&src.s_book_list, html, c);
    let mut out: Vec<(i32, SearchBook)> = Vec::new();
    for item_html in items {
        let name = format_name(&first(&src.s_name, &item_html, c));
        let mut book_url = first(&src.s_book_url, &item_html, c);
        // 过滤 1：空书名 / JS 模板残留（如 `'+book.bookName+'`）
        let name_t = name.trim();
        if name_t.is_empty()
            || name_t.contains("'+")
            || name_t.contains("+'")
            || name_t.contains("book.bookName")
            || name_t.contains("{{")
        {
            continue;
        }
        // 过滤 2：bookUrl 为空的条目无法加入书架，直接丢弃
        book_url = book_url.trim().to_string();
        if book_url.is_empty() {
            continue;
        }
        // bookUrl 绝对化（相对路径 /b/xxx -> https://host/b/xxx）
        book_url = crate::http::abs_url(&src.url, &book_url);

        let score = if is_search { relevance(name_t, key) } else { 0 };
        out.push((score, SearchBook {
            name,
            author: format_author(&first(&src.s_author, &item_html, c)),
            kind: first(&src.s_kind, &item_html, c),
            word_count: first(&src.s_word_count, &item_html, c),
            last_chapter: first(&src.s_last_chapter, &item_html, c),
            intro: first(&src.s_intro, &item_html, c),
            cover_url: {
                let cv = first(&src.s_cover, &item_html, c);
                if cv.is_empty() { cv } else { crate::http::abs_url(&src.url, &cv) }
            },
            book_url,
            source_name: src.name.clone(),
            source_url: src.url.clone(),
        }));
    }
    // 搜索：按相关度降序（稳定排序），精确匹配排最前
    if is_search {
        out.sort_by(|a, b| b.0.cmp(&a.0));
    }
    Ok(out.into_iter().map(|(_, b)| b).collect())
}

/// 书名与搜索关键词的相关度评分（越高越匹配）。
/// 四档：完全相等 > 前缀含完整关键词 > 中间含完整关键词 > 只沾部分字。
/// 只沾部分字给小正分(1~99)，让它显示但排在三档之后；完全不沾字才判 0。
pub(crate) fn relevance(name: &str, key: &str) -> i32 {
    let n = name.trim().to_lowercase();
    let k = key.trim().to_lowercase();
    if k.is_empty() {
        return 0;
    }
    let klen = k.chars().count() as i32;
    let nlen = n.chars().count() as i32;
    let extra = (nlen - klen).max(0).min(9999); // 书名比关键词多出的字数，越少越靠前
    // 第1档：完全相等 —— 《斗破苍穹》，排第一
    if n == k {
        return 1_000_000;
    }
    // 第2档：前缀含完整关键词的衍生作品 —— 斗破苍穹前传 / 斗破苍穹之万界商城
    if n.starts_with(&k) {
        return 100_000 + (10_000 - extra);
    }
    // 第3档：中间含完整关键词的衍生作品 —— 修炼从斗破苍穹开始 / 斗破之马气大陆（斗破苍穹之马气大陆）
    if n.contains(&k) {
        return 50_000 + (10_000 - extra);
    }
    // 第4档：只沾部分字（灵破苍穹/神动苍穹/苍穹战神这类逐字重合但不连续）—— 命中字数占比 → 1~99
    let mut seen = std::collections::HashSet::new();
    let mut hit = 0i32;
    for ch in k.chars() {
        if seen.insert(ch) && n.contains(ch) {
            hit += 1;
        }
    }
    if hit == 0 {
        return 0;
    }
    let total = seen.len() as i32;
    (hit * 99 / total).clamp(1, 99)
}

/// 详情
/// 从详情页 HTML 探测指定 property 的 <meta> content（属性顺序/单双引号均可）。
/// 很多站点的目录是独立 URL，写在 og:novel:read_url 里，而书源规则的 tocUrl 为空。
pub fn detect_meta_content(html: &str, property: &str) -> Option<String> {
    let tag_re = regex::Regex::new(&format!(
        r#"(?is)<meta\b[^>]*\bproperty\s*=\s*["']{}["'][^>]*>"#,
        regex::escape(property)
    )).ok()?;
    let tag = tag_re.find(html)?.as_str().to_string();
    let content_re = regex::Regex::new(r#"(?is)\bcontent\s*=\s*["']([^"']*)["']"#).ok()?;
    content_re
        .captures(&tag)
        .and_then(|c| c.get(1))
        .map(|m| m.as_str().trim().to_string())
        .filter(|s| !s.is_empty())
}

pub fn detail(src: &BookSource, book_url: &str) -> Result<BookDetail, String> {
    let fetcher = UreqFetcher::default();
    // legado: 封面相对路径以重定向后的真实 URL(res.url)为 base，
    // 而不是原始请求 URL —— 很多站点详情页会跳转，用原 URL 拼出的封面 404。
    let (html, final_url) = fetcher.get_with_base(book_url, &src.header, "")?;
    let base = if final_url.is_empty() { book_url } else { &final_url };
    let c = ctx(src, base, &fetcher);
    Ok(BookDetail {
        name: format_name(&first(&src.d_name, &html, &c)),
        author: format_author(&first(&src.d_author, &html, &c)),
        kind: first(&src.d_kind, &html, &c),
        word_count: first(&src.d_word_count, &html, &c),
        last_chapter: first(&src.d_last_chapter, &html, &c),
        intro: first(&src.d_intro, &html, &c),
        cover_url: {
            let cv = first(&src.d_cover, &html, &c);
            if cv.is_empty() { cv } else { crate::http::abs_url(base, &cv) }
        },
        toc_url: {
            let t = first(&src.d_toc_url, &html, &c);
            if !t.is_empty() {
                t
            } else if let Some(read_url) = detect_meta_content(&html, "og:novel:read_url") {
                // 目录是独立 URL（写在 og:novel:read_url），书源 tocUrl 规则留空时的兜底
                let abs = crate::http::abs_url(base, &read_url);
                if abs.is_empty() { base.to_string() } else { abs }
            } else {
                base.to_string()
            }
        },
    })
}

/// 目录
pub fn toc(src: &BookSource, toc_url: &str) -> Result<Vec<Chapter>, String> {
    let fetcher = UreqFetcher::default();
    let html = fetcher.get(toc_url, &src.header, "")?;
    let c = ctx(src, toc_url, &fetcher);
    let items = get_list(&src.t_chapter_list, &html, &c);
    let mut out = Vec::new();
    for item_html in items {
        out.push(Chapter {
            title: first(&src.t_chapter_name, &item_html, &c),
            url: first(&src.t_chapter_url, &item_html, &c),
            is_vip: false,
            is_volume: false,
        });
    }
    Ok(out)
}

/// 正文 —— 支持 nextContentUrl 翻页拼接，图片转占位
pub fn content(src: &BookSource, chapter_url: &str) -> Result<Content, String> {
    let fetcher = UreqFetcher::default();
    let mut text = String::new();
    let mut images: Vec<String> = Vec::new();
    let mut title = String::new();
    let mut url = chapter_url.to_string();
    let mut guard = 0;
    while !url.is_empty() && guard < 20 {
        guard += 1;
        let html = match fetcher.get(&url, &src.header, "") {
            Ok(h) => h,
            Err(e) => {
                // 首页必须成功；后续翻页失败时保留已抓到的图片/文字，别整章报错。
                if guard == 1 { return Err(e); }
                break;
            }
        };
        let c = ctx(src, &url, &fetcher);
        if title.is_empty() {
            title = first(&src.c_title, &html, &c);
        }
        let (seg, mut imgs) = crate::content::extract_content(&src.c_content, &html, &c);
        text.push_str(&clean_text(&seg));
        images.append(&mut imgs);
        let next = first(&src.c_next_url, &html, &c);
        if next.is_empty() {
            break;
        }
        // next 规则常返回相对路径（/comic/chapter/...），必须基于当前 URL 绝对化。
        let next_abs = crate::http::abs_url(&url, &next);
        if next_abs.is_empty() || next_abs == url {
            break;
        }
        url = next_abs;
    }
    Ok(Content { title, text, images, next_url: String::new() })
}

fn clean_text(s: &str) -> String {
    // 折叠 3+ 连续空行，去首尾空白
    let re = regex::Regex::new(r"\n{3,}").unwrap();
    let s = re.replace_all(s, "\n\n");
    s.trim().to_string()
}

/// 作者字段清洗：循环剥离所有重复的「作者:」前缀。
/// legado 原生 authorRegex 只去一层；用户要求数据层把所有重复前缀去掉
/// （「作者:作者:天蚕土豆」→「天蚕土豆」），App UI 自己那一层「作者」标签保留。
pub(crate) fn format_author(s: &str) -> String {
    let mut out = s.trim().to_string();
    let prefix = regex::Regex::new(r"^\s*作\s*者\s*[:：]\s*").unwrap();
    loop {
        let next = prefix.replace(&out, "").to_string();
        if next == out { break; }
        out = next;
    }
    // 尾部「XX 著」的「著」
    let tail = regex::Regex::new(r"\s+著\s*$").unwrap();
    out = tail.replace(&out, "").to_string();
    out.trim().to_string()
}

/// 书名清洗：对齐 legado nameRegex —— 去掉书名里粘连的「 作者...」及尾部「 XX 著」。
pub(crate) fn format_name(s: &str) -> String {
    let mut out = s.trim().to_string();
    let re = regex::Regex::new(r"\s+作\s*者.*|\s+\S+\s+著\s*$").unwrap();
    out = re.replace(&out, "").to_string();
    out.trim().to_string()
}

fn first(rule: &str, raw: &str, c: &RuleCtx) -> String {
    if rule.is_empty() {
        return String::new();
    }
    get_string(rule, raw, c)
}

/// 带请求选项的抓取：method=post 时带 body 发送，charset 交给解码
fn fetch_with_options(f: &dyn Fetcher, url: &str, header: &str, method: &str, body: &str, charset: &str) -> Result<String, String> {
    f.request(url, header, method, body, charset)
}
