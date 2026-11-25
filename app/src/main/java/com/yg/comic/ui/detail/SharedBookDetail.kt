package com.yg.comic.ui.detail

import com.yg.comic.ui.source.SourcedBook

/**
 * 从搜索页把「当前查看的书」带到详情页（含整条来源列表，sourceJson 很大不能走路由参数）。
 * 仿 SharedSource：Activity 作用域单例，post 后 consume 一次即清空。
 */
object SharedBookDetail {
    private var pending: SourcedBook? = null
    fun post(sb: SourcedBook) { pending = sb }
    // 非破坏性读取：从阅读器返回时 book_detail 会重新组合，必须还能读到同一本书，
    // 否则 consume() 清空后 sb=null 导致整块 UI 不渲染（白屏）。post 永远在 navigate 前写入。
    val current: SourcedBook? get() = pending
    fun consume(): SourcedBook? { val v = pending; pending = null; return v }
}
