//! 只读批量 search 探针（开发机 Windows 原生）。不改引擎源码，只调 pub API。
//! 用法: yiove_batch_probe.exe <输入完整规则数组.json> <关键词逗号分隔> <并发数> <输出.jsonl>
//! 环境变量 YG_PROXY 指定代理，默认 127.0.0.1:17891。
//! 输出每行一个源: idx/name/url/type/status/kw/count/books/elapsed_ms/last_err/reqs
//! status: OK(命中>=1本) / HTTP_ERR / EMPTY_OR_PARSE_0 / NO_SEARCH_URL / SRC_PARSE_ERR
use source_engine::api::{search_with, BookSource};
use source_engine::http::{Fetcher, UreqFetcher};
use serde_json::{json, Value};
use std::env;
use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

#[derive(Clone)]
struct ReqLog {
    kind: &'static str,
    method: String,
    url: String,
    bytes: usize,
    http_ok: bool,
    err: String,
    html_head: String,
}

struct DiagFetcher {
    inner: UreqFetcher,
    diag: Mutex<Vec<ReqLog>>,
}

impl DiagFetcher {
    fn new(timeout: u64, proxy: &str) -> Self {
        Self {
            inner: UreqFetcher { timeout_secs: timeout, proxy: Some(proxy.to_string()) },
            diag: Mutex::new(Vec::new()),
        }
    }
    fn log(&self, r: ReqLog) {
        self.diag.lock().unwrap().push(r);
    }
    fn snapshot(&self) -> Vec<ReqLog> {
        self.diag.lock().unwrap().clone()
    }
    fn ok_log(kind: &'static str, method: String, url: String, html: &str) -> ReqLog {
        ReqLog {
            kind,
            method,
            url,
            bytes: html.len(),
            http_ok: true,
            err: String::new(),
            html_head: html.chars().take(500).collect(),
        }
    }
    fn err_log(kind: &'static str, method: String, url: String, e: &str) -> ReqLog {
        ReqLog {
            kind,
            method,
            url,
            bytes: 0,
            http_ok: false,
            err: e.to_string(),
            html_head: String::new(),
        }
    }
}

impl Fetcher for DiagFetcher {
    fn get(&self, url: &str, header_json: &str, charset: &str) -> Result<String, String> {
        match self.inner.get(url, header_json, charset) {
            Ok(html) => {
                self.log(Self::ok_log("get", "GET".into(), url.into(), &html));
                Ok(html)
            }
            Err(e) => {
                self.log(Self::err_log("get", "GET".into(), url.into(), &e));
                Err(e)
            }
        }
    }
    fn get_with_base(&self, url: &str, header_json: &str, charset: &str) -> Result<(String, String), String> {
        match self.inner.get_with_base(url, header_json, charset) {
            Ok((html, base)) => {
                self.log(Self::ok_log("get_with_base", "GET".into(), base.clone(), &html));
                Ok((html, base))
            }
            Err(e) => {
                self.log(Self::err_log("get_with_base", "GET".into(), url.into(), &e));
                Err(e)
            }
        }
    }
    fn request(&self, url: &str, header_json: &str, method: &str, body: &str, charset: &str) -> Result<String, String> {
        let m = if method.trim().eq_ignore_ascii_case("post") { "POST" } else { "GET" };
        match self.inner.request(url, header_json, method, body, charset) {
            Ok(html) => {
                self.log(Self::ok_log("request", m.into(), url.into(), &html));
                Ok(html)
            }
            Err(e) => {
                self.log(Self::err_log("request", m.into(), url.into(), &e));
                Err(e)
            }
        }
    }
}

