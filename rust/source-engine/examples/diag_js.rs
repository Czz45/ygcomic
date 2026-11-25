//! 诊断 boa_engine JS 求值 —— 复刻 js::eval 完整逻辑
use boa_engine::{Context, Source};

fn eval_replica(js: &str, raw: &str, base_url: &str) -> String {
    let mut context = Context::default();
    let prelude = format!(
        "var result = {};\n\
         var baseUrl = {};\n\
         var book = {{\"bookUrl\":{},\"name\":{}}};\n\
         var chapter = {{\"url\":{}}};\n\
         var java = {{ ajax: function(u){{ return ''; }}, log: function(){{}} }};\n",
        serde_json::to_string(raw).unwrap_or_else(|_| "\"\"".into()),
        serde_json::to_string(base_url).unwrap_or_else(|_| "\"\"".into()),
        serde_json::to_string("").unwrap_or_else(|_| "\"\"".into()),
        serde_json::to_string("").unwrap_or_else(|_| "\"\"".into()),
        serde_json::to_string("").unwrap_or_else(|_| "\"\"".into()),
    );
    if let Err(e) = context.eval(Source::from_bytes(prelude.as_bytes())) {
        println!("PRELUDE ERR: {:?}", e);
        return String::new();
    }
    // 测试 prelude 是否把 result 设进去
    let chk = context.eval(Source::from_bytes(b"result"));
    println!("  [prelude result] = {:?}", chk.ok().and_then(|v| v.to_string(&mut context).ok()).map(|s| s.to_std_string_escaped()));
    let wrapped = format!("(function(){{ {} }})()", js);
    println!("  [wrapped] = {}", wrapped);
    match context.eval(Source::from_bytes(wrapped.as_bytes())) {
        Ok(val) => match val.to_string(&mut context) {
            Ok(s) => {
                let out = s.to_std_string_escaped();
                if out == "undefined" || out == "null" { String::new() } else { out }
            }
            Err(e) => { println!("TOSTRING ERR: {:?}", e); String::new() }
        },
        Err(e) => { println!("EVAL ERR for {:?}: {:?}", js, e); String::new() }
    }
}

fn main() {
    let raw = "newWebView('/b/229093.html', '', '')";
    for code in ["'hello'", "1+1", "result", "result.match(/\\('(.*?)', '', ''\\)/)[1]"] {
        println!("{:?} => {:?}", code, eval_replica(code, raw, "https://sma.yueyouxs.com"));
    }
}
