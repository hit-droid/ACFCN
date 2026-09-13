package com.selfmod.agent.plugin

import com.selfmod.agent.repo.CodeRepository
import java.io.File

/**
 * Tracks installed and currently-loaded plugins. Plugins are addressed by
 * name (the dex file stem). Re-loading an already-loaded plugin returns the
 * cached instance — call [unload] first to force a fresh classloader.
 */
class PluginRegistry(
    private val repo: CodeRepository,
    optimizeDir: File,
    private val parentClassLoader: ClassLoader = SelfModPlugin::class.java.classLoader!!,
) {
    private val loader = PluginLoader(optimizeDir, parentClassLoader)
    private val loaded = LinkedHashMap<String, SelfModPlugin>()

    fun available(): List<String> = repo.listPlugins()

    @Synchronized
    fun load(name: String): SelfModPlugin {
        loaded[name]?.let { return it }
        val dexFile = File(repo.pluginsDir(), "$name.dex")
        val entry = repo.pluginEntry(name)
            ?: error("Plugin $name has no entry descriptor; install it via repo.installPlugin first")
        val plugin = loader.load(dexFile, entry)
        loaded[name] = plugin
        return plugin
    }

    @Synchronized
    fun unload(name: String) { loaded.remove(name) }

    @Synchronized
    fun unloadAll() { loaded.clear() }

    fun get(name: String): SelfModPlugin? = loaded[name]
    fun loaded(): List<String> = loaded.keys.toList().sorted()
}
