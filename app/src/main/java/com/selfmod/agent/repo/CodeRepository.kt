package com.selfmod.agent.repo

import android.content.Context
import com.selfmod.agent.plugin.SelfModPlugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device store for the agent's editable code: JS scripts and plugin .dex
 * files. Each write to a script creates a timestamped snapshot under
 * `versions/<name>/` so the agent (or the user) can roll back a bad edit.
 *
 * Layout under app-private files dir:
 *   scripts/<name>.js              current script body
 *   plugins/<name>.dex             plugin bytecode
 *   plugins/<name>.entry           entry-class descriptor for PluginLoader
 *   versions/<name>/<ts>.js        prior versions of the script
 */
class CodeRepository(
    private val scriptsDir: File,
    private val pluginsDir: File,
    private val versionsDir: File,
) {
    init { listOf(scriptsDir, pluginsDir, versionsDir).forEach { it.mkdirs() } }

    // --- Scripts --------------------------------------------------------

    fun listScripts(): List<String> =
        (scriptsDir.listFiles { f -> f.extension == "js" } ?: emptyArray())
            .map { it.nameWithoutExtension }.sorted()

    fun readScript(name: String): String =
        File(scriptsDir, "$name.js").readText()

    fun scriptExists(name: String): Boolean = File(scriptsDir, "$name.js").exists()

    /** Returns the new version id. */
    fun writeScript(name: String, content: String): String {
        val target = File(scriptsDir, "$name.js")
        if (target.exists()) snapshot(name, target.readText())
        target.writeText(content)
        return "v${System.currentTimeMillis()}"
    }

    fun deleteScript(name: String): Boolean {
        val f = File(scriptsDir, "$name.js")
        return f.delete()
    }

    fun listVersions(name: String): List<Version> {
        val dir = File(versionsDir, name)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "js" }
            ?.map {
                Version(
                    id = it.nameWithoutExtension,
                    timestamp = runCatching { it.nameWithoutExtension.removePrefix("v").toLong() }.getOrDefault(0L),
                    sizeBytes = it.length().toInt(),
                    scriptName = name,
                )
            }?.sortedByDescending { it.timestamp } ?: emptyList()
    }

    fun rollback(name: String, versionId: String): String {
        val dir = File(versionsDir, name)
        val snap = File(dir, "$versionId.js")
        require(snap.exists()) { "Version $versionId not found for $name" }
        val current = File(scriptsDir, "$name.js")
        if (current.exists()) snapshot(name, current.readText())
        current.writeText(snap.readText())
        return snap.readText()
    }

    private fun snapshot(name: String, content: String) {
        val dir = File(versionsDir, name).apply { mkdirs() }
        val ts = "v${System.currentTimeMillis()}"
        File(dir, "$ts.js").writeText(content)
    }

    /** First-time bootstrap: copy a script from app assets if absent. */
    fun importAssetScript(context: Context, assetName: String, scriptName: String) {
        if (scriptExists(scriptName)) return
        val content = context.assets.open("scripts/$assetName").bufferedReader().readText()
        File(scriptsDir, "$scriptName.js").writeText(content)
    }

    // --- Plugins -------------------------------------------------------

    fun listPlugins(): List<String> =
        (pluginsDir.listFiles { f -> f.extension == "dex" } ?: emptyArray())
            .map { it.nameWithoutExtension }.sorted()

    fun installPlugin(name: String, dexBytes: ByteArray, entryClass: String): Boolean {
        val dex = File(pluginsDir, "$name.dex")
        dex.writeBytes(dexBytes)
        File(pluginsDir, "$name.entry").writeText(entryClass)
        return true
    }

    fun pluginEntry(name: String): String? =
        File(pluginsDir, "$name.entry").takeIf { it.exists() }?.readText()?.trim()

    fun deletePlugin(name: String): Boolean {
        val dex = File(pluginsDir, "$name.dex").delete()
        File(pluginsDir, "$name.entry").delete()
        return dex
    }

    fun pluginsDir(): File = pluginsDir
    fun scriptsDir(): File = scriptsDir
}
