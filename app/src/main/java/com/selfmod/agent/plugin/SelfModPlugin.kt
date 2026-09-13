package com.selfmod.agent.plugin

/**
 * Contract implemented by every dynamically-loaded dex plugin. Plugins are
 * loaded at runtime via [PluginLoader] using DexClassLoader, so a plugin dex
 * must:
 *   - declare a public no-arg class implementing [SelfModPlugin];
 *   - ship a sibling file `<name>.entry` (next to `<name>.dex`) whose content
 *     is the fully-qualified name of that entry class.
 *
 * Keeping the surface tiny (one method) keeps the host immune to plugin
 * signature drift.
 */
interface SelfModPlugin {
    val id: String
    val version: Int
    fun describe(): String

    /** Run a named action. payload is a JSON string; returns a JSON string. */
    fun invoke(action: String, payload: String): String
}
