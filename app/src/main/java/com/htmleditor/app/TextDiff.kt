package com.htmleditor.app

/**
 * Оценка количества изменившихся символов между двумя текстами:
 * общий префикс и общий суффикс вычитаются, остаток = изменения.
 * Используется для условия обновления предпросмотра (минимум 2 символа).
 */
object TextDiff {

    fun changedCharCount(old: String, new: String): Int {
        if (old == new) return 0
        val minLen = minOf(old.length, new.length)

        var prefix = 0
        while (prefix < minLen && old[prefix] == new[prefix]) prefix++

        var suffix = 0
        while (suffix < minLen - prefix &&
            old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
        ) {
            suffix++
        }

        return maxOf(old.length, new.length) - prefix - suffix
    }
}
