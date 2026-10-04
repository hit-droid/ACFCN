package com.selfmod.agent.store

data class InitOutcome<T>(
    val value: T,
    val encrypted: Boolean,
    val initIssue: String?,
)

object SecretInit {
    fun <T : Any> tryInit(
        encryptedFactory: () -> T,
        plainFactory: () -> T,
    ): InitOutcome<T> {
        return try {
            InitOutcome(value = encryptedFactory(), encrypted = true, initIssue = null)
        } catch (encryptedError: Throwable) {
            val plainResult = runCatching { plainFactory() }
            val value: T = plainResult.getOrElse {
                throw IllegalStateException(
                    "encrypted+plain both failed: $encryptedError",
                    it,
                )
            }
            val plainOk = plainResult.isSuccess
            InitOutcome(
                value = value,
                encrypted = false,
                initIssue = describe(encryptedError, plainOk),
            )
        }
    }

    private fun describe(e: Throwable, plainOk: Boolean): String {
        val type = e::class.java.simpleName
        val msg = e.message?.takeIf { it.isNotBlank() }
        val detail = if (msg != null) "$type: $msg" else type
        return if (plainOk) detail else "$detail (fallback also failed)"
    }
}