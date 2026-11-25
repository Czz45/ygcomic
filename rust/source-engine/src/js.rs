//! JavaScript 规则求值 —— 基于 boa_engine（纯 Rust JS 引擎，可交叉编译到 Android）。
//! 提供最小可用运行时：
//!   - 变量 result（当前页 HTML/JSON 文本）、baseUrl、book、chapter
//!   - 全局 java.ajax(url) 暂时返回空（沙箱内不发起额外网络）
//! 求值结果转字符串返回。

use crate::rule::RuleCtx;
use boa_engine::{Context, Source};

pub fn eval(js: &str, raw: &str, ctx: &RuleCtx) -> String {
    let mut context = Context::default();

    let prelude = format!(
        "var result = {};\n\
         var baseUrl = {};\n\
         var book = {{\"bookUrl\":{},\"name\":{},\
            getVariable: function(k){{ return ''; }},\
            putVariable: function(k,v){{ }}}};\n\
         var chapter = {{\"url\":{}}};\n\
         var java = {{ ajax: function(u){{ return ''; }}, log: function(){{}},\
            getVariable: function(k){{ return ''; }}, putVariable: function(k,v){{ }} }};\n",
        serde_json::to_string(raw).unwrap_or_else(|_| "\"\"".into()),
        serde_json::to_string(ctx.base_url).unwrap_or_else(|_| "\"\"".into()),
        serde_json::to_string(ctx.book_url).unwrap_or_else(|_| "\"\"".into()),
        serde_json::to_string(ctx.book_name).unwrap_or_else(|_| "\"\"".into()),
        serde_json::to_string(ctx.chapter_url).unwrap_or_else(|_| "\"\"".into()),
    );
    if context.eval(Source::from_bytes(prelude.as_bytes())).is_err() {
        return String::new();
    }

    // 用 IIFE 包裹，把表达式值 return 出来。
    // legado 的 @js: 规则通常是一个表达式（如 `result.match(...)[1]`）或多条语句：
    //   - 先尝试 `return (expr)`：覆盖绝大多数单表达式规则；
    //   - 失败则退回原语句块（带显式 return / 赋值 result 的复杂规则）。
    let candidates = [
        format!("(function(){{ return ({}) }})()", js),
        format!("(function(){{ {} }})()", js),
    ];
    for wrapped in &candidates {
        match context.eval(Source::from_bytes(wrapped.as_bytes())) {
            Ok(val) => {
                if let Ok(s) = val.to_string(&mut context) {
                    let out = s.to_std_string_escaped();
                    if out != "undefined" && out != "null" && !out.is_empty() {
                        return out;
                    }
                }
            }
            Err(_) => continue,
        }
    }
    String::new()
}

/// URL 规则专用求值：给 searchUrl / exploreUrl 里的 <js>/@js:/{{...}} 段求值。
/// 不依赖 RuleCtx（build_url 阶段还没有 RuleCtx），只注入 URL 折叠所需的最小环境。
/// 抄 hadc188/reader 的 eval_js_url 语义，但用 boa_engine 实现（不移植其 rquickjs 运行时）。
pub fn eval_url(
    script: &str,
    result: &str,
    key: &str,
    page: i64,
    source_key: &str,
    base_url: &str,
) -> String {
    let mut context = Context::default();
    let j = |s: &str| serde_json::to_string(s).unwrap_or_else(|_| "\"\"".into());
    let prelude = format!(
        "var result = {};\n\
         var key = {};\n\
         var page = {};\n\
         var baseUrl = {};\n\
         var sourceUrl = {};\n\
         var source = {{\"key\":{},\"getKey\": function(){{ return {}; }},\
            \"getString\": function(k){{ return ''; }},\
            \"put\": function(k,v){{ }},\
            \"getVariable\": function(k){{ return ''; }},\
            \"putVariable\": function(k,v){{ }} }};\n\
         var java = {{\"ajax\": function(u){{ return ''; }},\
            \"log\": function(){{ }},\
            \"getVariable\": function(k){{ return ''; }},\
            \"putVariable\": function(k,v){{ }},\
            \"encodeURI\": function(s){{ return encodeURIComponent(s); }},\
            \"encodeURIComponent\": function(s){{ return encodeURIComponent(s); }},\
            \"md5Encode16\": function(s){{ return ''; }},\
            \"md5Encode\": function(s){{ return ''; }},\
            \"s2t\": function(s){{ return s; }},\
            \"removeCookie\": function(){{ }},\
            \"put\": function(){{ }} }};\n\
         var cookie = {{\"removeCookie\": function(){{ }}, \"getCookie\": function(){{ return ''; }} }};\n\
         var cache = {{\"get\": function(){{ return ''; }}, \"put\": function(){{ }} }};\n",
        j(result),
        j(key),
        page,
        j(base_url),
        j(source_key),
        j(source_key),
        j(source_key),
    );
    if context.eval(Source::from_bytes(prelude.as_bytes())).is_err() {
        return String::new();
    }
    // hadc188 语义：脚本可能直接返回表达式值，也可能修改 result 变量（result=url）。
    // 三段候选依次尝试：①纯表达式 ②语句块+回读 result ③裸语句块。
    let candidates = [
        format!("(function(){{ return ({}) }})()", script),
        format!("(function(){{ {} ; return result }})()", script),
        format!("(function(){{ {} }})()", script),
    ];
    for wrapped in &candidates {
        if let Ok(val) = context.eval(Source::from_bytes(wrapped.as_bytes())) {
            if let Ok(s) = val.to_string(&mut context) {
                let out = s.to_std_string_escaped();
                if out != "undefined" && out != "null" && !out.is_empty() {
                    return out;
                }
            }
        }
    }
    String::new()
}
