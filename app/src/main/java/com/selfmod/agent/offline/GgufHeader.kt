package com.selfmod.agent.offline

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class GgufInfo(
    val version: Int,
    val tensorCount: Long,
    val name: String,
    val architecture: String,
    val quant: String,
    val contextLength: Long,
    val parameterCount: Long,
    val extra: Map<String, String>,
) {
    fun summary(): String = buildString {
        if (name.isNotBlank()) append(name) else append("GGUF")
        if (architecture.isNotBlank()) append(" · ").append(architecture)
        if (quant.isNotBlank()) append(" · ").append(quant)
        if (contextLength > 0) append(" · ctx ").append(contextLength)
        if (parameterCount > 0) append(" · ").append(paramLabel())
    }

    fun paramLabel(): String = when {
        parameterCount >= 1_000_000_000 -> "%.1fB".format(parameterCount / 1_000_000_000.0)
        parameterCount >= 1_000_000 -> "%.0fM".format(parameterCount / 1_000_000.0)
        parameterCount > 0 -> parameterCount.toString()
        else -> ""
    }
}

object GgufHeader {
    private val MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    private const val MAX_KV = 1024
    private const val MAX_STRING = 1_000_000L
    private const val MAX_ARRAY = 100_000L

    private const val T_UINT8 = 0
    private const val T_INT8 = 1
    private const val T_UINT16 = 2
    private const val T_INT16 = 3
    private const val T_UINT32 = 4
    private const val T_INT32 = 5
    private const val T_FLOAT32 = 6
    private const val T_BOOL = 7
    private const val T_STRING = 8
    private const val T_ARRAY = 9
    private const val T_UINT64 = 10
    private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    fun parse(input: InputStream): GgufInfo? = runCatching { parseOrThrow(input) }.getOrNull()

    fun parseOrThrow(input: InputStream): GgufInfo {
        val r = LittleReader(input)
        val magic = r.bytes(4)
        require(magic.contentEquals(MAGIC)) { "not a GGUF file" }
        val version = r.u32()
        require(version in 1..3) { "unsupported GGUF version $version" }
        val tensorCount = r.u64()
        val kvCount = r.u64().coerceAtMost(MAX_KV.toLong()).toInt()
        val kv = LinkedHashMap<String, String>()
        repeat(kvCount) {
            val key = r.str()
            val value = r.value()
            if (key.isNotBlank()) kv[key] = value
        }
        val arch = kv["general.architecture"].orEmpty()
        val name = kv["general.name"].orEmpty()
        val quant = kv["general.file_type"]?.let { fileTypeLabel(it) }
            ?: kv["general.quantization_version"].orEmpty()
        val ctx = sequenceOf(
            kv["$arch.context_length"],
            kv["llama.context_length"],
            kv["qwen.context_length"],
            kv["qwen2.context_length"],
            kv["gemma.context_length"],
            kv["general.context_length"],
        ).mapNotNull { it?.toLongOrNull() }.firstOrNull() ?: 0L
        val params = kv["general.parameter_count"]?.toLongOrNull() ?: 0L
        return GgufInfo(
            version = version,
            tensorCount = tensorCount,
            name = name,
            architecture = arch,
            quant = quant,
            contextLength = ctx,
            parameterCount = params,
            extra = kv,
        )
    }

    private fun fileTypeLabel(raw: String): String {
        val n = raw.toIntOrNull() ?: return raw
        return FILE_TYPES[n] ?: "type$n"
    }

    private val FILE_TYPES = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1",
        6 to "Q5_0", 7 to "Q5_1", 8 to "Q8_0",
        10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L",
        14 to "Q4_K_S", 15 to "Q4_K_M", 16 to "Q5_K_S", 17 to "Q5_K_M",
        18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S",
        23 to "IQ3_S", 24 to "IQ3_XXS", 25 to "IQ1_S", 26 to "IQ4_NL",
        27 to "IQ3_XS", 28 to "IQ1_M", 29 to "IQ4_XS",
    )

    private class LittleReader(private val input: InputStream) {
        fun bytes(n: Int): ByteArray {
            require(n >= 0) { "negative read" }
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val got = input.read(out, off, n - off)
                if (got <= 0) throw IOException("unexpected EOF")
                off += got
            }
            return out
        }

        fun u8(): Int = input.read().also { if (it < 0) throw IOException("unexpected EOF") }

        fun u16(): Int {
            val b = bytes(2)
            return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
        }

        fun i16(): Int = ByteBuffer.wrap(bytes(2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

        fun u32(): Int = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int

        fun u64(): Long = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).long

        fun f32(): Float = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).float

        fun f64(): Double = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).double

        fun str(): String {
            val n = u64()
            require(n in 0..MAX_STRING) { "string too long: $n" }
            if (n == 0L) return ""
            return String(bytes(n.toInt()), StandardCharsets.UTF_8)
        }

        fun value(): String = typed(u32())

        private fun typed(t: Int): String = when (t) {
            T_UINT8 -> u8().toString()
            T_INT8 -> u8().toByte().toString()
            T_UINT16 -> u16().toString()
            T_INT16 -> i16().toString()
            T_UINT32 -> (u32().toLong() and 0xffffffffL).toString()
            T_INT32 -> u32().toString()
            T_FLOAT32 -> f32().toString()
            T_BOOL -> (u8() != 0).toString()
            T_STRING -> str()
            T_ARRAY -> arraySummary()
            T_UINT64, T_INT64 -> u64().toString()
            T_FLOAT64 -> f64().toString()
            else -> "t$t"
        }

        private fun arraySummary(): String {
            val elem = u32()
            val n = u64()
            require(n in 0..MAX_ARRAY) { "array too long: $n" }
            repeat(n.toInt()) { skipTyped(elem) }
            return "[$n]"
        }

        private fun skipTyped(t: Int) {
            when (t) {
                T_UINT8, T_INT8, T_BOOL -> u8()
                T_UINT16, T_INT16 -> bytes(2)
                T_UINT32, T_INT32, T_FLOAT32 -> bytes(4)
                T_STRING -> str()
                T_ARRAY -> {
                    val elem = u32()
                    val n = u64()
                    require(n in 0..MAX_ARRAY) { "array too long: $n" }
                    repeat(n.toInt()) { skipTyped(elem) }
                }
                T_UINT64, T_INT64, T_FLOAT64 -> bytes(8)
                else -> { }
            }
        }
    }
}
