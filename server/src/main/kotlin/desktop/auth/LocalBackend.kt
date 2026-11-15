package desktop.auth

import desktop.api.DeviceAuthorization
import desktop.api.DeviceRequest
import desktop.auth.Secrets.hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.SCrypt
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Sign-in without the Worker: the owner's optional password is kept here (scrypt, `owner.json`), and device codes are
 * approved from a device that is already signed in to this server. Grants live in memory; a restart drops pending ones.
 */
class LocalBackend(
    private val ownerFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /** log2 of scrypt's N; tests lower it. */
    private val cost: Int = 15,
) : AuthBackend {
    override val mode = "local"
    override val configured = true
    override val usesEpoch = false

    @Serializable private data class Owner(val salt: String, val hash: String, val n: Int, val r: Int, val p: Int)
    private class Grant(val userCode: String, val deviceName: String, val requester: String, val createdAt: Long) {
        var state = "pending"
        var lastPoll = 0L
    }

    @Volatile private var owner: Owner? = if (ownerFile.exists()) Json.decodeFromString(ownerFile.readText()) else null
    /** sha256(deviceCode) → grant. */
    private val grants = HashMap<String, Grant>()
    private val loginPerAddress = RateLimit(10, 15 * 60_000L, "Too many attempts. Try again later.", clock)
    private val loginGlobal = RateLimit(60, 15 * 60_000L, "Too many attempts. Try again later.", clock)

    val passwordSet: Boolean get() = owner != null

    @Synchronized private fun prune() {
        val now = clock()
        grants.entries.removeIf { now - it.value.createdAt >= TTL_MS }
    }

    override suspend fun startDevice(deviceName: String, requester: String, origin: String): DeviceAuthorization = synchronized(this) {
        prune()
        if (grants.size >= 50 || grants.values.count { it.requester == requester } >= 5)
            throw AuthFailure(429, "Too many pending sign-ins. Try again later.")
        var userCode: String
        do userCode = (1..6).map { ALPHABET[Secrets.random(1).toInt(16) % ALPHABET.length] }.joinToString("")
        while (grants.values.any { it.userCode == userCode })
        val deviceCode = Secrets.random()
        grants[Secrets.sha256(deviceCode)] = Grant(userCode, deviceName, requester, clock())
        DeviceAuthorization(userCode, deviceCode, "$origin/activate", (TTL_MS / 1000).toInt(), 5)
    }

    override suspend fun poll(deviceCode: String): AuthBackend.Approved? = synchronized(this) {
        prune()
        if (!deviceCode.matches(Regex("[a-f0-9]{64}"))) throw AuthFailure(400, "Invalid device secret")
        val key = Secrets.sha256(deviceCode)
        val grant = grants[key] ?: throw AuthFailure(410, "Sign-in expired. Request a new code.")
        if (clock() - grant.lastPoll < 4500) throw AuthFailure(429, "Wait five seconds before checking again")
        grant.lastPoll = clock()
        when (grant.state) {
            "pending" -> null
            "approved" -> { grants.remove(key); AuthBackend.Approved(grant.deviceName, 0) }
            else -> { grants.remove(key); throw AuthFailure(410, "Sign-in was declined. Request a new code.") }
        }
    }

    @Synchronized private fun pending(code: String): Grant {
        prune()
        val userCode = code.uppercase().replace(Regex("[\\s-]"), "")
        if (!userCode.matches(Regex("[A-HJ-NP-Z2-9]{6}"))) throw AuthFailure(400, "Enter the six-character code shown on your device")
        return grants.values.firstOrNull { it.userCode == userCode && it.state == "pending" }
            ?: throw AuthFailure(410, "Code expired or already used. Request a new code on your device.")
    }

    /** What the approver sees before deciding. */
    fun lookup(code: String): DeviceRequest = pending(code).let { DeviceRequest(it.deviceName, it.requester, it.createdAt) }

    /** Declining consumes the request too, so a rejected code can't be approved by mistake later. */
    @Synchronized fun approve(code: String, approve: Boolean) {
        pending(code).state = if (approve) "approved" else "denied"
    }

    override suspend fun login(username: String?, password: String, clientIp: String): Long {
        checkPassword(password, clientIp)
        return 0
    }

    /** Throws 401 unless [password] matches; counts against the login limits either way. */
    suspend fun checkPassword(password: String, clientIp: String) {
        loginPerAddress.check(clientIp)
        loginGlobal.check("all")
        val current = owner ?: throw AuthFailure(401, "No password is set. Approve this device from one that's signed in.")
        val hash = withContext(Dispatchers.Default) { derive(password, current.salt, current.n, current.r, current.p) }
        if (!Secrets.equal(hash, current.hash)) throw AuthFailure(401, "Incorrect password")
    }

    suspend fun setPassword(password: String) {
        if (password.length !in 12..256) throw AuthFailure(400, "Use a password of 12–256 characters.")
        val salt = Secrets.random(16)
        val n = 1 shl cost
        val next = Owner(salt, withContext(Dispatchers.Default) { derive(password, salt, n, 8, 1) }, n, 8, 1)
        synchronized(this) {
            ownerFile.parentFile?.mkdirs()
            val tmp = File(ownerFile.parentFile, "${ownerFile.name}.tmp")
            tmp.createNewFile()
            tmp.setReadable(false, false); tmp.setWritable(false, false)
            tmp.setReadable(true, true); tmp.setWritable(true, true)
            tmp.writeText(Json.encodeToString(next))
            Files.move(tmp.toPath(), ownerFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            owner = next
        }
    }

    override suspend fun epoch(): Long = 0

    private fun derive(password: String, salt: String, n: Int, r: Int, p: Int): String =
        SCrypt.generate(password.toByteArray(), salt.toByteArray(), n, r, p, 32).hex()

    companion object {
        const val TTL_MS = 300_000L
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