fn process_one(
    line: &str,
    idx: usize,
    keywords: &[String],
    proxy: &str,
    timeout: u64,
) -> String {
    let val: Value = match serde_json::from_str(line) {
        Ok(v) => v,
        Err(e) => {
            return json!({"idx":idx,"status":"SRC_PARSE_ERR","err":format!("json: {e}"),"reqs":[]}).to_string();
        }
    };
    let name = val.get("bookSourceName").and_then(|x| x.as_str()).unwrap_or("").to_string();
    let url = val.get("bookSourceUrl").and_then(|x| x.as_str()).unwrap_or("").to_string();
    let stype = match val.get("bookSourceType") {
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::String(s)) => s.clone(),
        _ => "".to_string(),
    };
    let base = |status: &str, err: String| {
        json!({"idx":idx,"name":name,"url":url,"type":stype,"status":status,
               "kw":"","count":0,"books":[],"elapsed_ms":0,"last_err":err,"reqs":[]}).to_string()
    };
    let src = match BookSource::from_json(line) {
        Ok(s) => s,
        Err(e) => return base("SRC_PARSE_ERR", e),
    };
    if src.search_url.is_empty() {
        return base("NO_SEARCH_URL", "searchUrl empty".into());
    }

    let mut reqs_all: Vec<Value> = Vec::new();
    let mut best: Option<(String, Vec<source_engine::model::SearchBook>)> = None;
    let mut total_ms: u128 = 0;
    let mut last_err = String::new();
    let mut any_http_ok = false;

    for kw in keywords {
        let mut attempt = 0;
        loop {
            if attempt > 0 {
                thread::sleep(Duration::from_millis(800)); // 失败退避后重试1次
            }
            let fetcher = DiagFetcher::new(timeout, proxy);
            let t0 = Instant::now();
            let res = search_with(&src, kw, 1, &fetcher);
            total_ms += t0.elapsed().as_millis();
            let snap = fetcher.snapshot();
            for r in &snap {
                if r.http_ok {
                    any_http_ok = true;
                } else if !r.err.is_empty() {
                    last_err = r.err.clone();
                }
                reqs_all.push(json!({
                    "kw": kw, "attempt": attempt, "kind": r.kind, "method": r.method,
                    "url": r.url, "bytes": r.bytes, "http_ok": r.http_ok,
                    "err": r.err, "html_head": r.html_head
                }));
            }
            match res {
                Ok(books) if !books.is_empty() => {
                    best = Some((kw.clone(), books));
                    break;
                }
                Ok(_) => {
                    // HTTP 成功但解析 0 本：不重试该词，换下一个词
                    break;
                }
                Err(e) => {
                    last_err = e.clone();
                    let net = e.contains("http error") || e.contains("read error") || e.contains("too large");
                    if net && attempt == 0 {
                        attempt += 1;
                        continue; // 网络类失败重试1次
                    }
                    break;
                }
            }
        }
        if best.is_some() {
            break;
        }
        thread::sleep(Duration::from_millis(200)); // 关键词之间轻微限速
    }

    if let Some((kw, books)) = best {
        let count = books.len();
        let books_json: Vec<Value> = books
            .iter()
            .take(8)
            .map(|b| json!({"name": b.name, "url": b.book_url, "author": b.author}))
            .collect();
        json!({"idx":idx,"name":name,"url":url,"type":stype,"status":"OK",
               "kw":kw,"count":count,"books":books_json,"elapsed_ms":total_ms,
               "last_err":"","reqs":reqs_all}).to_string()
    } else {
        let status = if any_http_ok { "EMPTY_OR_PARSE_0" } else { "HTTP_ERR" };
        json!({"idx":idx,"name":name,"url":url,"type":stype,"status":status,
               "kw":"","count":0,"books":[],"elapsed_ms":total_ms,
               "last_err":last_err,"reqs":reqs_all}).to_string()
    }
}

fn main() {
    sys_stderr("yiove_batch_probe start");
    let args: Vec<String> = env::args().collect();
    let input = args.get(1).cloned().unwrap_or_else(|| "C:/Users/Administrator/.dsh/im/yiove_search_input.json".into());
    let keywords: Vec<String> = args
        .get(2)
        .cloned()
        .unwrap_or_else(|| "斗破苍穹,诡秘之主,遮天,斗罗大陆".into())
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    let workers: usize = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(5).clamp(1, 16);
    let out_path = args.get(4).cloned().unwrap_or_else(|| "C:/Users/Administrator/.dsh/im/yiove_search_result.jsonl".into());
    let proxy = env::var("YG_PROXY").unwrap_or_else(|_| "127.0.0.1:17891".into());
    let timeout: u64 = 6;

    sys_stderr(&format!("input={} keywords={:?} workers={} out={} proxy={}", input, keywords, workers, out_path, proxy));
    let data = fs::read_to_string(&input).unwrap_or_else(|e| panic!("read input: {e}"));
    let arr: Vec<Value> = serde_json::from_str(&data).unwrap_or_else(|e| panic!("parse input json: {e}"));
    let items: Arc<Vec<(usize, String)>> = Arc::new(
        arr.into_iter()
            .enumerate()
            .map(|(i, v)| (i, v.to_string()))
            .collect(),
    );
    sys_stderr(&format!("loaded {} sources", items.len()));
    let counter = Arc::new(AtomicUsize::new(0));
    let results: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
    let total = items.len();

    let kws = Arc::new(keywords);
    std::thread::scope(|s| {
        for _w in 0..workers {
            let items = Arc::clone(&items);
            let counter = Arc::clone(&counter);
            let results = Arc::clone(&results);
            let kws = Arc::clone(&kws);
            let proxy = proxy.clone();
            s.spawn(move || loop {
                let i = counter.fetch_add(1, Ordering::Relaxed);
                if i >= total {
                    break;
                }
                let (idx, line) = &items[i];
                let out = process_one(line, *idx, &kws, &proxy, timeout);
                results.lock().unwrap().push(out);
                if i % 50 == 0 {
                    sys_stderr(&format!("progress {}/{}", i + 1, total));
                }
                thread::sleep(Duration::from_millis(150)); // 全局慢速
            });
        }
    });

    let mut all = results.lock().unwrap();
    all.sort_by_key(|ln| {
        serde_json::from_str::<Value>(ln)
            .ok()
            .and_then(|v| v.get("idx").and_then(|x| x.as_u64()))
            .unwrap_or(usize::MAX as u64) as usize
    });
    let mut f = BufWriter::new(File::create(&out_path).unwrap_or_else(|e| panic!("create out: {e}")));
    for ln in all.iter() {
        writeln!(f, "{}", ln).unwrap();
    }
    f.flush().unwrap();
    sys_stderr(&format!("DONE wrote {} lines -> {}", all.len(), out_path));
}

fn sys_stderr(msg: &str) {
    eprintln!("{}", msg);
}
