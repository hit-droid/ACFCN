package com.selfmod.agent.memory

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Long-term memory layer: tokenization, Jaccard recall, kinds, and de-dup.
 * Driven against an in-memory [SharedPreferences] via the test-only secondary
 * constructor — no Robolectric / Android context required.
 */
class MemoryServiceTest {

    private val prefs = InMemoryPrefs()
    private val mem = MemoryService(prefs)

    @Test
    fun tokenizeSplitsCjkAndLatin() {
        // CJK chars tokenize one-per-character; latin/digit runs stay whole words.
        val t = MemoryService.tokenize("使用 Kotlin 编程构建 App")
        assertEquals(listOf("使", "用", "kotlin", "编", "程", "构", "建", "app"), t)
    }

    @Test
    fun tokenizeDropsStopwords() {
        val t = MemoryService.tokenize("我的猫在睡觉了")
        assertFalse("的 应被过滤", t.contains("我"))
        assertFalse("了 应被过滤", t.contains("了"))
        assertTrue(t.contains("猫"))
        assertTrue(t.contains("睡"))
        assertTrue(t.contains("觉"))
    }

    @Test
    fun recallRanksByOverlap() {
        mem.record("Kotlin 是一种运行在 JVM 上的编程语言")
        mem.record("Python 常用于数据科学和机器学习")
        val hits = mem.recall("Kotlin 编程语言")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits[0].text.contains("Kotlin"))
    }

    @Test
    fun recallEmptyQueryReturnsNothing() {
        mem.record("something to remember")
        assertTrue(mem.recall("").isEmpty())
    }

    @Test
    fun kindsArePersistedAndFilterable() {
        mem.record("用户姓张，做后端开发", MemoryService.Kind.USER)
        mem.record("偏好简洁的中文回复", MemoryService.Kind.PREFERENCE)
        mem.record("一般性的杂项事实", MemoryService.Kind.GENERAL)
        val profile = mem.userProfile()
        assertEquals(2, profile.size)
        assertTrue(profile.all { it.kind == "user" || it.kind == "preference" })
        // Explicit kind filter on recall.
        val userOnly = mem.recall("用户", kinds = setOf(MemoryService.Kind.USER))
        assertTrue(userOnly.all { it.kind == "user" })
    }

    @Test
    fun recordDeduplicatesIdenticalText() {
        mem.record("重复的事实")
        mem.record("重复的事实")
        val all = mem.all()
        assertEquals(1, all.count { it.text == "重复的事实" })
    }

    @Test
    fun emptyTextRejected() {
        var threw = false
        try {
            mem.record("   ")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun capStaysBoundedAcrossManyInserts() {
        repeat(600) { mem.record("记忆条目编号 $it 用于压力测试上限") }
        assertTrue(mem.all().size <= 500)
    }

    /** Minimal SharedPreferences backed by a HashMap — enough for MemoryService. */
    private class InMemoryPrefs : SharedPreferences {
        private val data = HashMap<String, Any>()
        private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(k: String?, d: String?): String? = data[k] as? String ?: d
        override fun getStringSet(k: String?, d: MutableSet<String>?): MutableSet<String>? =
            (data[k] as? MutableSet<*>)?.map { it as String }?.toMutableSet() ?: d

        override fun getInt(k: String?, d: Int): Int = data[k] as? Int ?: d
        override fun getLong(k: String?, d: Long): Long = data[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float): Float = data[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean): Boolean = data[k] as? Boolean ?: d
        override fun contains(k: String?): Boolean = data.containsKey(k)
        override fun edit(): SharedPreferences.Editor = InMemoryEditor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
            l?.let { listeners.add(it) }
        }

        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
            listeners.remove(l)
        }

        inner class InMemoryEditor : SharedPreferences.Editor {
            private val removed = HashSet<String>()
            private val put = HashMap<String, Any>()
            override fun putString(k: String?, v: String?): SharedPreferences.Editor = apply { if (k != null) put[k] = v as Any }
            override fun putStringSet(k: String?, v: MutableSet<String>?): SharedPreferences.Editor = apply { if (k != null) put[k] = v as Any }
            override fun putInt(k: String?, v: Int): SharedPreferences.Editor = apply { if (k != null) put[k] = v }
            override fun putLong(k: String?, v: Long): SharedPreferences.Editor = apply { if (k != null) put[k] = v }
            override fun putFloat(k: String?, v: Float): SharedPreferences.Editor = apply { if (k != null) put[k] = v }
            override fun putBoolean(k: String?, v: Boolean): SharedPreferences.Editor = apply { if (k != null) put[k] = v }
            override fun remove(k: String?): SharedPreferences.Editor = apply { if (k != null) removed.add(k) }
            override fun clear(): SharedPreferences.Editor = apply { removed.addAll(data.keys) }
            override fun commit(): Boolean { applyChanges(); return true }
            override fun apply() = applyChanges()
            private fun applyChanges() {
                removed.forEach { data.remove(it) }
                put.forEach { (k, v) -> data[k] = v }
                removed.clear(); put.clear()
            }
        }
    }
}
