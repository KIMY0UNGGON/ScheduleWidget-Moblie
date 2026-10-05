package com.schedulewidget.mobile.stt

import com.schedulewidget.mobile.data.SttCorrection

/**
 * The user's correction dictionary (AppData.stt.corrections), applied to every new transcript right after
 * recognition: plain substring replacement, longest "from" first so "데이터 베이스" wins over "데이터",
 * blank "from" entries skipped. Each position is replaced at most once (no cascading into replaced text).
 */
object SttCorrections {
    fun apply(text: String, list: List<SttCorrection>): String {
        val rules = list.filter { it.from.isNotBlank() }.distinctBy { it.from }.sortedByDescending { it.from.length }
        if (rules.isEmpty() || text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val rule = rules.firstOrNull { text.startsWith(it.from, i) }
            if (rule != null) {
                out.append(rule.to)
                i += rule.from.length
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }
}
