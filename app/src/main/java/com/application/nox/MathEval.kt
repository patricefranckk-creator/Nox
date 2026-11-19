package com.application.nox

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.E
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Calculatrice exacte pour Nox (les petits modèles se trompent en calcul mental).
 * Grammaire : + - * / ^ %, parenthèses, virgule décimale française, fonctions sqrt/racine, sin, cos, tan, ln, log, abs, pi, e.
 * "20 % de 150" et "150 + 20 %" sont compris.
 */
object MathEval {

    fun eval(input: String): Double {
        var s = input.lowercase()
            .replace("×", "*").replace("÷", "/").replace("x", "*").replace("−", "-")
            .replace(Regex("(\\d),(\\d)"), "$1.$2")
            .replace(" ", "")
        // "20%de150" → (20/100)*150
        s = s.replace(Regex("([0-9.]+)%de"), "($1/100)*")
        return Parser(s).parse()
    }

    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "Résultat indéfini"
        val bd = BigDecimal(v).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString().replace('.', ',')
    }

    private class Parser(val s: String) {
        var i = 0

        fun parse(): Double {
            val v = expr()
            require(i == s.length) { "symbole inattendu « ${s.getOrNull(i)} »" }
            return v
        }

        fun expr(): Double {
            var v = term()
            while (i < s.length) {
                val c = s[i]
                if (c != '+' && c != '-') break
                i++
                val t = term()
                // "150+20%" → 150 + 150*20/100
                v = if (lastWasPercent) { if (c == '+') v + v * t else v - v * t } else { if (c == '+') v + t else v - t }
                lastWasPercent = false
            }
            return v
        }

        var lastWasPercent = false

        fun term(): Double {
            var v = power()
            while (i < s.length) {
                val c = s[i]
                if (c != '*' && c != '/') break
                i++
                val r = power()
                v = if (c == '*') v * r else v / r
            }
            return v
        }

        fun power(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') { i++; return b.pow(power()) }
            return b
        }

        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            var v = atom()
            lastWasPercent = false
            if (i < s.length && s[i] == '%') { i++; v /= 100.0; lastWasPercent = true }
            return v
        }

        fun atom(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                require(i < s.length && s[i] == ')') { "parenthèse manquante" }
                i++
                return v
            }
            val start = i
            if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                return s.substring(start, i).toDouble()
            }
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i)
            return when (name) {
                "pi" -> PI
                "e" -> E
                "sqrt", "racine" -> sqrt(atom())
                "sin" -> sin(atom())
                "cos" -> cos(atom())
                "tan" -> tan(atom())
                "ln" -> ln(atom())
                "log" -> log10(atom())
                "abs" -> abs(atom())
                else -> throw IllegalArgumentException(if (name.isEmpty()) "expression incomplète" else "fonction inconnue « $name »")
            }
        }
    }
}
