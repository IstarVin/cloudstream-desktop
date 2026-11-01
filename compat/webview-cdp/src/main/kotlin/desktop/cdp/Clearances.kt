package desktop.cdp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cookies that passed a browser check, per host, plus the user agent they were issued to. Saved to disk so a restart
 * doesn't mean solving every challenge again.
 *
 * It is also the map behind every `CloudflareKiller.savedCookies`: on Android each instance keeps its own map but
 * falls back to the WebView's global cookie jar, so sharing one map behaves the same. Plugins drop an expired
 * clearance with `savedCookies.remove(host)`, which removes it here too.
 */
object Clearances {
    @Volatile private var file: File? = null

    /** The user agent the saved cookies belong to (Cloudflare ties `cf_clearance` to it). */
    @Volatile var userAgent: String? = null
        set(value) {
            if (field == value) return
            field = value
            save()
        }

    val hosts: MutableMap<String, Map<String, String>> = object : ConcurrentHashMap<String, Map<String, String>>() {
        override fun put(key: String, value: Map<String, String>): Map<String, String>? = super.put(key, value).also { save() }
        override fun remove(key: String): Map<String, String>? = super.remove(key).also { save() }
        override fun clear() = super.clear().also { save() }
    }

    fun load(file: File) {
        this.file = null
        hosts.clear()
        runCatching {
            if (file.exists()) {
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                userAgent = root["userAgent"]?.jsonPrimitive?.contentOrNull
                root["hosts"]?.jsonObject?.forEach { (host, cookies) ->
                    hosts[host] = cookies.jsonObject.mapValues { it.value.jsonPrimitive.content }
                }
            }
        }
        this.file = file
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        val root = JsonObject(buildMap {
            userAgent?.let { put("userAgent", JsonPrimitive(it)) }
            put("hosts", JsonObject(hosts.mapValues { (_, c) -> JsonObject(c.mapValues { JsonPrimitive(it.value) }) }))
        })
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(f)
    }
}
