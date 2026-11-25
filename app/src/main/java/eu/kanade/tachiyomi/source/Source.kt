package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

/** 与 extensions-lib 完全一致的 Source 接口（运行时由宿主提供真实实现）。 */
@Suppress("Unused")
interface Source {

    val id: Long
    val name: String
    val supportsLatest: Boolean

    fun getFilterList(): FilterList = FilterList()

    suspend fun getPopularManga(page: Int): MangasPage

    suspend fun getLatestUpdates(page: Int): MangasPage

    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage

    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate

    suspend fun getPageList(chapter: SChapter): List<Page>
}
