//! source_engine —— legado 书源规则引擎（Rust 核心）。
//!
//! 对外通过 JNI 暴露（包名 com.yg.comic.source.SourceEngine）。
//! 所有接口返回 JSON 字符串，Kotlin 侧用 kotlinx.serialization 解析。

pub mod api;
pub mod content;
pub mod http;
pub mod js;
pub mod model;
pub mod rule;

#[cfg(test)]
mod tests;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

fn to_jstring(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => env.new_string("").map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

/// 引擎版本
#[no_mangle]
pub extern "system" fn Java_com_yg_comic_source_SourceEngine_nativeVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&mut env, "source-engine 0.1.0-ycy3".to_string())
}

/// 校验并规范化一条书源 JSON：返回解析后的关键字段（JSON），失败返回错误信息 JSON。
#[no_mangle]
pub extern "system" fn Java_com_yg_comic_source_SourceEngine_nativeParseSource(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
) -> jstring {
    let json = jstr(&mut env, &json);
    let out = match api::BookSource::from_json(&json) {
        Ok(src) => serde_json::json!({
            "ok": true,
            "name": src.name,
            "url": src.url,
            "hasSearch": !src.search_url.is_empty(),
            "hasExplore": !src.explore_url.is_empty(),
        })
        .to_string(),
        Err(e) => serde_json::json!({ "ok": false, "error": e }).to_string(),
    };
    to_jstring(&mut env, out)
}

/// 搜索：参数 (书源JSON, 关键词, 页码) → SearchBook[] JSON
#[no_mangle]
pub extern "system" fn Java_com_yg_comic_source_SourceEngine_nativeSearch(
    mut env: JNIEnv,
    _class: JClass,
    source_json: JString,
    key: JString,
    page: jni::sys::jint,
) -> jstring {
    let source_json = jstr(&mut env, &source_json);
    let key = jstr(&mut env, &key);
    let out = match api::BookSource::from_json(&source_json) {
        Ok(src) => match api::search(&src, &key, page as i64) {
            Ok(list) => serde_json::to_string(&list).unwrap_or_else(|_| "[]".into()),
            Err(e) => serde_json::json!({ "error": e }).to_string(),
        },
        Err(e) => serde_json::json!({ "error": e }).to_string(),
    };
    to_jstring(&mut env, out)
}

/// 发现
#[no_mangle]
pub extern "system" fn Java_com_yg_comic_source_SourceEngine_nativeExplore(
    mut env: JNIEnv,
    _class: JClass,
    source_json: JString,
    kind_url: JString,
    page: jni::sys::jint,
) -> jstring {
    let source_json = jstr(&mut env, &source_json);
    let kind_url = jstr(&mut env, &kind_url);
    let out = match api::BookSource::from_json(&source_json) {
        Ok(src) => match api::explore(&src, &kind_url, page as i64) {
            Ok(list) => serde_json::to_string(&list).unwrap_or_else(|_| "[]".into()),
            Err(e) => serde_json::json!({ "error": e }).to_string(),
        },
        Err(e) => serde_json::json!({ "error": e }).to_string(),
    };
    to_jstring(&mut env, out)
}

/// 详情：参数 (书源JSON, bookUrl) → BookDetail JSON
#[no_mangle]
pub extern "system" fn Java_com_yg_comic_source_SourceEngine_nativeDetail(
    mut env: JNIEnv,
    _class: JClass,
    source_json: JString,
    book_url: JString,
) -> jstring {
    let source_json = jstr(&mut env, &source_json);
    let book_url = jstr(&mut env, &book_url);
    let out = match api::BookSource::from_json(&source_json) {
        Ok(src) => match api::detail(&src, &book_url) {
            Ok(d) => serde_json::to_string(&d).unwrap_or_else(|_| "{}".into()),
            Err(e) => serde_json::json!({ "error": e }).to_string(),
        },
        Err(e) => serde_json::json!({ "error": e }).to_string(),
    };
    to_jstring(&mut env, out)
}

/// 目录：参数 (书源JSON, tocUrl) → Chapter[] JSON
#[no_mangle]
pub extern "system" fn Java_com_yg_comic_source_SourceEngine_nativeToc(
    mut env: JNIEnv,
    _class: JClass,
    source_json: JString,
    toc_url: JString,
) -> jstring {
    let source_json = jstr(&mut env, &source_json);
    let toc_url = jstr(&mut env, &toc_url);
    let out = match api::BookSource::from_json(&source_json) {
        Ok(src) => match api::toc(&src, &toc_url) {
            Ok(list) => serde_json::to_string(&list).unwrap_or_else(|_| "[]".into()),
            Err(e) => serde_json::json!({ "error": e }).to_string(),
        },
        Err(e) => serde_json::json!({ "error": e }).to_string(),
    };
    to_jstring(&mut env, out)
}

/// 正文：参数 (书源JSON, chapterUrl) → Content JSON
#[no_mangle]
pub extern "system" fn Java_com_yg_comic_source_SourceEngine_nativeContent(
    mut env: JNIEnv,
    _class: JClass,
    source_json: JString,
    chapter_url: JString,
) -> jstring {
    let source_json = jstr(&mut env, &source_json);
    let chapter_url = jstr(&mut env, &chapter_url);
    let out = match api::BookSource::from_json(&source_json) {
        Ok(src) => match api::content(&src, &chapter_url) {
            Ok(c) => serde_json::to_string(&c).unwrap_or_else(|_| "{}".into()),
            Err(e) => serde_json::json!({ "error": e }).to_string(),
        },
        Err(e) => serde_json::json!({ "error": e }).to_string(),
    };
    to_jstring(&mut env, out)
}
