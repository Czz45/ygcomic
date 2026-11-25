//! 规则引擎核心 —— 把 legado 书源规则字符串解析成值。
//!
//! legado 规则语法（前缀分派）：
//!   - 无前缀           : legado jsoup 默认语法，如 `class.book-list@tag.li@text`
//!   - `@css:` / `@@`   : CSS 选择器
//!   - `@xpath:` / `//` : XPath 1.0
//!   - `@json:` / `$.`  : JSONPath（简化子集）
//!   - `@js:` / `<js>`  : JavaScript（Rhino）
//!   - `##正则##替换##` : 净化替换
//! 串接：`||` 取首个非空、`&&` 合并、`%%` 交错、`@@` 链式
//! 取值后缀：`@text` / `@html` / `@href` / `@src` / `@content` / `@attr.xxx`
//!
//! 本文件实现一个足够覆盖多数源的核心子集，并对未支持语法优雅降级。

use crate::http::Fetcher;
use scraper::{Html, Selector};

/// 裸 HTML5 片段补上下文：html5ever 按 document 模式解析时，孤立的
/// `<tr>/<td>/<li>` 会被丢掉（内部内容被提到 body 下），导致元素级
/// `select('tr')/select('td a')` 恒空。照 hadc188/reader html.rs 的
/// wrap_bare_fragment：按首标签给片段套上最小合法祖先再解析。
fn parse_html(raw: &str) -> Html {
    let t = raw.trim_start();
    // 跳过注释和 <!...> 声明，取第一个标签名
    let name = first_tag_name(t);
    let wrapped = match name.as_deref() {
        Some("tr") => Some(format!("<table><tbody>{}</tbody></table>", raw)),
        Some("td") | Some("th") => Some(format!("<table><tbody><tr>{}</tr></tbody></table>", raw)),
        Some("thead") | Some("tbody") | Some("tfoot") => Some(format!("<table>{}</table>", raw)),
        Some("col") => Some(format!("<table><colgroup>{}</colgroup></table>", raw)),
        Some("li") => Some(format!("<ul>{}</ul>", raw)),
        Some("dt") | Some("dd") => Some(format!("<dl>{}</dl>", raw)),
        Some("option") => Some(format!("<select>{}</select>", raw)),
        Some("legend") => Some(format!("<fieldset>{}</fieldset>", raw)),
        _ => None,
    };
    match wrapped {
        Some(w) => Html::parse_document(&w),
        None => Html::parse_document(raw),
    }
}

/// 取片段首个标签名（小写），跳过 HTML 注释与 `<!...>` 声明。
fn first_tag_name(s: &str) -> Option<String> {
    let b = s.as_bytes();
    let mut i = 0;
    while i < b.len() {
        if b[i] != b'<' { return None; }
        if i + 4 <= b.len() && &b[i..i + 4] == b"<!--" {
            match s[i + 4..].find("-->") {
                Some(off) => { i = i + 4 + off + 3; continue; }
                None => return None,
            }
        }
        if i + 2 <= b.len() && b[i + 1] == b'!' {
            match s[i + 2..].find('>') {
                Some(off) => { i = i + 2 + off + 1; continue; }
                None => return None,
            }
        }
        let start = i + 1;
        let mut j = start;
        while j < b.len() && (b[j].is_ascii_alphanumeric()) { j += 1; }
        if j == start { return None; }
        return Some(s[start..j].to_ascii_lowercase());
    }
    None
}

/// 解析上下文：当前页面 HTML/JSON 文本、base 地址、请求头、书与章节信息
pub struct RuleCtx<'a> {
    pub base_url: &'a str,
    pub header: &'a str,
    pub book_url: &'a str,
    pub book_name: &'a str,
    pub chapter_url: &'a str,
    pub fetcher: &'a dyn Fetcher,
}

impl<'a> RuleCtx<'a> {
    fn abs(&self, href: &str) -> String {
        crate::http::abs_url(self.base_url, href)
    }
}

/// 语法分派 —— 从一段 HTML/JSON 文本里，按规则取一个字符串。
///
/// 支持 legado 规则链：`css@text`、`@attr@js:...`、`##正则##替换##` 净化、
/// `class.x@tag.a@text` 默认语法。求值统一走 `eval_chain`。
/// 大小写不敏感地剥离 ASCII 前缀（@css:/@CSS: 等）。s.get(..n) 在非 char boundary 时返回 None，安全。
fn strip_prefix_ci<'a>(s: &'a str, prefix: &str) -> Option<&'a str> {
    let head = s.get(..prefix.len())?;
    if head.eq_ignore_ascii_case(prefix) {
        Some(&s[prefix.len()..])
    } else {
        None
    }
}

