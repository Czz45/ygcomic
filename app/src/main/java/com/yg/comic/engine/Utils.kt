package com.yg.comic.engine

import java.text.Collator
import java.util.Locale

/** 自然排序：第2页 < 第10页，支持中文 */
object NaturalOrder : Comparator<String> {
    private val collator = Collator.getInstance(Locale.CHINESE)

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ac = a[i]
            val bc = b[j]
            if (ac.isDigit() && bc.isDigit()) {
                var ai = i
                var bj = j
                while (ai < a.length && a[ai].isDigit()) ai++
                while (bj < b.length && b[bj].isDigit()) bj++
                val an = a.substring(i, ai).trimStart('0').ifEmpty { "0" }
                val bn = b.substring(j, bj).trimStart('0').ifEmpty { "0" }
                if (an.length != bn.length) return an.length.compareTo(bn.length)
                if (an != bn) return an.compareTo(bn)
                i = ai; j = bj
            } else {
                if (ac != bc) return collator.compare(ac.toString(), bc.toString())
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}

val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif")

fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT
