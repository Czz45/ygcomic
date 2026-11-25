//! 正文抽取 —— 从正文规则里取纯文本，并把 <img> 转成图片占位 + 收集图片 URL 列表。
//! 这是「小说内含图片」需求的落点：图片不进排版文本，仅用 \u{FFFC} 占位，
//! 排版层遇到占位符再按 images 索引取图渲染。

use crate::rule::RuleCtx;

/// 返回 (纯文本, 图片URL列表)。文本中图片位置以 \u{FFFC}（对象替换符）占位。
pub fn extract_content(rule: &str, html: &str, ctx: &RuleCtx) -> (String, Vec<String>) {
    // 纯图片规则（漫画源常见）：`amp-img@data-src` / `img@src` 直接取属性并在整页里选元素，
    // 不能走 @html（amp-img 没有子节点，会得到空串→0 图）。
    if let Some(images) = direct_image_rule(rule, html, ctx) {
        let placeholders = "\u{FFFC}".repeat(images.len());
        return (placeholders, images);
    }
    // 用规则选出正文元素 HTML（通常是 @css:...@html 或 @@xxx 默认 text）
    let rule_html = ensure_html_extract(rule);
    let raw = crate::rule::get_string(&rule_html, html, ctx);
    // 若规则本身取的就是纯文本（无标签），没有图片可言
    if !raw.contains('<') {
        return (raw, vec![]);
    }
    split_text_and_images(&raw, ctx)
}

/// 形如 `amp-img@data-src` / `img@src` 的纯图片规则：直接选元素、取属性。
fn direct_image_rule(rule: &str, html: &str, ctx: &RuleCtx) -> Option<Vec<String>> {
    let (sel, attr) = rule.trim().rsplit_once('@')?;
    let attr = attr.trim();
    if !matches!(attr, "src" | "data-src" | "data-original" | "data-lazy-src") {
        return None;
    }
    if !sel.contains("img") {
        return None;
    }
    // 站点可能把 amp-img 换成了普通 img（移动版/新版页面），两边都选。
    let sel_css = if sel.contains("amp-img") { format!("{}, img", sel.trim()) } else { sel.trim().to_string() };
    let selector = scraper::Selector::parse(&sel_css).ok()?;
    let doc = scraper::Html::parse_document(html);
    let mut out = Vec::new();
    for el in doc.select(&selector) {
        let v = el.value();
        // 规则写 data-src 但页面只给 src（或反过来）时依次回退，避免 0 图。
        let src = [attr, "data-src", "src", "data-original", "data-lazy-src"]
            .iter()
            .find_map(|a| v.attr(a).filter(|s| !s.is_empty()));
        if let Some(src) = src {
            out.push(crate::http::abs_url(ctx.base_url, src));
        }
    }
    Some(out)
}

/// 强制把正文规则变成取 inner_html，便于我们自己做图片处理。
fn ensure_html_extract(rule: &str) -> String {
    let r = rule.trim();
    if r.is_empty() {
        return String::new();
    }
    // 含 <js> / @js: 的规则：JS 段产出的是最终结果（漫画源常见「拼 <img> 列表」），
    // 不能再追加 @html 把它当选择器取 innerHTML（孤立 <img> 无 innerHTML → 丢图）。
    // 这类规则本身已显式写 @html（如 容器@html@js:...拼img），原样交给 get_string 执行即可。
    if r.contains("<js>") || r.contains("@js:") {
        return r.to_string();
    }
    // 已经是 @html / @content 结尾就别动
    if r.ends_with("@html") || r.ends_with("@content") {
        return r.to_string();
    }
    // CSS / @@ → 追加 @html
    if r.starts_with("@css:") || r.starts_with("@@") {
        return format!("{r}@html");
    }
    // legado 默认语法：把末尾 @text 去掉或追加 @html
    if let Some(pos) = r.rfind('@') {
        let tail = &r[pos + 1..];
        if tail == "text" || tail == "ownText" {
            return format!("{}@html", &r[..pos]);
        }
        if !tail.is_empty() && !tail.contains('.') {
            // 末步已是取值指令，替换成 html
            return format!("{}@html", &r[..pos]);
        }
    }
    format!("{r}@html")
}

/// 从一段 HTML 片段里抽出纯文本，img 转占位。
fn split_text_and_images(fragment: &str, ctx: &RuleCtx) -> (String, Vec<String>) {
    let doc = scaper_parse(fragment);
    let mut images = Vec::new();
    let mut out = String::new();
    walk(&doc.root_element(), &mut out, &mut images, ctx);
    (normalize(&out), images)
}

fn scaper_parse(frag: &str) -> scraper::Html {
    scraper::Html::parse_fragment(frag)
}

fn walk(
    el: &scraper::ElementRef,
    out: &mut String,
    images: &mut Vec<String>,
    ctx: &RuleCtx,
) {
    use scraper::Node;
    for child in el.children() {
        match child.value() {
            Node::Text(t) => out.push_str(t),
            Node::Element(e) => {
                let name = e.name();
                if name == "img" {
                    // 取 src（懒加载常见 data-src / data-original）
                    let src = e
                        .attr("src")
                        .or_else(|| e.attr("data-src"))
                        .or_else(|| e.attr("data-original"))
                        .or_else(|| e.attr("data-lazy-src"))
                        .unwrap_or("");
                    if !src.is_empty() {
                        images.push(crate::http::abs_url(ctx.base_url, src));
                        out.push('\u{FFFC}');
                    }
                    continue;
                }
                if name == "br" {
                    out.push('\n');
                    continue;
                }
                // 块级元素前后换行
                let block = matches!(
                    name,
                    "p" | "div" | "br" | "li" | "tr" | "h1" | "h2" | "h3" | "h4" | "h5" | "section" | "article"
                );
                if block && !out.ends_with('\n') && !out.is_empty() {
                    out.push('\n');
                }
                if let Some(er) = scraper::ElementRef::wrap(child) {
                    walk(&er, out, images, ctx);
                }
                if block && !out.ends_with('\n') {
                    out.push('\n');
                }
            }
            _ => {}
        }
    }
}

fn normalize(s: &str) -> String {
    // 每行 trim，保留段落换行，折叠过多空行
    let mut lines: Vec<String> = Vec::new();
    for line in s.split('\n') {
        let t = line.replace('\u{00a0}', " ");
        let t = t.trim();
        if t.is_empty() {
            if lines.last().map(|l: &String| l.is_empty()).unwrap_or(true) {
                continue;
            }
            lines.push(String::new());
        } else {
            lines.push(t.to_string());
        }
    }
    while lines.last().map(|l| l.is_empty()).unwrap_or(false) {
        lines.pop();
    }
    lines.join("\n")
}
