package com.selfmod.agent.util

import org.json.JSONArray
import org.json.JSONObject

/** Thin helpers over org.json to avoid pulling in a heavier JSON library. */
object JsonUtil {
    fun obj(s: String): JSONObject = JSONObject(s)

    fun arr(s: String): JSONArray = JSONArray(s)

    fun pretty(o: Any?): String = when (o) {
        is JSONObject -> o.toString(2)
        is JSONArray -> o.toString(2)
        else -> o.toString()
    }

    /** Best-effort pretty-print of an arbitrary JSON string. */
    fun prettyString(s: String): String = runCatching {
        val trimmed = s.trim()
        if (trimmed.startsWith("[")) JSONArray(trimmed).toString(2)
        else JSONObject(trimmed).toString(2)
    }.getOrDefault(s)
}
