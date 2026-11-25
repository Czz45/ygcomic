//! 网络层 —— 可插拔 HttpFetcher。默认用 ureq 阻塞实现。

use encoding_rs::{GBK, UTF_8};

/// 单个响应最大读取字节数（8MB）。防止恶意/异常书源返回超大响应撑爆 native 内存，
/// 这类 OOM 在 Android JNI 层不可 catch，表现为"检测跑到一半闪退"。超限直接当失败处理。
const MAX_RESPONSE_BYTES: u64 = 8 * 1024 * 1024;

/// 带上限地读取响应体，返回 None 表示超限（调用方当网络错误处理）。
fn read_limited<R: std::io::Read>(reader: R) -> std::io::Result<Option<Vec<u8>>> {
    use std::io::Read;
    let mut buf = Vec::new();
    let n = reader.take(MAX_RESPONSE_BYTES + 1).read_to_end(&mut buf)?;
    if n as u64 > MAX_RESPONSE_BYTES {
        Ok(None)
    } else {
        Ok(Some(buf))
    }
}

/// 抽象网络接口，便于测试 mock 与将来换实现
pub trait Fetcher {
    /// GET 一个 URL，返回解码后的文本。charset 为空时自动探测。
    fn get(&self, url: &str, header_json: &str, charset: &str) -> Result<String, String>;

    /// 同 get，但额外返回重定向后的最终 URL（legado 用 res.url 作封面相对路径的 base）。
    /// 默认退化为 (get 结果, 原 url)，保证只实现 get 的 mock 不受影响。
    fn get_with_base(&self, url: &str, header_json: &str, charset: &str) -> Result<(String, String), String> {
        let text = self.get(url, header_json, charset)?;
        Ok((text, url.to_string()))
    }

    /// 按 method 发起请求（GET/POST）。默认退化为 GET，
    /// 保证只实现了 get 的 mock / rule.rs 里的 @js fetchText 不受影响。
    fn request(
        &self,
        url: &str,
        header_json: &str,
        method: &str,
        body: &str,
        charset: &str,
    ) -> Result<String, String> {
        let _ = (method, body);
        self.get(url, header_json, charset)
    }
}

pub struct UreqFetcher {
    pub timeout_secs: u64,
    /// 仅用于开发机原生测试：显式代理地址（如 127.0.0.1:17891）。
    /// 生产（Android JNI）从不设置，保持直连，零影响。
    pub proxy: Option<String>,
}

impl Default for UreqFetcher {
    fn default() -> Self {
        // 电脑上的书源探针走本机代理：api::search/detail/toc 内部用的是 default fetcher，
        // 之前它们不认 YG_PROXY，外网源在 PC 上必 DNS/连接失败。Android 端没有这个环境变量，不受影响。
        let proxy = std::env::var("YG_PROXY").ok().filter(|p| !p.trim().is_empty());
        Self { timeout_secs: 20, proxy }
    }
}

impl UreqFetcher {
    /// 构造一个走指定 HTTP 代理的 fetcher，仅用于 Windows 原生逐源测试。
    pub fn with_proxy(proxy: &str) -> Self {
        Self { timeout_secs: 20, proxy: Some(proxy.to_string()) }
    }

    /// 构造带超时/代理/UA 的 ureq Agent（GET/POST 共用）
    fn agent(&self) -> ureq::Agent {
        let mut builder = ureq::AgentBuilder::new()
            .timeout(std::time::Duration::from_secs(self.timeout_secs))
            // 只信任代码里显式配置的 header，忽略环境变量代理（避免 HTTP_PROXY 把本地/私有地址也劫持）
            .try_proxy_from_env(false)
            .user_agent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36");
        if let Some(p) = &self.proxy {
            let proxy_url = if p.contains("://") { p.clone() } else { format!("http://{}", p) };
            if let Ok(px) = ureq::Proxy::new(&proxy_url) {
                builder = builder.proxy(px);
            }
        }
        builder.build()
    }
}

