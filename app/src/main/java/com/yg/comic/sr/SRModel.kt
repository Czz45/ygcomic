package com.yg.comic.sr

/**
 * 超分模型清单（源自 mihon-super-resolution，MIT）。
 * 模型不内置，首次使用时从内置的多条镜像链接自由下载到 App 私有目录。
 * 每条模型给多个镜像，按国内可用性排序，失败自动切换下一条。
 */
enum class SRModel(
    val key: String,
    val label: String,
    val dir: String,
    val modelType: String,   // realcugan | realesrgan
    val scale: Int,
    val tier: String,        // 性能挡位说明
) {
    REALESRGAN_ANIME_FAST(
        "realesrgan_anime_fast", "动漫·极速", "realesrgan-anime-fast", "realesrgan", 4,
        "最快·包最小(1.2MB)",
    ),
    REALCUGAN_2X(
        "realcugan_2x", "线条·2倍(推荐)", "realcugan-2x-conservative", "realcugan", 2,
        "线条锐利·漫画首选(2.5MB)",
    ),
    REALCUGAN_4X(
        "realcugan_4x", "线条·4倍", "realcugan-4x-conservative", "realcugan", 4,
        "线条锐利·大图(2.8MB)",
    ),
    REALESRGAN_GENERAL(
        "realesrgan_general", "通用·快速", "realesrgan-general-fast", "realesrgan", 4,
        "写实/通用图(4.7MB)",
    ),
    REALESRGAN_ANIME_PLUS(
        "realesrgan_anime_plus", "动漫·极致", "realesrgan-anime-plus", "realesrgan", 4,
        "最佳画质·最慢(8.5MB)",
    ),
    ;

    /** 模型文件基准路径（相对仓库根）。param/bin 文件名与目录名相同。 */
    private fun assetPath(file: String) =
        "core/superresolution/src/main/assets/models/$dir/$file"

    /** 下载镜像列表（国内优先，逐条 fallback）。 */
    fun mirrorUrls(file: String): List<String> {
        val p = assetPath(file)
        return listOf(
            "https://ghfast.top/https://raw.githubusercontent.com/timyang2005/mihon-super-resolution/master/$p",
            "https://cdn.jsdelivr.net/gh/timyang2005/mihon-super-resolution@master/$p",
            "https://ghproxy.net/https://raw.githubusercontent.com/timyang2005/mihon-super-resolution/master/$p",
            "https://raw.githubusercontent.com/timyang2005/mihon-super-resolution/master/$p",
        )
    }

    companion object {
        const val REPO_API = "https://api.github.com/repos/timyang2005/mihon-super-resolution/contents"

        fun fromKey(key: String?): SRModel = entries.firstOrNull { it.key == key } ?: REALCUGAN_2X

        /** 按性能挡位从快到慢排序，供 UI 展示。 */
        fun allBySpeed(): List<SRModel> = listOf(
            REALESRGAN_ANIME_FAST, REALCUGAN_2X, REALCUGAN_4X,
            REALESRGAN_GENERAL, REALESRGAN_ANIME_PLUS,
        )
    }
}
