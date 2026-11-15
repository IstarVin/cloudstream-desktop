package desktop.auth

import java.security.MessageDigest
import java.security.SecureRandom

internal object Secrets {
    private val rng = SecureRandom()
    fun random(bytes: Int = 32): String = ByteArray(bytes).also(rng::nextBytes).hex()
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).hex()
    fun equal(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

/** Fixed-window counter per key. A full table refuses only new keys, so one noisy client can't lock out known ones. */
class RateLimit(
    private val max: Int,
    private val windowMs: Long,
    private val message: String = "Too many requests. Try again in a minute.",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = HashMap<String, Pair<Long, Int>>()
    @Synchronized fun check(key: String) {
        val now = clock()
        entries.entries.removeIf { now - it.value.first >= windowMs }
        val previous = entries[key]
        if ((previous == null && entries.size >= 4096) || (previous?.second ?: 0) >= max) throw AuthFailure(429, message)
        entries[key] = (previous?.first ?: now) to (previous?.second ?: 0) + 1
    }
}
