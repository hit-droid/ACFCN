package com.selfmod.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hard SSRF guard for the http tools. These cases resolve without any network
 * access (loopback / private / link-local literals parse locally, and `localhost`
 * resolves on the JVM), so the suite is deterministic in CI and offline.
 */
class HttpGuardTest {

    private fun denyReason(url: String, offline: Boolean = false): String? =
        when (val v = HttpGuard.check(url, offline)) {
            is HttpGuard.Verdict.Allow -> null
            is HttpGuard.Verdict.Deny -> v.reason
        }

    private fun isAllowed(url: String, offline: Boolean = false): Boolean =
        HttpGuard.check(url, offline) is HttpGuard.Verdict.Allow

    @Test
    fun emptyUrlDenied() {
        assertTrue(denyReason("")!!.contains("URL 为空"))
    }

    @Test
    fun nonHttpSchemeDenied() {
        assertTrue(denyReason("ftp://example.com/x")!!.contains("仅允许 http/https"))
    }

    @Test
    fun missingHostDenied() {
        assertTrue(denyReason("http:///x")!!.contains("主机名"))
    }

    @Test
    fun loopbackIpv4Denied() {
        assertFalse(isAllowed("http://127.0.0.1/foo"))
        assertTrue(denyReason("http://127.0.0.1/foo")!!.contains("回环"))
    }

    @Test
    fun ipv6LoopbackDenied() {
        assertFalse(isAllowed("http://[::1]/foo"))
        assertTrue(denyReason("http://[::1]/foo")!!.contains("回环"))
    }

    @Test
    fun cloudMetadataDenied() {
        assertFalse(isAllowed("http://169.254.169.254/latest/meta-data/"))
        assertTrue(denyReason("http://169.254.169.254/")!!.contains("链路本地"))
    }

    @Test
    fun privateRangeDeniedInOnlineMode() {
        assertFalse(isAllowed("http://192.168.1.1:11434/v1/models"))
        assertTrue(denyReason("http://192.168.1.1/")!!.contains("私有网段"))
    }

    @Test
    fun privateRangeAllowedInOfflineMode() {
        // The local LLM lives on the LAN — offline mode must permit it.
        assertTrue(isAllowed("http://192.168.1.1:11434/v1/models", offline = true))
    }

    @Test
    fun multicastDeniedInOnlineMode() {
        assertFalse(isAllowed("http://224.0.0.1/x"))
    }

    @Test
    fun localhostHostnameDenied() {
        assertFalse(isAllowed("http://localhost:8080/x"))
        assertTrue(denyReason("http://localhost:8080/x")!!.contains("回环"))
    }

    @Test
    fun allowVerdictCarriesNormalizedUrl() {
        val v = HttpGuard.check("https://10.255.255.1/x", offlineMode = true)
        assertTrue(v is HttpGuard.Verdict.Allow)
        assertEquals("https://10.255.255.1/x", (v as HttpGuard.Verdict.Allow).url)
    }
}