/// 把 header JSON 的键值塞进请求（ureq 的 get/post 都返回同一个 Request 类型）
fn set_headers(mut req: ureq::Request, header_json: &str) -> ureq::Request {
    // P0⑤: 默认浏览器首部（过 Cloudflare/WAF 的 Accept 校验，对齐 legado/hadc188）。
    // 先设默认，再让书源 header_json 覆盖同名头——ureq 的 header 大小写不敏感，set 会替换。
    req = req
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .set("Upgrade-Insecure-Requests", "1");
    if !header_json.trim().is_empty() {
        if let Ok(serde_json::Value::Object(m)) = serde_json::from_str(header_json) {
            for (k, v) in m {
                if let Some(s) = v.as_str() {
                    req = req.set(&k, s);
                }
            }
        }
    }
    req
}

/// 读取响应字节并按 charset 解码：优先显式 charset，再探测 GBK，UTF-8 失败回退 GBK
fn decode_bytes(buf: &[u8], charset: &str) -> String {
    let cs = charset.to_lowercase();
    if cs.contains("gbk") || cs.contains("gb2312") || cs.contains("gb18030") {
        let (cow, _, _) = GBK.decode(buf);
        cow.into_owned()
    } else if detect_gbk(buf) {
        let (cow, _, _) = GBK.decode(buf);
        cow.into_owned()
    } else {
        match UTF_8.decode(buf) {
            (cow, _, had_errors) => {
                if had_errors {
                    let (g, _, _) = GBK.decode(buf);
                    g.into_owned()
                } else {
                    cow.into_owned()
                }
            }
        }
    }
}

impl Fetcher for UreqFetcher {
    fn get(&self, url: &str, header_json: &str, charset: &str) -> Result<String, String> {
        self.get_with_base(url, header_json, charset).map(|(t, _)| t)
    }

    fn get_with_base(&self, url: &str, header_json: &str, charset: &str) -> Result<(String, String), String> {
        let agent = self.agent();
        let req = set_headers(agent.get(url), header_json);
        let resp = req.call().map_err(|e| format!("http error: {e}"))?;
        let final_url = resp.get_url().to_string();
        let buf = read_limited(resp.into_reader())
            .map_err(|e| format!("read error: {e}"))?
            .ok_or_else(|| format!("response too large (>{} bytes)", MAX_RESPONSE_BYTES))?;
        Ok((decode_bytes(&buf, charset), final_url))
    }

    fn request(
        &self,
        url: &str,
        header_json: &str,
        method: &str,
        body: &str,
        charset: &str,
    ) -> Result<String, String> {
        let agent = self.agent();
        let resp = if method.trim().eq_ignore_ascii_case("post") {
            let req = set_headers(agent.post(url), header_json);
            req.send_string(body).map_err(|e| format!("http error: {e}"))?
        } else {
            let req = set_headers(agent.get(url), header_json);
            req.call().map_err(|e| format!("http error: {e}"))?
        };
        let buf = read_limited(resp.into_reader())
            .map_err(|e| format!("read error: {e}"))?
            .ok_or_else(|| format!("response too large (>{} bytes)", MAX_RESPONSE_BYTES))?;
        Ok(decode_bytes(&buf, charset))
    }
}

/// 简单 GBK 探测：UTF-8 校验失败且含高位字节则判 GBK
fn detect_gbk(buf: &[u8]) -> bool {
    std::str::from_utf8(buf).is_err()
}

/// 相对路径 → 绝对 URL
pub fn abs_url(base: &str, href: &str) -> String {
    let href = href.trim();
    if href.is_empty() {
        return String::new();
    }
    if href.starts_with("http://") || href.starts_with("https://") {
        return href.to_string();
    }
    if let Ok(b) = url::Url::parse(base) {
        if let Ok(u) = b.join(href) {
            return u.to_string();
        }
    }
    // base 本身可能不是完整 url
    if let Ok(u) = url::Url::parse(base) {
        return u.to_string();
    }
    href.to_string()
}

