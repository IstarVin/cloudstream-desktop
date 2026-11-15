package desktop.auth

import desktop.api.DeviceAuthorization
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Where owner credentials and device approvals live: on this server ([LocalBackend]) or in the central Worker ([WorkerBackend]). */
interface AuthBackend {
    /** `local` or `worker`; stored with the sessions so switching modes signs every device out. */
    val mode: String
    val configured: Boolean
    /** Whether sessions are tied to a remote account epoch that must be checked (see [Auth.isAuthorized]). */
    val usesEpoch: Boolean
    /** Shown on the sign-in screen so users know where approval happens. */
    val workerUrl: String get() = ""

    data class Approved(val deviceName: String, val epoch: Long)

    /** [requester] is the client's address, shown to the approver; [origin] is this server's public origin. */
    suspend fun startDevice(deviceName: String, requester: String, origin: String): DeviceAuthorization
    /** `null` while pending; throws [AuthFailure] 410 once expired, declined or consumed. */
    suspend fun poll(deviceCode: String): Approved?
    /** Returns the epoch the new session belongs to; throws [AuthFailure] 401 for wrong credentials. */
    suspend fun login(username: String?, password: String, clientIp: String): Long
    suspend fun epoch(): Long
}

class WorkerBackend(val client: WorkerClient) : AuthBackend {
    override val mode = "worker"
    override val configured get() = client.configured
    override val usesEpoch = true
    override val workerUrl get() = client.origin

    override suspend fun startDevice(deviceName: String, requester: String, origin: String): DeviceAuthorization {
        val r = client.request("device", mapOf("deviceName" to deviceName))
        return DeviceAuthorization(r["userCode"]!!.jsonPrimitive.content, r["deviceCode"]!!.jsonPrimitive.content,
            r["verificationUri"]!!.jsonPrimitive.content, r["expiresIn"]!!.jsonPrimitive.int, r["interval"]!!.jsonPrimitive.int)
    }
    override suspend fun poll(deviceCode: String): AuthBackend.Approved? {
        val r = client.request("poll", mapOf("deviceCode" to deviceCode))
        if (r["status"]?.jsonPrimitive?.content != "approved") return null
        return AuthBackend.Approved(r["deviceName"]!!.jsonPrimitive.content, r["epoch"]!!.jsonPrimitive.long)
    }
    override suspend fun login(username: String?, password: String, clientIp: String): Long {
        if (username.isNullOrEmpty()) throw AuthFailure(400, "Invalid username")
        return client.request("login", mapOf("username" to username, "password" to password, "clientIp" to clientIp))["epoch"]!!.jsonPrimitive.long
    }
    override suspend fun epoch(): Long = client.request("epoch")["epoch"]!!.jsonPrimitive.long
}