/// 判断一段规则文本是否还含 legado 规则语法（不含则可当字面值直接用）。
fn has_rule_syntax(s: &str) -> bool {
    let t = s.trim();
    t.starts_with("$.") || t.starts_with("$[") || t.starts_with("//")
        || t.contains("@js:") || t.contains("##") || t.contains("<js>")
        || t.contains("||") || t.contains("&&") || t.contains("%%")
        || strip_prefix_ci(t, "@css:").is_some() || t.starts_with("@@") || t.contains("@xpath:")
}

/// 字段级 {{expr}} 内插：JSON API 源（如酷我）里 bookUrl=/novels/api/book/{{$.book_id}}，
/// {{...}} 内的表达式要按「同一个 raw」（当前条目）求值后拼回字符串。
/// 返回 (替换后的规则, 是否为纯文本模板)。纯文本模板直接作字面值，不再走 tokenize，
/// 否则 /novels/api/book/14228 会被误当 CSS 选择器。
fn interpolate_fields(rule: &str, raw: &str, ctx: &RuleCtx) -> (String, bool) {
    if !rule.contains("{{") {
        return (rule.to_string(), false);
    }
    let mut out = String::new();
    let mut rest = rule;
    let mut consumed = false;
    while let Some(start) = rest.find("{{") {
        out.push_str(&rest[..start]);
        let after = &rest[start + 2..];
        if let Some(end) = after.find("}}") {
            let expr = &after[..end];
            let val = if expr.contains("{{") {
                let (expanded, _) = interpolate_fields(expr, raw, ctx);
                get_string(&expanded, raw, ctx)
            } else {
                get_string(expr.trim(), raw, ctx)
            };
            out.push_str(&val);
            rest = &after[end + 2..];
            consumed = true;
        } else {
            out.push_str("{{");
            rest = after;
        }
    }
    out.push_str(rest);
    let pure = consumed && !has_rule_syntax(&out);
    (out, pure)
}

pub fn get_string(rule: &str, raw: &str, ctx: &RuleCtx) -> String {
    if rule.trim().is_empty() {
        return String::new();
    }
    // 字段级 {{expr}} 内插（JSON API 源关键）
    let expanded;
    let rule = if rule.contains("{{") {
        let (s, pure) = interpolate_fields(rule, raw, ctx);
        if pure {
            return s;
        }
        expanded = s;
        expanded.as_str()
    } else {
        rule
    };
    let (body, cleans) = split_clean(rule.trim());
    // || / && / %% 是「段」级组合：每段独立求值，get_string 取第一个非空段。
    // 不能把各段拼成一条步骤链（旧实现会把 A||B 变成 A@B，导致回退规则永远取不到值）。
    for segment in split_combine(&body) {
        let steps = tokenize(&segment);
        if steps.is_empty() {
            continue;
        }
        let (mut values, ended_select) = run_steps(&steps, raw, ctx);
        // 纯选择器（无取值步骤）默认取文本
        if ended_select {
            values = values.iter().map(|v| value_from(v, "text", ctx)).collect();
        }
        let got = apply_clean(values, &cleans)
            .into_iter()
            .find(|s| !s.trim().is_empty());
        if let Some(v) = got {
            if !v.trim().is_empty() {
                return v;
            }
        }
    }
    String::new()
}

/// 取一个字符串列表（用于列表规则，如 bookList / chapterList）——返回每项的 outer_html。
pub fn get_list(rule: &str, raw: &str, ctx: &RuleCtx) -> Vec<String> {
    if rule.trim().is_empty() {
        return vec![];
    }
    let (body, _cleans) = split_clean(rule.trim());
    // 列表规则：各段分别求值后合并（|| 回退、&& 追加都能覆盖）。
    let mut out = Vec::new();
    for segment in split_combine(&body) {
        let steps = tokenize(&segment);
        if steps.is_empty() {
            continue;
        }
        let (values, _) = run_steps(&steps, raw, ctx);
        out.extend(values.into_iter().filter(|s| !s.is_empty()));
    }
    out
}

// ============ legado 规则链求值核心 ============

#[derive(Debug, Clone)]
enum Step {
    Select(String),        // CSS 选择器（已翻译 legado 默认语法）
    Value(String),         // text/html/content/ownText/href/src/attr.x/任意属性名
    Js(String),            // @js: 代码
    Json(String),          // JSONPath
    Xpath(String),         // XPath
    Index(String, usize, Option<usize>), // tag.N / tag.A:B 索引
}

/// 按分隔符切分，但保护括号/方括号/引号内部的字符（JS 代码里常有 || && @ 等）
fn split_top(s: &str, sep: char) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut depth = 0i32;
    let mut quote: Option<char> = None;
    // <js>…</js> 是不可分割脚本段：段内的 ||/&&/@ 是 JS 字符，段外才是规则分隔符。
    // 照 hadc188/reader rule_analyzer.rs 的 in_js 标志。
    let mut in_js = false;
    let chars: Vec<char> = s.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        let rest: String = chars[i..].iter().collect();
        if !in_js && rest.starts_with("<js>") {
            in_js = true;
            cur.push_str("<js>");
            i += 4;
            continue;
        }
        if in_js && rest.starts_with("</js>") {
            in_js = false;
            cur.push_str("</js>");
            i += 5;
            continue;
        }
        if in_js {
            cur.push(c);
            i += 1;
            continue;
        }
        if let Some(q) = quote {
            cur.push(c);
            if c == q { quote = None; }
            i += 1;
            continue;
        }
        match c {
            '\'' | '"' | '`' => { quote = Some(c); cur.push(c); }
            '(' | '[' | '{' => { depth += 1; cur.push(c); }
            ')' | ']' | '}' => { depth -= 1; cur.push(c); }
            _ if c == sep && depth == 0 => { out.push(cur.trim().to_string()); cur.clear(); }
            _ => cur.push(c),
        }
        i += 1;
    }
    out.push(cur.trim().to_string());
    out
}

/// 剥离 `##正则##替换##` 净化后缀（可多组），位于规则末尾。
fn split_clean(rule: &str) -> (String, Vec<(String, String)>) {
    let body: String;
    let mut cleans = Vec::new();
    if let Some(pos) = rule.find("##") {
        body = rule[..pos].to_string();
        let rest = &rule[pos + 2..];
        // legado / hadc188 语义（AnalyzeRule.kt:762-773）：## 之后只当两段看——
        // pattern=第1段，replacement=第2段；第3段存在表示 firstOnly（本结构不支持，忽略）。
        // 旧实现按 ## 循环每两段一组，会把第 2 组之后的模式错位，留下 " 12.5万 字" 这类残留。
        let mut seg = rest.splitn(3, "##");
        let re = seg.next().unwrap_or("").to_string();
        let rep = seg.next().unwrap_or("").to_string();
        let _first_only = seg.next().is_some();
        if !re.is_empty() {
            cleans.push((re, rep));
        }
    } else {
        body = rule.to_string();
    }
    (body, cleans)
}

fn apply_clean(values: Vec<String>, cleans: &[(String, String)]) -> Vec<String> {
    let mut values = values;
    for (re_s, rep) in cleans {
        if re_s.is_empty() { continue; }
        let re = regex::Regex::new(re_s)
            .unwrap_or_else(|_| regex::Regex::new(&regex::escape(re_s)).unwrap());
        values = values.iter().map(|v| re.replace_all(v, rep.as_str()).to_string()).collect();
    }
    values
}

/// 把规则主体拆成步骤序列。
fn tokenize(body: &str) -> Vec<Step> {
    let mut s: &str = body.trim();
    // legado 允许规则主体携带独立的 <js>...</js> 后处理块（通常在末尾），语义是
    // "对前面步骤解析出的结果集做 JS 加工"。必须先剥离并单独识别为 Step::Js，
    // 否则它会被按 @ 拆分后混进 CSS 选择器段，导致目录/字段解析结果被整体清空
    // （典型症状：书源规则 "li.chapter@a\n<js>...</js>" 返回 0 章）。
    let mut js_tail: Option<String> = None;
    // cleaned 必须在 if 外声明，让 s = cleaned.trim() 的借用活得足够久
    let mut cleaned = String::new();
    if let Some(pos) = s.find("<js>") {
        if let Some(rel_end) = s[pos..].find("</js>") {
            let code_start = pos + 4;          // skip "<js>"
            let tag_end = pos + rel_end + 5;   // include "</js>"
            js_tail = Some(s[code_start..pos + rel_end].to_string());
            cleaned.push_str(&s[..pos]);
            cleaned.push_str(&s[tag_end..]);
            s = cleaned.trim();
        }
    }
    let mut steps = tokenize_inner(s);
    if let Some(code) = js_tail {
        steps.push(Step::Js(code));
    }
    steps
}

