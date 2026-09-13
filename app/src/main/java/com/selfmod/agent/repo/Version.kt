package com.selfmod.agent.repo

/** A saved snapshot of a script's content, used for rollback. */
data class Version(
    val id: String,         // file name of the snapshot (without extension)
    val timestamp: Long,
    val sizeBytes: Int,
    val scriptName: String,
) {
    fun displayName(): String {
        val d = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(timestamp))
        return "$id · $d · ${sizeBytes}B"
    }
}
