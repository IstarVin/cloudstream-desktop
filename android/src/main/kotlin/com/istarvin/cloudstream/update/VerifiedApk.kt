package com.istarvin.cloudstream.update

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Streams without buffering the APK in memory; callers publish the file only after this succeeds. */
internal suspend fun copyVerifiedApk(
    input: InputStream,
    output: OutputStream,
    size: Long,
    expectedSha256: String,
    onProgress: (Long) -> Unit,
) {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var received = 0L
    var lastProgress = 0L
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(buffer)
        if (count == -1) break
        received += count
        check(received <= size) { "The downloaded APK is larger than expected." }
        output.write(buffer, 0, count)
        hash.update(buffer, 0, count)
        val now = System.nanoTime()
        if (now - lastProgress >= 100_000_000L || received == size) {
            onProgress(received)
            lastProgress = now
        }
    }
    check(received == size) { "The download was incomplete. Try again." }
    val actual = hash.digest().joinToString("") { "%02x".format(it) }
    check(actual.equals(expectedSha256, ignoreCase = true)) { "The APK checksum didn't match. Please download it again." }
}
