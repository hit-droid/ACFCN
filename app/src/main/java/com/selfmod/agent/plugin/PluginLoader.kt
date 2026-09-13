package com.selfmod.agent.plugin

import dalvik.system.DexClassLoader
import java.io.File

/**
 * Loads a `.dex` file as a plugin. The dex must live in app-private storage
 * (DexClassLoader requires a writable optimized-output dir). The entry class
 * name is read from a sibling `.entry` descriptor file so the loader itself
 * stays version-agnostic.
 */
class PluginLoader(
    private val optimizeDir: File,
    private val parentClassLoader: ClassLoader,
) {
    init { optimizeDir.mkdirs() }

    fun load(dexFile: File, entryClass: String): SelfModPlugin {
        require(dexFile.exists()) { "Plugin dex not found: ${dexFile.absolutePath}" }
        val cl = DexClassLoader(
            dexFile.absolutePath,
            optimizeDir.absolutePath,
            null,
            parentClassLoader,
        )
        val cls = cl.loadClass(entryClass)
        val instance = cls.getDeclaredConstructor().newInstance()
        require(instance is SelfModPlugin) {
            "Entry class $entryClass does not implement SelfModPlugin"
        }
        return instance
    }
}