/// 不含 <js> 后处理块的主体步骤拆分（原 tokenize 逻辑）。
fn tokenize_inner(s: &str) -> Vec<Step> {
    let mut s = s.trim();
    // 开头前缀（整段作为单一子规则）；@css: 大小写不敏感（书源里 @CSS: 大写很常见）
    if let Some(x) = strip_prefix_ci(s, "@css:") { s = x.trim(); }
    else if let Some(x) = s.strip_prefix("@@") { s = x.trim(); }
    if let Some(x) = s.strip_prefix("@json:") {
        return vec![Step::Json(x.trim().to_string())];
    }
    if s.starts_with("$.") || s.starts_with("$[") {
        return vec![Step::Json(s.to_string())];
    }
    if let Some(x) = s.strip_prefix("@xpath:") {
        return vec![Step::Xpath(x.trim().to_string())];
    }
    if s.starts_with("//") {
        return vec![Step::Xpath(s.to_string())];
    }
    if s.starts_with("<js>") && s.ends_with("</js>") {
        return vec![Step::Js(s[4..s.len() - 5].to_string())];
    }
    // 注意：|| / && / %% 由 get_string / get_list 在「段」级拆分，这里只处理单段。
    let mut steps = Vec::new();
    for part in split_top(s, '@') {
        if part.is_empty() { continue; }
        steps.push(classify_step(&part));
    }
    steps
}

/// 按顶层合成符 || && %% 拆分；JS 代码内的符号受 split_top 保护。
fn split_combine(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let chars: Vec<char> = s.chars().collect();
    let mut i = 0;
    let mut depth = 0i32;
    let mut quote: Option<char> = None;
    let mut in_js = false;
    while i < chars.len() {
        let c = chars[i];
        let rest: String = chars[i..].iter().collect();
        if !in_js && rest.starts_with("<js>") {
            in_js = true;
            cur.push_str("<js>");
            i += 4;
            continue;
        }
        if in_js && rest.starts_with("</js>") {
            in_js = false;
            cur.push_str("</js>");
            i += 5;
            continue;
        }
        if in_js {
            cur.push(c);
            i += 1;
            continue;
        }
        if let Some(q) = quote {
            cur.push(c);
            if c == q { quote = None; }
            i += 1;
            continue;
        }
        match c {
            '\'' | '"' | '`' => { quote = Some(c); cur.push(c); }
            '(' | '[' | '{' => { depth += 1; cur.push(c); }
            ')' | ']' | '}' => { depth -= 1; cur.push(c); }
            '&' | '|' | '%' if depth == 0 && i + 1 < chars.len() && chars[i + 1] == c => {
                if !cur.trim().is_empty() { out.push(cur.trim().to_string()); }
                cur.clear();
                i += 2;
                continue;
            }
            _ => cur.push(c),
        }
        i += 1;
    }
    if !cur.trim().is_empty() { out.push(cur.trim().to_string()); }
    if out.is_empty() { out.push(s.to_string()); }
    out
}

fn classify_step(s: &str) -> Step {
    let t = s.trim();
    if let Some(js) = t.strip_prefix("js:") {
        return Step::Js(js.to_string());
    }
    if t.starts_with("$.") || t.starts_with("$[") {
        return Step::Json(t.to_string());
    }
    if t.starts_with("//") {
        return Step::Xpath(t.to_string());
    }
    // 异次元索引 sel!N / sel!A:B（如 .pure-g!0 / li!3）
    // 注意：必须在 tag.N 之前判断，避免把 CSS 选择器里的点号误当索引。
    if let Some((sel, rest)) = t.rsplit_once('!') {
        if !sel.is_empty()
            && !rest.is_empty()
            && rest.chars().all(|c| c.is_ascii_digit() || c == ':')
        {
            let mut it = rest.split(':');
            let a: usize = it.next().unwrap().parse().unwrap_or(0);
            let b: Option<usize> = it.next().and_then(|x| x.parse().ok());
            return Step::Index(sel.to_string(), a, b);
        }
    }
    // 索引 tag.N / tag.A:B（如 span.0 / span.1:3）
    if let Some((tag, rest)) = t.split_once('.') {
        if !tag.is_empty()
            && tag.chars().all(|c| c.is_ascii_alphanumeric())
            && !rest.is_empty()
            && rest.chars().all(|c| c.is_ascii_digit() || c == ':')
        {
            let mut it = rest.split(':');
            let a: usize = it.next().unwrap().parse().unwrap_or(0);
            let b: Option<usize> = it.next().and_then(|x| x.parse().ok());
            return Step::Index(tag.to_string(), a, b);
        }
    }
    match t {
        "text" | "html" | "content" | "ownText" => return Step::Value(t.to_string()),
        _ => {}
    }
    if t.starts_with("attr.") {
        return Step::Value(t.to_string());
    }
    // 已知属性名（含事件属性、data-*）→ 取属性；否则按 CSS/legado 选择器
    if is_attr_name(t) && !looks_like_css(t) {
        return Step::Value(t.to_string());
    }
    Step::Select(legado_step_to_css(t))
}

