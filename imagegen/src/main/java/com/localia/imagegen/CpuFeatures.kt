package com.localia.imagegen

import java.io.File

/** What the phone's CPU can run, from the « Features » lines of /proc/cpuinfo. */
internal object CpuFeatures {

    /** True when every core lists the dot-product (asimddp) and half-precision arithmetic (asimdhp) instructions. */
    fun hasDotProdAndFp16(): Boolean = runCatching { hasDotProdAndFp16(File("/proc/cpuinfo").readText()) }.getOrDefault(false)

    internal fun hasDotProdAndFp16(cpuinfo: String): Boolean {
        val featureLines = cpuinfo.lines().filter { it.trimStart().startsWith("Features") }
        if (featureLines.isEmpty()) return false
        return featureLines.all { line ->
            val flags = line.substringAfter(':').trim().split(Regex("\\s+")).toSet()
            "asimddp" in flags && "asimdhp" in flags
        }
    }
}
