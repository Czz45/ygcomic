package com.yg.comic.source

/**
 * 可插拔书源提供者接口。
 *
 * 不同软件（legado / 异次元 / Mihon ...）各自实现本接口，
 * 通过 [SourceProviderRegistry] 注册后，上层只依赖统一数据契约
 * （[SearchBook] / [BookDetail] / [SourceChapter] / [ChapterContent] 等纯 DTO），
 * 不关心底层是 Rust 书源引擎还是别家解析器。
 */
interface SourceProvider {
    /** 提供者唯一标识，如 "legado"。 */
    val providerId: String

    /** 展示名。 */
    val displayName: String

    fun parse(sourceJson: String): ParseResult

    fun search(sourceJson: String, key: String, page: Int = 1): Result<List<SearchBook>>

    fun explore(sourceJson: String, kindUrl: String, page: Int = 1): Result<List<SearchBook>>

    fun detail(sourceJson: String, bookUrl: String): Result<BookDetail>

    fun toc(sourceJson: String, tocUrl: String): Result<List<SourceChapter>>

    fun content(sourceJson: String, chapterUrl: String): Result<ChapterContent>
}

/** legado 书源实现：委托现有的 Rust 书源引擎封装。 */
object LegadoProvider : SourceProvider {
    override val providerId: String = "legado"
    override val displayName: String = "阅读书源"

    override fun parse(sourceJson: String): ParseResult = SourceRepository.legadoParse(sourceJson)
    override fun search(sourceJson: String, key: String, page: Int): Result<List<SearchBook>> =
        SourceRepository.legadoSearch(sourceJson, key, page)
    override fun explore(sourceJson: String, kindUrl: String, page: Int): Result<List<SearchBook>> =
        SourceRepository.legadoExplore(sourceJson, kindUrl, page)
    override fun detail(sourceJson: String, bookUrl: String): Result<BookDetail> =
        SourceRepository.legadoDetail(sourceJson, bookUrl)
    override fun toc(sourceJson: String, tocUrl: String): Result<List<SourceChapter>> =
        SourceRepository.legadoToc(sourceJson, tocUrl)
    override fun content(sourceJson: String, chapterUrl: String): Result<ChapterContent> =
        SourceRepository.legadoContent(sourceJson, chapterUrl)
}

/**
 * 提供者注册中心：legado / 异次元 / Mihon 三套完全独立，互不混源。
 * 上层统一通过 [get] / [default] 取提供者，不直接依赖具体实现。
 */
object SourceProviderRegistry {
    private val providers = linkedMapOf<String, SourceProvider>()

    init {
        register(LegadoProvider)
        register(com.yg.comic.source.yiciyuan.YiciyuanProvider)
        register(com.yg.comic.source.mihon.MihonSourceProvider)
    }

    fun register(provider: SourceProvider) {
        providers[provider.providerId] = provider
    }

    /** 取指定提供者；未注册时回退到 legado，避免上层因缺源崩溃。 */
    fun get(providerId: String): SourceProvider = providers[providerId] ?: LegadoProvider

    val default: SourceProvider get() = LegadoProvider

    fun all(): List<SourceProvider> = providers.values.toList()
}
