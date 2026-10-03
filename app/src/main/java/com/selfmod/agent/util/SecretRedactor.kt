package com.selfmod.agent.util

/**
 * Masks credentials that may end up in crash reports (L15).
 *
 * Uncaught exceptions can carry request headers or provider error echoes in
 * their messages (e.g. OpenAI's "Incorrect API key provided: sk-..."), and the
 * crash report is user-shareable text. Everything written into the report goes
 * through [redact] first.
 */
object SecretRedactor {

    private const val MASK = "[REDACTED]"

    // Authorization values may contain spaces ("Bearer abc def") -> whole line.
    private val authorization = Regex(
        """(?i)(\bauthorization["']?\s*[:=]\s*)[^\r\n]+""",
    )

    // api key pairs: single token after "api_key=" / "x-api-key:" ...
    private val apiKeyField = Regex(
        """(?i)(\bapi[-_ ]?key["']?\s*[:=]\s*)[^\s,;&'"’]+""",
    )

    // Bearer <token>
    private val bearer = Regex("""(?i)(\bbearer\s+)[A-Za-z0-9._\-]{8,}""")

    // Provider-prefixed keys: sk-, sk_, gsk_, ghp_, gho_, glpat-, xoxb- ...
    private val prefixed = Regex("""(?i)\b(?:sk|gsk|ghp|gho|glpat|xoxb)[-_][A-Za-z0-9_\-]{7,}""")

    // Google API keys carry no separator prefix.
    private val google = Regex("""\bAIza[A-Za-z0-9_\-]{7,}""")

    fun redact(text: String): String {
        var out = authorization.replace(text) { it.groupValues[1] + MASK }
        out = apiKeyField.replace(out) { it.groupValues[1] + MASK }
        out = bearer.replace(out) { it.groupValues[1] + MASK }
        out = prefixed.replace(out, MASK)
        return google.replace(out, MASK)
    }
}
