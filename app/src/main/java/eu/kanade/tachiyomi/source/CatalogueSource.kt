package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SManga

@Suppress("Unused")
interface CatalogueSource : Source {

    /** ISO 639-1 语言码，小写两字母。 */
    val lang: String

    val supportsRelatedMangas: Boolean get() = false

    val disableRelatedMangasBySearch: Boolean get() = false

    val disableRelatedMangas: Boolean get() = false

    suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> =
        throw UnsupportedOperationException("Unsupported!")
}