fn is_attr_name(t: &str) -> bool {
    const ATTRS: &[&str] = &[
        "href", "src", "onclick", "onchange", "onload", "onerror", "class", "id",
        "title", "value", "alt", "action", "name", "type", "style", "width", "height",
        "onmouseover", "onfocus", "onmouseout",
    ];
    (ATTRS.contains(&t) && t != "a") || t.starts_with("data-") || (t.starts_with("on") && t.len() > 2)
}

fn looks_like_css(t: &str) -> bool {
    t.contains('.') || t.contains('#') || t.contains('[') || t.contains('>')
        || t.contains(':') || t.contains(' ') || t.contains('(')
}

/// 执行步骤链；返回 (当前值集合, 末步是否为选择器)。
fn run_steps(steps: &[Step], raw: &str, ctx: &RuleCtx) -> (Vec<String>, bool) {
    let mut values: Vec<String> = vec![raw.to_string()];
    let mut ended_select = false;
    let mut i = 0;
    while i < steps.len() {
        match &steps[i] {
            Step::Select(css) => {
                // legado @ 链语义：每一步都在上一步的结果集里再选一次，且**上一步元素
                // 自身也参与匹配**（hadc188/reader rule_engine 用 sel.matches(&el) 保证）。
                // 旧实现把连续 Select 合并成后代选择器 `.a .b`，会漏掉"上一步元素自身也
                // 匹配下一步"的情况；改成逐步筛选——每步把结果 outer_html 用 parse_html
                // 重新解析成完整 document，根片段元素成为 body 后代，sel.select 自然能
                // 命中自身。parse_html 同时给孤立 <tr>/<td>/<li> 补祖先，避免 html5ever
                // 按 document 模式丢弃无上下文标签导致链式规则中途产出 0。
                let (sel_css, ckw) = strip_contains(css);
                let sel = match Selector::parse(sel_css.trim()) {
                    Ok(s) => s,
                    Err(_) => return (vec![], false),
                };
                let mut next = Vec::new();
                for v in &values {
                    let doc = parse_html(v);
                    for el in doc.select(&sel) {
                        if let Some(kw) = &ckw {
                            if !el.text().collect::<String>().contains(kw) { continue; }
                        }
                        next.push(el.html());
                    }
                }
                values = next;
                ended_select = true;
                i += 1;
            }
            Step::Value(how) => {
                values = values.iter().map(|v| value_from(v, how, ctx)).collect();
                ended_select = false;
                i += 1;
            }
            Step::Js(code) => {
                // legado 的 <js> 后处理块常依赖 book.getVariable()/java.* 等宿主 API，
                // 沙箱里这些方法缺失会导致脚本抛异常、eval 返回空串。若此时直接覆盖，
                // 前面 Select 已解析出的章节/字段会被全部清空（典型症状：目录 0 章）。
                // 优雅降级：js 返回空而原值非空时保留原值，宁可多留几章也不要全部丢失。
                values = values.iter().map(|v| {
                    let r = crate::js::eval(code, v, ctx);
                    if r.trim().is_empty() && !v.trim().is_empty() { v.clone() } else { r }
                }).collect();
                ended_select = false;
                i += 1;
            }
            Step::Json(path) => {
                values = values.iter().flat_map(|v| json_list(path, v)).collect();
                ended_select = false;
                i += 1;
            }
            Step::Xpath(xp) => {
                values = values.iter().flat_map(|v| xpath_list(xp, v)).collect();
                ended_select = false;
                i += 1;
            }
            Step::Index(tag, a, b) => {
                let sel = Selector::parse(tag).ok();
                let mut next = Vec::new();
                for v in &values {
                    let doc = parse_html(v);
                    if let Some(sel) = &sel {
                        let els: Vec<_> = doc.select(sel).collect();
                        let end = b.unwrap_or(a + 1).min(els.len());
                        if *a < end {
                            for el in &els[*a..end] { next.push(el.html()); }
                        }
                    }
                }
                values = next;
                ended_select = true;
                i += 1;
            }
        }
    }
    (values, ended_select)
}

