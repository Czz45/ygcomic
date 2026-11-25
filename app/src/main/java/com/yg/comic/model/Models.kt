package com.yg.comic.model

/** 媒体类型：新增类型只加枚举值，不改表结构 */
enum class MediaType(val label: String) {
    COMIC("漫画"),
    NOVEL("小说"),
    SHORT_DRAMA("短剧"),
    AUDIO("音乐");

    companion object {
        fun from(name: String?): MediaType = entries.find { it.name == name } ?: COMIC
    }
}

/** 逻辑书：指向若干物理片段，不直接存文件 */
data class Book(
    val id: Long = 0,
    val title: String,
    val coverPath: String? = null,
    val mediaType: MediaType = MediaType.COMIC,
    val sourceLabel: String = "",
    val favorite: Boolean = false,
    val sortField: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pageCount: Int = 0,
    val collectionId: Long? = null,
)

/** 物理片段：zip 内目录 / 文件夹子目录 / 单个 PDF 都是一段 */
data class Fragment(
    val id: Long = 0,
    val bookId: Long,
    val kind: FragmentKind,
    val sourcePath: String,
    val entryPrefix: String = "",
    val orderIndex: Int = 0,
    val pageCount: Int = 0,
    val passwordAlias: String? = null,
)

enum class FragmentKind { FOLDER, ZIP, SEVENZ, RAR, PDF, IMAGE_DIR, TXT }

/** 合集 = 来源文件夹的物理映射，parentId 组成树 */
data class ComicCollection(
    val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val sourceUri: String = "",
    val orderIndex: Int = 0,
)

/** 统一进度结构：页码 / 章节偏移 / 音视频时间戳共用 */
data class ReadProgress(
    val bookId: Long,
    val pageIndex: Int = 0,
    val chapterOffset: Int = 0,
    val mediaTimeMs: Long = 0L,
    val extra: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** 进度语义标记：pageIndex 在小说里是章号、在漫画/PDF 里是图片页号，靠 extra 区分，避免串味。 */
        const val KIND_NOVEL = "novel"
        const val KIND_COMIC = "comic"
    }
}

/** 书与片段的合并关系（多段合并成一本书） */
data class BookFragmentLink(val bookId: Long, val fragmentId: Long, val orderIndex: Int)

enum class SortMode(val label: String) {
    NATURAL("按自然语言排序"), FILE_NAME("按文件名排序")
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}

enum class PageDirection(val label: String) {
    LTR("从左到右"), RTL("从右到左"), VERTICAL("条漫上下")
}

/** 阅读设置：全局默认 + 单本覆盖（bookId=null 表示全局），漫画/PDF/图片用 */
data class ReaderSettings(
    val bookId: Long? = null,
    val direction: PageDirection = PageDirection.LTR,
    val scale: Float = 1f,
    val dualPage: Boolean = false,
    val background: Long = 0xFF000000L,
    val sensitivity: Float = 1f,
    val immersive: Boolean = false,
)

/** 小说字体族：更多档位，按系统已安装字体解析，取不到回退到通用族 */
enum class NovelFont(val label: String) {
    DEFAULT("默认"), SERIF("宋体"), SANS("黑体"), MONO("等宽"),
    KAI("楷体"), FANGSONG("仿宋"), HEI("细黑"), SONG("明朝"),
}

/** 字重：中 / 粗 / 细 */
enum class NovelWeight(val label: String) {
    MEDIUM("中"), BOLD("粗"), LIGHT("细")
}

/** 翻页动画：整页切换时的过渡方式 */
enum class PageAnimation(val label: String) {
    COVER("覆盖"), SLIDE("滑动"), SIMULATION("仿真"), SCROLL("滚动"), NONE("无动画")
}

/** 简繁显示 */
enum class ChineseVariant(val label: String) {
    SIMPLIFIED("简"), TRADITIONAL("繁")
}

/** 文字颜色 + 背景成对预设 */
data class NovelThemePreset(val id: String, val name: String, val textColor: Long, val background: Long)

object NovelThemes {
    val PRESETS = listOf(
        NovelThemePreset("wechat", "微信读书", 0xFFCFC8BC, 0xFF2B2B2B),
        NovelThemePreset("p1", "预设1", 0xFF2B2B2B, 0xFFFFFFFF),
        NovelThemePreset("p2", "预设2", 0xFF3E3226, 0xFFF5F1E8),
        NovelThemePreset("p3", "预设3", 0xFF243024, 0xFFE3EFE0),
        NovelThemePreset("p4", "预设4", 0xFF2A2E3A, 0xFFE6EAF2),
        NovelThemePreset("p5", "预设5", 0xFF4A3B2A, 0xFFF0E4CC),
        NovelThemePreset("ink", "羊皮纸", 0xFFD8CDB8, 0xFF1A1713),
    )
    fun match(text: Long, bg: Long): String = PRESETS.firstOrNull {
        it.textColor == text && it.background == bg
    }?.id ?: "custom"
}

/**
 * 小说阅读器独立设置：与漫画阅读设置完全分开，只对 TXT 小说生效。
 */
data class NovelSettings(
    val fontSize: Float = 24f,           // 正文字号 sp（放宽上限到 72）
    val lineHeightMul: Float = 1.9f,     // 行距倍数（× 字号，放宽到 4.0）
    val letterSpacing: Float = 0f,       // 字距 sp
    val font: NovelFont = NovelFont.SERIF,
    val fontWeight: NovelWeight = NovelWeight.MEDIUM,
    val variant: ChineseVariant = ChineseVariant.SIMPLIFIED,
    val paragraphSpacing: Float = 16f,   // 段间距 dp
    val sideMargin: Float = 20f,         // 左右边距 dp
    val verticalMargin: Float = 16f,     // 上下边距 dp（页顶/页底留白，对称）
    val indentFirstLine: Boolean = true, // 首行缩进两字
    val autoParagraph: Boolean = true,   // 自动分段
    val background: Long = 0xFF2B2B2BL,  // 默认深色底
    val textColor: Long = 0xFFCFC8BCL,   // 默认浅色字
    val animation: PageAnimation = PageAnimation.SLIDE,
)

data class SourceFolder(val id: Long = 0, val uri: String, val displayName: String)

/**
 * 屏蔽池 / 替换规则（参照 legado ReplaceRule）。
 * pattern=被替换内容，replacement=替换为；scopeTitle/scopeContent 控制作用目标。
 */
data class ReplaceRule(
    val id: Long = 0,
    val name: String = "",
    val group: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val scope: String = "",
    val scopeTitle: Boolean = false,
    val scopeContent: Boolean = true,
    val excludeScope: String = "",
    val isEnabled: Boolean = true,
    val isRegex: Boolean = true,
    val timeoutMillisecond: Long = 3000,
    val order: Int = 0,
)
