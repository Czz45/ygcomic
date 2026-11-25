package com.yg.comic.engine

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import com.yg.comic.model.NovelFont

/**
 * 字体解析：优先用 Android 系统已安装的中文字体（宋体/黑体/楷体/仿宋），
 * 取不到时回退到 Compose 通用族。不打包字体文件，零体积开销。
 * 思路对齐 legado 的 FontManager（探测系统字体名 + 回退）。
 */
object NovelFontResolver {
    private val cache = HashMap<String, FontFamily>()

    fun resolve(font: NovelFont): FontFamily = when (font) {
        NovelFont.DEFAULT -> FontFamily.Default
        NovelFont.SERIF -> FontFamily.Serif
        NovelFont.SANS -> FontFamily.SansSerif
        NovelFont.MONO -> FontFamily.Monospace
        NovelFont.KAI -> fromSystem(FontFamily.Serif, "KaiTi", "楷体")
        NovelFont.FANGSONG -> fromSystem(FontFamily.Serif, "FangSong", "仿宋")
        NovelFont.HEI -> fromSystem(FontFamily.SansSerif, "Heiti SC", "黑体")
        NovelFont.SONG -> fromSystem(FontFamily.Serif, "Songti SC", "SimSun", "宋体")
    }

    private fun fromSystem(fallback: FontFamily, vararg names: String): FontFamily {
        for (name in names) {
            cache[name]?.let { return it }
            runCatching {
                val tf = Typeface.create(name, Typeface.NORMAL)
                // FontFamily(typeface) 让 Compose 用系统解析出的字体渲染
                val fam = FontFamily(tf)
                cache[name] = fam
                return fam
            }
        }
        return fallback
    }

    /**
     * 系统 TextView 用：与 resolve() 同一套字体探测逻辑，直接返回 android.graphics.Typeface。
     * 换系统 TextView 原生选字后，正文用 Typeface 对齐 Compose 端 fontFamily，避免字体不一致导致换行错位。
     */
    fun resolveTypeface(font: NovelFont, bold: Boolean): Typeface {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        return when (font) {
            NovelFont.DEFAULT -> Typeface.defaultFromStyle(style)
            NovelFont.SANS, NovelFont.HEI -> createTf(style, "Heiti SC", "黑体") ?: Typeface.SANS_SERIF
            NovelFont.MONO -> Typeface.MONOSPACE
            NovelFont.KAI -> createTf(style, "KaiTi", "楷体") ?: Typeface.SERIF
            NovelFont.FANGSONG -> createTf(style, "FangSong", "仿宋") ?: Typeface.SERIF
            NovelFont.SONG -> createTf(style, "Songti SC", "SimSun", "宋体") ?: Typeface.SERIF
            NovelFont.SERIF -> Typeface.SERIF
        }
    }

    private fun createTf(style: Int, vararg names: String): Typeface? {
        for (name in names) {
            runCatching { Typeface.create(name, style) }.getOrNull()?.let { return it }
        }
        return null
    }
}
