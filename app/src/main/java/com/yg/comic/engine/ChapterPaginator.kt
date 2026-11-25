package com.yg.comic.engine

/**
 * 移植自 FlowReader（https://github.com/HuZaiGong/flowreader）
 * 文件：feature/reader/src/main/java/com/flowreader/feature/reader/ChapterPaginator.kt
 * 原项目许可证：GPL-3.0（YGComic 已在 GitHub 开源，按同协议署名保留）。
 *
 * 纯 Kotlin 贪心分页核心：
 * - 章节拆成 block（标题 / 段落 / 小标题），每个 block 通过注入的 [measureHeight] 回调测量；
 * - 装不下的单个 block 在「原始字符长度」上二分，找测量高度仍 ≤ 可用高度的最大前缀；
 * - 切出的 Fragment 保留绝对章偏移 [Fragment.paragraphStart]，进度/高亮不依赖页码；
 * - 测量与渲染由调用方共用同一个 TextStyle / TextMeasurer，保证断行点完全一致。
 */
class ChapterPaginator(private val measureHeight: (text: String, indent: Boolean, isTitle: Boolean) -> Int) {

    /** 一段可渲染的文本块。[paragraphStart] 是它在整章原文中的绝对字符偏移。 */
    data class Fragment(
        val text: String,
        val paragraphStart: Int,
        val indent: Boolean,
        val isTitle: Boolean,
        val isHeading: Boolean,
    )

    /**
     * @param blocks 带绝对偏移的原始章节块（标题偏移为 0）。
     * @param pageHeightPx 一页可用内容高度（px）。
     * @param gapPx 同一页内相邻 Fragment 之间的垂直间距（px）。
     */
    fun paginate(blocks: List<Block>, pageHeightPx: Int, gapPx: Int): List<List<Fragment>> {
        if (blocks.isEmpty() || pageHeightPx <= 0) return emptyList()

        val pages = mutableListOf<List<Fragment>>()
        var current = mutableListOf<Fragment>()
        var remainingHeight = pageHeightPx

        fun flushPage() {
            if (current.isNotEmpty()) {
                pages.add(current)
                current = mutableListOf()
            }
            remainingHeight = pageHeightPx
        }

        blocks.forEach { block ->
            var text = block.text
            var offset = block.offset
            var firstFragment = true

            while (text.isNotEmpty()) {
                val indent = firstFragment && block.paragraph && block.indent
                val measured = measureHeight(text, indent, block.isTitle)
                val needed = measured + if (current.isEmpty()) 0 else gapPx

                if (needed <= remainingHeight) {
                    current.add(Fragment(text, offset, indent, block.isTitle, block.isHeading))
                    remainingHeight -= needed
                    text = ""
                } else if (current.isEmpty()) {
                    // 单个 block 比整页还高：二分切出能放下的一页，剩下的流到下一页
                    val split = findSplit(text, indent, block.isTitle, remainingHeight)
                    if (split <= 0) {
                        // 连一个字都量不下（极端）：整块塞入，避免死循环
                        current.add(Fragment(text, offset, indent, block.isTitle, block.isHeading))
                        text = ""
                    } else {
                        current.add(Fragment(text.substring(0, split), offset, indent, block.isTitle, block.isHeading))
                        offset += split
                        text = text.substring(split)
                        firstFragment = false
                        flushPage()
                    }
                } else {
                    // 当前页还有内容，但本 block 放不下 → 换页重试本 block
                    flushPage()
                }
            }
            firstFragment = false
        }

        if (current.isNotEmpty()) pages.add(current)
        return pages
    }

    /**
     * 在原始文本长度上二分，返回「测量高度仍 ≤ [availableHeight]」的最大前缀长度。
     * 测量高度对原始长度单调（显示长度去掉 markdown 标记后也单调），故二分成立。
     */
    private fun findSplit(text: String, indent: Boolean, isTitle: Boolean, availableHeight: Int): Int {
        var low = 1
        var high = text.length
        var best = 0
        while (low <= high) {
            val mid = (low + high) / 2
            if (measureHeight(text.substring(0, mid), indent, isTitle) <= availableHeight) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }
}

/** 原始章节块：可选标题、段落或小标题。 */
data class Block(
    val text: String,
    val offset: Int,
    val isTitle: Boolean = false,
    val isHeading: Boolean = false,
    val paragraph: Boolean = true,
    val indent: Boolean = false,
)
