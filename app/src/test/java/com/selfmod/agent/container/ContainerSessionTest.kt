package com.selfmod.agent.container

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Exercises the real process lifecycle of [ContainerSession] with a stand-in
 * "proot" script — the aarch64 binary itself can't run on a JVM host, but the
 * spawn / drain / watchdog / cancel machinery is architecture-independent and
 * is where the bugs would live.
 */
class ContainerSessionTest {

    private lateinit var tmp: File
    private lateinit var fakeProot: File
    private lateinit var rootfs: File
    private lateinit var workspace: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "acfcn-container-test-${System.nanoTime()}")
        tmp.mkdirs()
        fakeProot = File(tmp, "fake-proot.sh").apply {
            writeText(
                """
                #!/bin/bash
                # Mimics proot's argument surface enough for ContainerSession:
                # ignore all options, then hand /bin/sh -c <cmd> to the shell.
                while [[ $# -gt 0 ]]; do
                    case "$1" in
                        /bin/sh) shift; [[ "$1" == "-c" ]] && shift; exec /bin/bash -c "$1" ;;
                        *) shift ;;
                    esac
                done
                exit 0
                """.trimIndent()
            )
            setExecutable(true)
        }
        rootfs = File(tmp, "rootfs").apply { mkdirs() }
        workspace = File(tmp, "workspace").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun session() = ContainerSession(fakeProot, rootfs, workspace, loaderPath = null)

    @Test
    fun `echo returns OK with stdout and exit code 0`() {
        val r = session().exec("echo hello-container")
        assertEquals(ContainerSession.Result.Status.OK, r.status)
        assertEquals(0, r.exitCode)
        assertEquals("hello-container", r.stdout.trim())
        assertFalse(r.truncated)
    }

    @Test
    fun `non-zero exit is surfaced`() {
        val r = session().exec("echo out; echo err >&2; exit 3")
        assertEquals(ContainerSession.Result.Status.OK, r.status)
        assertEquals(3, r.exitCode)
        assertEquals("out", r.stdout.trim())
        assertEquals("err", r.stderr.trim())
    }

    @Test
    fun `timeout marks TIMEOUT and kills the process tree`() {
        val r = session().exec("sleep 30", timeoutMs = 400)
        assertEquals(ContainerSession.Result.Status.TIMEOUT, r.status)
        assertTrue("duration should stay near the timeout, got ${r.durationMs}", r.durationMs < 5_000)
    }

    @Test
    fun `cancel from another thread marks CANCELLED`() {
        val s = session()
        val t = Thread { Thread.sleep(300); s.cancel() }.apply { isDaemon = true; start() }
        val r = s.exec("sleep 30")
        t.join(2_000)
        assertEquals(ContainerSession.Result.Status.CANCELLED, r.status)
    }

    @Test
    fun `stdout beyond the cap is truncated but the process still completes`() {
        // 256KB cap; emit ~1MB.
        val r = session().exec("head -c 1048576 /dev/zero | tr '\\0' 'x'")
        assertEquals(ContainerSession.Result.Status.OK, r.status)
        assertTrue("stdout should be truncated", r.truncated)
        assertEquals(ContainerSession.STDOUT_CAP_BYTES, r.stdout.length)
    }

    @Test
    fun `timeout below the floor is clamped up`() {
        val r = session().exec("echo quick", timeoutMs = 1)
        assertEquals(ContainerSession.Result.Status.OK, r.status)
    }

    @Test
    fun `empty command fails to spawn`() {
        val r = session().exec("   ")
        assertEquals(ContainerSession.Result.Status.SPAWN_FAILED, r.status)
    }

    @Test
    fun `missing proot binary fails to spawn`() {
        val s = ContainerSession(File(tmp, "nonexistent"), rootfs, workspace)
        val r = s.exec("echo hi")
        assertEquals(ContainerSession.Result.Status.SPAWN_FAILED, r.status)
        assertTrue(r.stderr.isNotBlank())
    }

    @Test
    fun `env overrides reach the child process`() {
        val r = session().exec("echo \$ACFCN_TEST_VAR", env = mapOf("ACFCN_TEST_VAR" to "passed-through"))
        assertEquals(ContainerSession.Result.Status.OK, r.status)
        assertEquals("passed-through", r.stdout.trim())
    }

    @Test
    fun `cwd flag is forwarded`() {
        // The fake proot ignores --cwd, so just verify the argument doesn't break
        // the spawn path — the real binary resolves it inside the container.
        val r = session().exec("echo cwd-ok", cwd = "/workspace")
        assertEquals(ContainerSession.Result.Status.OK, r.status)
        assertEquals("cwd-ok", r.stdout.trim())
    }
}
