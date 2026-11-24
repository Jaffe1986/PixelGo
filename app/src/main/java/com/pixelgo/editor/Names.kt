package com.pixelgo.editor

/** Default names that don't repeat ("Voice", "Voice 2"…, "Recording 1", "Recording 2"…). */
object Names {
    /** [base] if it's free, otherwise "[base] 2", "[base] 3"…, whichever is free first. */
    fun unique(base: String, taken: Collection<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n"
    }

    /** "[prefix] N", with N one more than the highest such number already used. */
    fun numbered(prefix: String, taken: Collection<String>): String {
        val pattern = Regex("^" + Regex.escape(prefix) + " (\\d+)$")
        val highest = taken.mapNotNull { pattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return "$prefix ${highest + 1}"
    }
}
