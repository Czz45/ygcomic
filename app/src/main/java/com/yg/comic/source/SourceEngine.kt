package com.yg.comic.source

/**
 * 书源规则引擎（Rust 实现）的 JNI 桥。
 * 所有返回值为 JSON 字符串。
 */
object SourceEngine {
    init {
        System.loadLibrary("source_engine")
    }

    /** 引擎版本 */
    external fun nativeVersion(): String

    /** 校验书源 JSON，返回 {ok, name, url, hasSearch, hasExplore} 或 {ok:false, error} */
    external fun nativeParseSource(json: String): String

    /** 搜索：返回 SearchBook[] JSON 或 {"error":...} */
    external fun nativeSearch(sourceJson: String, key: String, page: Int): String

    /** 发现：返回 SearchBook[] JSON */
    external fun nativeExplore(sourceJson: String, kindUrl: String, page: Int): String

    /** 详情：返回 BookDetail JSON */
    external fun nativeDetail(sourceJson: String, bookUrl: String): String

    /** 目录：返回 Chapter[] JSON */
    external fun nativeToc(sourceJson: String, tocUrl: String): String

    /** 正文：返回 Content JSON */
    external fun nativeContent(sourceJson: String, chapterUrl: String): String

    val version: String get() = runCatching { nativeVersion() }.getOrDefault("unavailable")
}
