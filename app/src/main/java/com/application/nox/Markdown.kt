package com.application.nox

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** Rendu Markdown minimal (gras, italique, code, titres) — suffisant pour un chat, zéro dépendance. */
object Markdown {
    private val CODE_BG = 0xFF0E0E18.toInt()
    private val CODE_FG = 0xFFB9F3FF.toInt()

    fun render(src: String): CharSequence {
        val out = SpannableStringBuilder()
        val blocks = src.split("```")
        blocks.forEachIndexed { i, block ->
            if (i % 2 == 1) {
                // bloc de code (on retire l'éventuelle langue en 1re ligne)
                val body = block.substringAfter('\n', block).trimEnd()
                val start = out.length
                out.append(body).append('\n')
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(CODE_BG), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(CODE_FG), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.88f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                inline(out, block)
            }
        }
        return out
    }

    private fun inline(out: SpannableStringBuilder, text: String) {
        text.lines().forEachIndexed { li, rawLine ->
            if (li > 0) out.append('\n')
            var line = rawLine
            var heading = false
            val h = Regex("^\\s*#{1,6}\\s+").find(line)
            if (h != null) { line = line.substring(h.range.last + 1); heading = true }
            line = line.replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
            val lineStart = out.length
            var i = 0
            while (i < line.length) {
                when {
                    line.startsWith("**", i) -> {
                        val end = line.indexOf("**", i + 2)
                        if (end > i + 2) {
                            val s = out.length; out.append(line, i + 2, end)
                            out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            i = end + 2; continue
                        }
                    }
                    line[i] == '`' -> {
                        val end = line.indexOf('`', i + 1)
                        if (end > i + 1) {
                            val s = out.length; out.append(line, i + 1, end)
                            out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            out.setSpan(ForegroundColorSpan(CODE_FG), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            i = end + 1; continue
                        }
                    }
                    line[i] == '*' && i + 1 < line.length && line[i + 1] != ' ' -> {
                        val end = line.indexOf('*', i + 1)
                        if (end > i + 1) {
                            val s = out.length; out.append(line, i + 1, end)
                            out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            i = end + 1; continue
                        }
                    }
                }
                out.append(line[i]); i++
            }
            if (heading && out.length > lineStart) {
                out.setSpan(StyleSpan(Typeface.BOLD), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(1.12f), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
