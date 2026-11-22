package com.application.nox
import java.util.Locale
object Text { fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(Locale.FRANCE), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "") }
