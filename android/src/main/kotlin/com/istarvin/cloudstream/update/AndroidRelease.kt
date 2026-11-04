package com.istarvin.cloudstream.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Stable releases only. Numeric comparison avoids treating 0.10.0 as older than 0.9.0. */
internal fun versionParts(version: String): List<Int>? {
    val match = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?$").matchEntire(version) ?: return null
    return listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3].ifEmpty { "0" })
        .map { it.toIntOrNull() ?: return null }
}

internal fun compareVersions(left: String, right: String): Int {
    val a = requireNotNull(versionParts(left))
    val b = requireNotNull(versionParts(right))
    return a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0
}

@Serializable
internal data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
    val digest: String? = null,
    val state: String = "uploaded",
)

@Serializable
internal data class AndroidRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    val version: String get() = tag.removePrefix("android-v")
    fun apk(): ReleaseAsset? = assets.singleOrNull {
        it.name == "cloudstream-android-$version.apk" && it.state == "uploaded" && it.size > 0
    }
    fun isStableAndroid(): Boolean = !draft && !prerelease && tag.startsWith("android-v") &&
        Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version) && versionParts(version) != null
}

internal fun newestAndroidRelease(releases: List<AndroidRelease>): AndroidRelease? = releases
    .filter { it.isStableAndroid() && it.apk() != null }
    .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