/// 从书源 URL 模板里拆出请求选项 `,{"method":"post","body":"...","charset":"..."}`。
/// 返回 (去掉选项的 URL 模板, method, body, charset)。
/// legado 书源里单引号/双引号 JSON 都出现过，这里统一把单引号转双引号再解析。
/// body 里的 {{key}} 用 urlencode、{{page}} 用页码替换（URL 部分仍交给 build_url 处理）。
pub fn split_request_options(template: &str, key: &str, page: i64) -> (String, String, String, String) {
    let mut url_part = template.to_string();
    let mut method = String::new();
    let mut body = String::new();
    let mut charset = String::new();
    if let Some(idx) = template.find(",{") {
        url_part = template[..idx].to_string();
        let opt_src = template[idx + 1..].replace('\'', "\"");
        if let Ok(serde_json::Value::Object(m)) = serde_json::from_str(&opt_src) {
            if let Some(s) = m.get("method").and_then(|v| v.as_str()) {
                method = s.trim().to_lowercase();
            }
            if let Some(s) = m.get("charset").and_then(|v| v.as_str()) {
                charset = s.to_string();
            }
            if let Some(s) = m.get("body").and_then(|v| v.as_str()) {
                // body 里的 {{key}} 必须按源 charset 编码：GBK 站用 GBK 字节 percent-encode
                body = s
                    .replace("{{key}}", &urlencode_for_charset(key, &charset))
                    .replace("{{page}}", &page.to_string());
            }
        }
    }
    (url_part, method, body, charset)
}

/// 拼接含分页模板的 URL：把 {{page}} / {{key}} 之类替换。
/// 支持 `{{(page-1)*20}}` 这类简单算术表达式。
pub fn build_url(
    template: &str,
    key: &str,
    page: i64,
    base: &str,
    source_key: &str,
) -> String {
    let mut out = template.to_string();
    // GET 型 GBK 站：{{key}} 也要按源 charset 编码（从 ,{...charset...} 提取）
    let charset = charset_from_template(template);
    out = out.replace("{{key}}", &urlencode_for_charset(key, &charset));
    out = out.replace("{{page}}", &page.to_string());

    // <js>...</js> / @js:... 段求值（抄 hadc188 eval_url_js_segments 语义）。
    // 大量书源的 searchUrl 由 JS 拼出；旧实现把整段当字面量 URL 编码，导致 403/404。
    let re_js = regex::Regex::new(r"(?is)<js>(.*?)</js>|@js:([\w\W]*)").unwrap();
    if re_js.is_match(&out) {
        let rule = out.clone();
        let mut result = rule.clone();
        let mut start = 0usize;
        for caps in re_js.captures_iter(&rule) {
            let m = match caps.get(0) {
                Some(x) => x,
                None => continue,
            };
            if m.start() > start {
                let prefix = rule[start..m.start()].trim();
                if !prefix.is_empty() {
                    result = prefix.replace("@result", &result);
                }
            }
            let script = caps
                .get(2)
                .or_else(|| caps.get(1))
                .map(|x| x.as_str())
                .unwrap_or_default();
            let evaluated = crate::js::eval_url(script, &result, key, page, source_key, base);
            if !evaluated.is_empty() {
                result = evaluated;
            }
            start = m.end();
            if caps.get(2).is_some() {
                // @js: 段吞掉后面全部
                break;
            }
        }
        if rule.len() > start {
            let suffix = rule[start..].trim();
            if !suffix.is_empty() {
                result = suffix.replace("@result", &result);
            }
        }
        out = result;
    }

    // {{表达式}}：先试简单算术，失败则当 JS 表达式求值（var/;/引号/java./source. 等）
    let re = regex::Regex::new(r"\{\{([^}]+)\}\}").unwrap();
    out = re
        .replace_all(&out, |caps: &regex::Captures| {
            let expr = caps[1].trim();
            if let Some(n) = eval_arith(expr, page) {
                return n.to_string();
            }
            crate::js::eval_url(expr, "", key, page, source_key, base)
        })
        .to_string();

    // <a,b> 分页（取第一个值）
    let re2 = regex::Regex::new(r"<([^,>]+),([^>]+)>").unwrap();
    out = re2.replace_all(&out, "$1").to_string();

    // ",{...}" 请求选项（method/body/charset/headers）由 split_request_options 单独解析，这里去掉
    if let Some(idx) = out.find(",{") {
        out = out[..idx].to_string();
    }

    abs_url(base, &out)
}

