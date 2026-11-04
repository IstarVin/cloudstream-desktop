package desktop.app.data

import desktop.app.update.copyVerifiedApk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VerifiedApkTest {
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun streamsVerifiedBytesAndReportsCompletion() = runBlocking {
        val output = ByteArrayOutputStream()
        var progress = 0L
        copyVerifiedApk(ByteArrayInputStream(bytes), output, bytes.size.toLong(), digest.uppercase()) { progress = it }
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(bytes.size.toLong(), progress)
    }

    @Test fun rejectsTruncatedOversizedAndCorruptedDownloads() = runBlocking {
        suspend fun rejects(data: ByteArray, size: Long, checksum: String, message: String) {
            try {
                copyVerifiedApk(ByteArrayInputStream(data), ByteArrayOutputStream(), size, checksum) {}
                fail("Expected download verification to fail")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains(message))
            }
        }
        rejects(bytes.copyOf(100), bytes.size.toLong(), digest, "incomplete")
        rejects(bytes, 100, digest, "larger")
        rejects(bytes.copyOf().apply { this[0] = 1 }, bytes.size.toLong(), digest, "checksum")
    }

    @Test fun cancellationStopsCopyingBeforeTheWholeApkIsWritten() = runBlocking {
        val output = ByteArrayOutputStream()
        val job = launch {
            try {
                copyVerifiedApk(ByteArrayInputStream(bytes), output, bytes.size.toLong(), digest) { cancel() }
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                // The caller deletes the partial file when this propagates.
            }
        }
        job.join()
        assertTrue(output.size() in 1 until bytes.size)
    }
}
