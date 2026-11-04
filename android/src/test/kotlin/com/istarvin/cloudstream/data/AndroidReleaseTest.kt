package com.istarvin.cloudstream.data

import com.istarvin.cloudstream.update.AndroidRelease
import com.istarvin.cloudstream.update.ReleaseAsset
import com.istarvin.cloudstream.update.compareVersions
import com.istarvin.cloudstream.update.newestAndroidRelease
import com.istarvin.cloudstream.update.versionParts
import org.junit.Assert.*
import org.junit.Test

class AndroidReleaseTest {
    private fun release(version: String) = AndroidRelease(
        tag = "android-v$version",
        assets = listOf(ReleaseAsset("cloudstream-android-$version.apk", "https://example.com/update.apk", 1024)),
    )

    @Test fun comparesVersionsNumericallyAndAcceptsTheDefaultBuildVersion() {
        assertTrue(compareVersions("0.10.0", "0.9.99") > 0)
        assertTrue(compareVersions("1.0.0", "0.99.99") > 0)
        assertEquals(0, compareVersions("0.1", "0.1.0"))
        assertNull(versionParts("0.2.0-beta.1"))
        assertNull(versionParts("999999999999.0.0"))
        assertNull(versionParts("invalid"))
    }

    @Test fun onlySelectsPublishedStableAndroidReleasesWithTheExpectedApk() {
        val stable = release("0.10.0")
        val candidates = listOf(
            release("0.9.0"), stable,
            release("8.0.0").copy(tag = "server-v8.0.0"),
            release("7.0.0").copy(draft = true),
            release("6.0.0").copy(prerelease = true),
            release("5.0.0-beta.1"),
            release("4.0.0").copy(assets = emptyList()),
            release("3.0.0").copy(assets = listOf(ReleaseAsset("other-app.apk", "https://example.com", 12))),
            release("2.0.0").copy(assets = listOf(ReleaseAsset("cloudstream-android-2.0.0.apk", "https://example.com", 0))),
        )
        assertEquals(stable, newestAndroidRelease(candidates))
        assertNull(newestAndroidRelease(candidates.drop(2)))
    }

    @Test fun readsGitHubMetadataWithNullableReleaseNotesAndDigest() {
        val releases = json.decodeFromString<List<AndroidRelease>>("""[
          {"tag_name":"server-v9.0.0", "name":"Server", "assets":[]},
          {"tag_name":"android-v0.2.0", "draft":false, "prerelease":false, "body":null,
           "assets":[{"name":"cloudstream-android-0.2.0.apk", "browser_download_url":"https://github.com/file.apk",
           "size":123, "digest":null, "state":"uploaded", "id":17}], "id":42}
        ]""")
        assertEquals("0.2.0", newestAndroidRelease(releases)?.version)
        assertNull(newestAndroidRelease(releases)?.apk()?.digest)
    }
}