/// 极简算术求值：支持 page 变量与 + - * / ( )
pub fn eval_arith(expr: &str, page: i64) -> Option<i64> {
    let mut s = expr.replace("page", &page.to_string());
    s.retain(|c| !c.is_whitespace());
    if s.is_empty() {
        return None;
    }
    // 若纯数字直接返回
    if let Ok(n) = s.parse::<i64>() {
        return Some(n);
    }
    let mut p = ArithParser { chars: s.chars().collect(), pos: 0 };
    p.parse_expr()
}

struct ArithParser {
    chars: Vec<char>,
    pos: usize,
}

impl ArithParser {
    fn peek(&self) -> Option<char> {
        self.chars.get(self.pos).copied()
    }
    fn parse_expr(&mut self) -> Option<i64> {
        let mut v = self.parse_term()?;
        while let Some(c) = self.peek() {
            if c == '+' {
                self.pos += 1;
                v += self.parse_term()?;
            } else if c == '-' {
                self.pos += 1;
                v -= self.parse_term()?;
            } else {
                break;
            }
        }
        Some(v)
    }
    fn parse_term(&mut self) -> Option<i64> {
        let mut v = self.parse_factor()?;
        while let Some(c) = self.peek() {
            if c == '*' {
                self.pos += 1;
                v *= self.parse_factor()?;
            } else if c == '/' {
                self.pos += 1;
                let d = self.parse_factor()?;
                if d == 0 {
                    return None;
                }
                v /= d;
            } else {
                break;
            }
        }
        Some(v)
    }
    fn parse_factor(&mut self) -> Option<i64> {
        if self.peek() == Some('(') {
            self.pos += 1;
            let v = self.parse_expr()?;
            if self.peek() == Some(')') {
                self.pos += 1;
            }
            return Some(v);
        }
        let start = self.pos;
        while let Some(c) = self.peek() {
            if c.is_ascii_digit() {
                self.pos += 1;
            } else {
                break;
            }
        }
        if start == self.pos {
            return None;
        }
        self.chars[start..self.pos]
            .iter()
            .collect::<String>()
            .parse()
            .ok()
    }
}

pub fn urlencode(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

/// 按字符集做 form urlencode：GBK 系列源必须用 GBK 字节再 percent-encode，
/// 否则中文关键词按 UTF-8 编码后 GBK 站永远搜不到（hadc188/reader P0④）。
pub fn urlencode_for_charset(s: &str, charset: &str) -> String {
    let cs = charset.to_lowercase();
    if cs.contains("gbk") || cs.contains("gb2312") || cs.contains("gb18030") {
        let (cow, _, _) = GBK.encode(s);
        url::form_urlencoded::byte_serialize(&cow).collect()
    } else {
        urlencode(s)
    }
}

/// 从请求模板的 `,{...}` 选项片段里提取 charset（供 build_url 的 GET {{key}} 编码用）
fn charset_from_template(template: &str) -> String {
    if let Some(idx) = template.find(",{") {
        let opt_src = template[idx + 1..].replace('\'', "\"");
        if let Ok(serde_json::Value::Object(m)) = serde_json::from_str(&opt_src) {
            if let Some(s) = m.get("charset").and_then(|v| v.as_str()) {
                return s.to_string();
            }
        }
    }
    String::new()
}
