package com.example.memoapp.util

/**
 * 文本统计工具。
 *
 * 字数口径：不计空白字符（空格、换行、制表符），标点计入。
 * 按 Unicode 码点计数，避免 emoji 等代理对字符被算成两个字。
 */
object TextStats {

    fun countChars(text: CharSequence): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            if (!Character.isWhitespace(codePoint)) count++
            index += Character.charCount(codePoint)
        }
        return count
    }
}