/// 从一段 HTML 或纯文本里按 how 取值。
fn value_from(v: &str, how: &str, ctx: &RuleCtx) -> String {
    let doc = parse_html(v);
    // 取第一个真实元素：<meta> 等被 html5ever 放进 head，body 里没有元素，
    // 故选择器要含 head 与 body 的直接子元素，否则孤立 <meta> 片段 first_el=None。
    let any = Selector::parse("head > *, body > *").unwrap();
    let first_el = doc.select(&any).next();
    match how {
        "text" | "ownText" => {
            if let Some(el) = first_el {
                el.text().collect::<String>().trim().to_string()
            } else {
                v.trim().to_string()
            }
        }
        "html" => {
            if let Some(el) = first_el { el.inner_html() } else { v.to_string() }
        }
        // legado 语义：@content 取元素的 content 属性（og:novel meta 的书名/作者/封面全靠它），
        // 不是 inner_html。孤立 <meta> 若 first_el 仍为空，退化用正则从原始串提取 content="..."。
        "content" => {
            if let Some(el) = first_el {
                if let Some(c) = el.value().attr("content") {
                    return c.to_string();
                }
            }
            if let Ok(re) = regex::Regex::new(r#"(?i)content\s*=\s*"([^"]*)""#) {
                if let Some(caps) = re.captures(v) {
                    return caps.get(1).map(|m| m.as_str().to_string()).unwrap_or_default();
                }
            }
            String::new()
        }
        "href" | "src" | "action" => {
            if let Some(el) = first_el {
                ctx.abs(el.value().attr(how).unwrap_or(""))
            } else {
                String::new()
            }
        }
        other => {
            let attr = other.strip_prefix("attr.").unwrap_or(other);
            if let Some(el) = first_el {
                el.value().attr(attr).unwrap_or("").to_string()
            } else {
                String::new()
            }
        }
    }
}

/// 提取并剥离 :contains-x(kw) / :contains(kw)，返回 (选择器, 关键词)。
fn strip_contains(css: &str) -> (String, Option<String>) {
    let re = regex::Regex::new(r":contains(?:-x)?\(([^)]*)\)").unwrap();
    let mut kw: Option<String> = None;
    let out = re.replace_all(css, |caps: &regex::Captures| {
        kw = Some(caps[1].trim_matches(|c| c == '\'' || c == '"').to_string());
        ""
    }).to_string();
    (out, kw)
}

/// 列表规则专用：选出元素并返回每个元素的 outer_html（含元素自身标签，用于后续对条目套子规则）。
/// 返回 outer_html 而非 inner_html：这样当规则直接命中 <a>/<li> 等元素、子规则又写 `@css:a@text`
/// （即对元素自身再选一次）时仍能匹配到自身标签，符合 legado 语义。
fn css_select_html(rule: &str, raw: &str) -> Vec<String> {
    let (mut css, _tail) = legado_to_css(rule);
    if css.is_empty() {
        css = rule.to_string();
    }
    let css = compile_contains(&css);
    let doc = parse_html(raw);
    let sel = match Selector::parse(&css) {
        Ok(s) => s,
        Err(_) => return vec![],
    };
    doc.select(&sel).map(|el| el.html()).collect()
}

// ---------- 前缀剥离 ----------

fn strip_js(rule: &str) -> Option<String> {
    if let Some(s) = rule.strip_prefix("@js:") {
        return Some(s.to_string());
    }
    let t = rule.trim();
    if t.starts_with("<js>") && t.ends_with("</js>") {
        return Some(t[4..t.len() - 5].to_string());
    }
    if t.starts_with("@js<") || t.starts_with("$:") {
        return None;
    }
    None
}

fn strip_json(rule: &str) -> Option<&str> {
    if let Some(s) = rule.strip_prefix("@json:") {
        return Some(s.trim());
    }
    if rule.starts_with("$.") || rule.starts_with("$[") {
        return Some(rule);
    }
    None
}

fn strip_xpath(rule: &str) -> Option<&str> {
    if let Some(s) = rule.strip_prefix("@xpath:") {
        return Some(s.trim());
    }
    if rule.starts_with("//") || rule.starts_with("@xpath") {
        return Some(rule);
    }
    None
}

fn strip_css(rule: &str) -> &str {
    if let Some(s) = strip_prefix_ci(rule, "@css:") {
        return s.trim();
    }
    if let Some(s) = rule.strip_prefix("@@") {
        return s.trim();
    }
    rule
}

// ---------- CSS（含 legado 默认语法降级）----------

/// legado 默认语法 `class.x@tag.a@text` → 转 CSS。简化翻译。
fn legado_to_css(rule: &str) -> (String, Vec<String>) {
    // 用 @ 分隔步骤，最后一步可能是取值 @text/@href 等
    let parts: Vec<&str> = rule.split('@').collect();
    let mut css = String::new();
    let mut tail: Vec<String> = Vec::new();
    for (i, p) in parts.iter().enumerate() {
        let p = p.trim();
        if p.is_empty() {
            continue;
        }
        let is_last = i == parts.len() - 1;
        if is_last && (p == "text" || p == "html" || p == "href" || p == "src" || p == "content" || p.starts_with("attr.") || p == "ownText") {
            tail.push(p.to_string());
            continue;
        }
        let sel = legado_step_to_css(p);
        if !sel.is_empty() {
            if !css.is_empty() {
                css.push(' ');
            }
            css.push_str(&sel);
        }
    }
    (css, tail)
}

fn legado_step_to_css(step: &str) -> String {
    // class.x / id.x / tag.x / text.x
    if let Some(x) = step.strip_prefix("class.") {
        return format!(".{}", x.replace('.', "."));
    }
    if let Some(x) = step.strip_prefix("id.") {
        return format!("#{}", x);
    }
    if let Some(x) = step.strip_prefix("tag.") {
        return x.to_string();
    }
    if let Some(x) = step.strip_prefix("text.") {
        return format!(":contains-x({})", x); // 由调用方特殊处理
    }
    // 已是 CSS 模样（含 . # [ : > 空格）就用原样
    if step.contains('.') || step.contains('#') || step.contains('[') || step.contains(':') || step.contains('>') {
        return step.to_string();
    }
    // 裸标签名
    step.to_string()
}

fn css_get_list_or_first(rule: &str, raw: &str, ctx: &RuleCtx, take_last: bool) -> Vec<String> {
    let mut vals = css_select_elements(rule, raw, ctx);
    if !vals.is_empty() && take_last {
        // get_string 只需要一个，取第一个非空
        let first = vals.drain(..).find(|s| !s.trim().is_empty());
        return first.into_iter().collect();
    }
    vals
}

/// 用 CSS 选出元素并取值（@text 默认）
fn css_select_elements(rule: &str, raw: &str, ctx: &RuleCtx) -> Vec<String> {
    // 统一走 legado_to_css：它会剥掉 @text/@href/@html/@attr.x 尾部取值指令，
    // 并把 class.x/@tag.a 等 legado 默认语法翻成 CSS。纯 CSS（含 . # [ 空格 >）原样保留。
    let (mut css, tail) = legado_to_css(rule);
    if css.is_empty() {
        css = rule.to_string();
    }
    // 处理 :contains-x 特殊语法
    let css = compile_contains(&css);

    let doc = parse_html(raw);
    let sel = match Selector::parse(&css) {
        Ok(s) => s,
        Err(_) => return vec![],
    };
    let extract = tail.first().map(|s| s.as_str()).unwrap_or("text");
    doc.select(&sel)
        .map(|el| extract_value(el, extract, ctx))
        .collect()
}

fn compile_contains(css: &str) -> String {
    // 把 :contains-x(kw) 转成 :contains(kw)（scraper 不支持 contains，需要手写：这里退化为去掉）
    // scraper 无 :contains，做尽力而为：用属性选择替代不可行，改为在 select 后过滤——简化先剥离
    let re = regex::Regex::new(r":contains-x\(([^)]*)\)").unwrap();
    re.replace_all(css, "").to_string()
}

fn extract_value(el: scraper::ElementRef, how: &str, ctx: &RuleCtx) -> String {
    match how {
        "text" => el.text().collect::<String>().trim().to_string(),
        "ownText" => el.text().collect::<String>().trim().to_string(),
        "html" => el.inner_html(),
        // @content = 取 content 属性（与 value_from 一致），并非 inner_html。
        "content" => el.value().attr("content").unwrap_or("").to_string(),
        "href" => ctx.abs(el.value().attr("href").unwrap_or("")),
        "src" => ctx.abs(el.value().attr("src").unwrap_or("")),
        other => {
            if let Some(attr) = other.strip_prefix("attr.") {
                el.value().attr(attr).unwrap_or("").to_string()
            } else {
                el.text().collect::<String>().trim().to_string()
            }
        }
    }
}

