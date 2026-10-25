package com.selfmod.agent.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class GgufHeaderTest {

    private class Buf {
        private val out = ByteArrayOutputStream()
        fun u32(v: Int) = apply { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun u64(v: Long) = apply { out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()) }
        fun str(s: String) = apply {
            val b = s.toByteArray(StandardCharsets.UTF_8)
            u64(b.size.toLong()); out.write(b)
        }
        fun raw(b: ByteArray) = apply { out.write(b) }
        fun toBytes(): ByteArray = out.toByteArray()
    }

    private fun header(vararg kvs: Pair<String, () -> Unit>): ByteArray {
        val b = Buf()
        b.raw(byteArrayOf(0x47, 0x47, 0x55, 0x46)) // "GGUF"
        b.u32(3)
        b.u64(0L)
        b.u64(kvs.size.toLong())
        kvs.forEach { (k, _) -> b.str(k) }
        return b.toBytes()
    }

    @Test
    fun parsesVersionAndTensorCount() {
        val b = Buf()
        b.raw(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        b.u32(3)
        b.u64(42L)
        b.u64(0L)
        val info = GgufHeader.parse(b.toBytes().inputStream())
        assertNotNull(info)
        assertEquals(3, info!!.version)
        assertEquals(42L, info.tensorCount)
    }

    @Test
    fun readsStringMetadata() {
        val b = Buf()
        b.raw(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        b.u32(3)
        b.u64(0L)
        b.u64(1L)
        b.str("general.architecture")
        b.u32(8) // STRING
        b.str("llama")
        val info = GgufHeader.parse(b.toBytes().inputStream())!!
        assertEquals("llama", info.architecture)
        assertTrue(info.summary().contains("llama"))
    }

    @Test
    fun skipsArrayMetadataWithoutBreakingStream() {
        // key1 = array<string>[2], key2 = float32 -> ensures arrays don't desync parsing.
        val b = Buf()
        b.raw(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        b.u32(3)
        b.u64(0L)
        b.u64(2L)
        b.str("tokenizer.ggml.tokens")
        b.u32(9) // ARRAY
        b.u32(8) // of STRING
        b.u64(2L)
        b.str("a"); b.str("b")
        b.str("llama.context_length")
        b.u32(4) // UINT32
        b.u32(4096)

        val info = GgufHeader.parse(b.toBytes().inputStream())!!
        assertEquals(4096L, info.contextLength)
        assertEquals("[2]", info.extra["tokenizer.ggml.tokens"])
    }

    @Test
    fun rejectsNonGguf() {
        assertNull(GgufHeader.parse("hello".toByteArray().inputStream()))
    }

    @Test
    fun readsQuantLabelFromFileType() {
        val b = Buf()
        b.raw(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        b.u32(3)
        b.u64(0L)
        b.u64(1L)
        b.str("general.file_type")
        b.u32(4) // UINT32
        b.u32(15) // Q4_K_M
        val info = GgufHeader.parse(b.toBytes().inputStream())!!
        assertEquals("Q4_K_M", info.quant)
    }

    @Test(expected = IOException::class)
    fun truncatedThrowsViaStream() {
        val b = Buf()
        b.raw(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        b.u32(3)
        b.u64(0L)
        b.u64(2L) // promises 2 kv but provides none
        val bad: InputStream = b.toBytes().inputStream()
        GgufHeader.parseOrThrow(bad)
    }

    // --- KV cache sizing -----------------------------------------------------

    private fun tempGguf(bytes: ByteArray): File =
        File.createTempFile("gguf", ".bin").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    private fun start(kvCount: Long): Buf = Buf().apply {
        raw(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        u32(3)
        u64(0L)
        u64(kvCount)
    }

    @Test
    fun computesKvBytesPerTokenFromShape() {
        // Qwen3-4B: 36 layers, 8 KV heads, head_dim 128, q8_0 KV (1 byte/elem).
        val b = start(5)
        b.str("general.architecture"); b.u32(8); b.str("qwen3")
        b.str("qwen3.block_count"); b.u32(4); b.u32(36)
        b.str("qwen3.attention.head_count"); b.u32(4); b.u32(32)
        b.str("qwen3.attention.head_count_kv"); b.u32(4); b.u32(8)
        b.str("qwen3.attention.key_length"); b.u32(4); b.u32(128)

        assertEquals(36L * 8 * (128 + 128), GgufHeader.kvBytesPerToken(tempGguf(b.toBytes())))
    }

    @Test
    fun derivesKeyLengthFromEmbeddingWhenAbsent() {
        // llama-style header: no key_length/value_length, no head_count_kv.
        val b = start(4)
        b.str("general.architecture"); b.u32(8); b.str("llama")
        b.str("llama.block_count"); b.u32(4); b.u32(22)
        b.str("llama.attention.head_count"); b.u32(4); b.u32(32)
        b.str("llama.embedding_length"); b.u32(4); b.u32(4096)

        assertEquals(22L * 32 * (128 + 128), GgufHeader.kvBytesPerToken(tempGguf(b.toBytes())))
    }

    @Test
    fun usesLargestHeadCountWhenKvHeadsIsAnArray() {
        val b = start(4)
        b.str("general.architecture"); b.u32(8); b.str("gemma3")
        b.str("gemma3.block_count"); b.u32(4); b.u32(26)
        b.str("gemma3.attention.head_count_kv"); b.u32(9); b.u32(4); b.u64(2); b.u32(4); b.u32(8)
        b.str("gemma3.attention.key_length"); b.u32(4); b.u32(256)

        assertEquals(26L * 8 * (256 + 256), GgufHeader.kvBytesPerToken(tempGguf(b.toBytes())))
    }

    @Test
    fun stopsBeforeTokenizerArrays() {
        // Shape first, then a huge array that is never walked.
        val b = start(5)
        b.str("general.architecture"); b.u32(8); b.str("qwen3")
        b.str("qwen3.block_count"); b.u32(4); b.u32(36)
        b.str("qwen3.attention.head_count_kv"); b.u32(4); b.u32(8)
        b.str("qwen3.attention.key_length"); b.u32(4); b.u32(128)
        b.str("tokenizer.ggml.tokens"); b.u32(9); b.u32(8); b.u64(500_000L)

        assertEquals(36L * 8 * (128 + 128), GgufHeader.kvBytesPerToken(tempGguf(b.toBytes())))
    }

    @Test
    fun kvIsZeroForNonGguf() {
        assertEquals(0L, GgufHeader.kvBytesPerToken(tempGguf("hello".toByteArray())))
    }
}
