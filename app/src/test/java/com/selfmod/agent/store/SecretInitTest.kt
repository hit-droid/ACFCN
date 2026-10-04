package com.selfmod.agent.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException

class SecretInitTest {

    @Test
    fun encryptedSucceeds() {
        val marker = Any()
        val out = SecretInit.tryInit(encryptedFactory = { marker }, plainFactory = { fail("plain should not run") })
        assertSame(marker, out.value)
        assertTrue(out.encrypted)
        assertNull(out.initIssue)
    }

    @Test
    fun encryptedThrowsFallsBackToPlain() {
        val plain = Any()
        val out = SecretInit.tryInit(
            encryptedFactory = { throw KeyStoreException("no keystore") },
            plainFactory = { plain },
        )
        assertSame(plain, out.value)
        assertFalse(out.encrypted)
        val issue: String = requireNotNull(out.initIssue) { "initIssue should be set" }
        assertTrue("issue should mention cause", issue.contains(other = "KeyStoreException"))
        assertTrue("issue should include message", issue.contains(other = "no keystore"))
    }

    @Test
    fun encryptedThrowsWithoutMessageStillReportsType() {
        val out = SecretInit.tryInit(
            encryptedFactory = { throw IOException() },
            plainFactory = { Any() },
        )
        assertFalse(out.encrypted)
        assertNotNull(out.initIssue)
        assertTrue(out.initIssue!!.contains("IOException"))
    }

    @Test
    fun bothFactoriesThrowSurfacesFallbackFailure() {
        var plainCalls = 0
        try {
            SecretInit.tryInit(
                encryptedFactory = { throw KeyStoreException("primary down") },
                plainFactory = {
                    plainCalls++
                    throw IOException("fallback down")
                },
            )
            fail("expected IllegalStateException when both factories throw")
        } catch (e: IllegalStateException) {
            assertTrue("plain factory should have been attempted", plainCalls >= 1)
            assertTrue(e.message!!.contains("primary down"))
        }
    }

    @Test
    fun plainSuccessRecordsPlainOk() {
        val out = SecretInit.tryInit(
            encryptedFactory = { throw KeyStoreException("x") },
            plainFactory = { Any() },
        )
        assertFalse(out.encrypted)
        assertNotNull(out.initIssue)
        val msg = out.initIssue!!
        assertFalse("should not say fallback also failed when plain works", msg.contains("fallback also failed"))
    }
}