// ---------- XPath ----------

fn xpath_get(xp: &str, raw: &str) -> String {
    xpath_list(xp, raw).into_iter().next().unwrap_or_default()
}

fn xpath_list(xp: &str, raw: &str) -> Vec<String> {
    use sxd_xpath::{Context, Factory, Value};
    let doc = parse_html(raw);
    // 用 sxd 需要把 HTML 转 XML——这里简化：scraper 不支持 XPath，
    // 实际生产用 sxd_document 解析 XML；HTML 场景下退化为空。
    // 说明：XPath 规则在纯文本小说源里占比不高，先留接口，后续可接 html5ever→XML。
    let _ = (doc, xp);
    let pkg = match sxd_document::parser::parse(&to_xml_lenient(raw)) {
        Ok(p) => p,
        Err(_) => return vec![],
    };
    let document = pkg.as_document();
    let factory = Factory::new();
    let xpath = match factory.build(xp).ok().flatten() {
        Some(x) => x,
        None => return vec![],
    };
    let mut ctx = Context::new();
    ctx.set_namespace("", "http://www.w3.org/1999/xhtml");
    let value = match xpath.evaluate(&ctx, document.root()) {
        Ok(v) => v,
        Err(_) => return vec![],
    };
    match value {
        Value::Nodeset(ns) => ns
            .document_order()
            .iter()
            .map(|n| n.string_value())
            .collect(),
        Value::String(s) => vec![s],
        Value::Number(n) => vec![n.to_string()],
        Value::Boolean(b) => vec![b.to_string()],
    }
}

fn to_xml_lenient(raw: &str) -> String {
    // 粗暴自闭合修正太少，直接交给 sxd；失败即空
    raw.to_string()
}

// ---------- JSONPath（简化子集：$.a.b[0].c / $..key）----------

fn json_get(path: &str, raw: &str) -> String {
    json_list(path, raw).into_iter().next().unwrap_or_default()
}

fn json_list(path: &str, raw: &str) -> Vec<String> {
    let v: serde_json::Value = match serde_json::from_str(raw) {
        Ok(v) => v,
        Err(_) => return vec![],
    };
    let cur = json_walk(&v, path);
    match cur {
        Some(serde_json::Value::Array(arr)) => arr
            .iter()
            .map(|x| json_scalar(x))
            .collect(),
        Some(other) => vec![json_scalar(&other)],
        None => vec![],
    }
}

fn json_scalar(v: &serde_json::Value) -> String {
    match v {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Null => String::new(),
        other => other.to_string(),
    }
}

/// 支持 `$.a.b[0].c` 与 `$..key`（取所有同名 key）
fn json_walk<'a>(root: &'a serde_json::Value, path: &str) -> Option<serde_json::Value> {
    let path = path.trim();
    if path.starts_with("$..") {
        let key = &path[3..];
        let mut out = Vec::new();
        collect_key(root, key, &mut out);
        return Some(serde_json::Value::Array(out));
    }
    let mut cur = root.clone();
    let p = path.strip_prefix('$').unwrap_or(path);
    let mut rest = p;
    while !rest.is_empty() {
        if let Some(r) = rest.strip_prefix('.') {
            rest = r;
            let (key, after) = split_key(rest);
            cur = cur.get(key)?.clone();
            rest = after;
        } else if let Some(r) = rest.strip_prefix('[') {
            let end = r.find(']')?;
            let idx: usize = r[..end].trim().parse().ok()?;
            cur = cur.get(idx)?.clone();
            rest = &r[end + 1..];
        } else {
            break;
        }
    }
    Some(cur)
}

fn split_key(s: &str) -> (&str, &str) {
    if let Some(pos) = s.find(|c| c == '.' || c == '[') {
        (&s[..pos], &s[pos..])
    } else {
        (s, "")
    }
}

fn collect_key(v: &serde_json::Value, key: &str, out: &mut Vec<serde_json::Value>) {
    match v {
        serde_json::Value::Object(m) => {
            for (k, val) in m {
                if k == key {
                    out.push(val.clone());
                }
                collect_key(val, key, out);
            }
        }
        serde_json::Value::Array(a) => {
            for val in a {
                collect_key(val, key, out);
            }
        }
        _ => {}
    }
}
