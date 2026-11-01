package android.net

import java.net.URI
import java.net.URLDecoder

/**
 * The part of `android.net.Uri` plugins read: what `WebResourceRequest.getUrl()` returns, and `Uri.parse` in
 * settings links. Backed by java.net.URI; a string java.net.URI rejects still round-trips through [toString].
 */
abstract class Uri private constructor() {
    abstract fun getScheme(): String?
    abstract fun getHost(): String?
    abstract fun getPort(): Int
    abstract fun getPath(): String?
    abstract fun getQuery(): String?
    abstract fun getFragment(): String?
    abstract fun getPathSegments(): List<String>
    abstract override fun toString(): String

    fun getLastPathSegment(): String? = getPathSegments().lastOrNull()

    fun getQueryParameter(key: String): String? = getQueryParameters(key).firstOrNull()

    fun getQueryParameters(key: String): List<String> =
        pairs().filter { it.first == key }.map { it.second }

    fun getQueryParameterNames(): Set<String> = pairs().mapTo(LinkedHashSet()) { it.first }

    override fun equals(other: Any?) = other is Uri && other.toString() == toString()
    override fun hashCode() = toString().hashCode()

    private fun pairs(): List<Pair<String, String>> =
        runCatching { URI(toString()).rawQuery }.getOrNull()?.split('&')?.filter { it.isNotEmpty() }
            ?.map { p -> p.split('=', limit = 2).let { decode(it[0]) to decode(it.getOrElse(1) { "" }) } }.orEmpty()

    private class StringUri(private val value: String) : Uri() {
        private val javaURI: URI? = runCatching { URI(value) }.getOrNull()
        override fun getScheme() = javaURI?.scheme
        override fun getHost() = javaURI?.host
        override fun getPort() = javaURI?.port ?: -1
        override fun getPath() = javaURI?.path
        override fun getQuery() = javaURI?.query
        override fun getFragment() = javaURI?.fragment
        override fun getPathSegments() = getPath()?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        override fun toString() = value
    }

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri = StringUri(uriString)

        private fun decode(s: String) = URLDecoder.decode(s, Charsets.UTF_8)
    }
}
