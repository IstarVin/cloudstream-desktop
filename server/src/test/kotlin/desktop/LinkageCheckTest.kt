package desktop

import com.lagradost.cloudstream3.network.WebViewResolver
import desktop.plugins.LinkageCheck
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** A stand-in for plugin code: it calls into the Cloudstream library. */
object LinkageSample {
    fun userAgent(): String? = WebViewResolver.webViewUserAgent
}

class LinkageCheckTest {
    private val sample = "desktop/LinkageSample.class"
    private val bytes = javaClass.classLoader.getResourceAsStream(sample)!!.readBytes()

    private fun jarOf(classBytes: ByteArray): File {
        val jar = File(createTempDirectory().toFile(), "Plugin.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(sample))
            zip.write(classBytes)
            zip.closeEntry()
        }
        return jar
    }

    /** Same-length replacement in the constant pool, as if the plugin had been built against another library. */
    private fun patched(from: String, to: String): ByteArray {
        require(from.length == to.length)
        val text = String(bytes, Charsets.ISO_8859_1)
        require(from in text) { "$from not in the class file" }
        return text.replace(from, to).toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun aPluginThatMatchesTheRuntimeHasNoProblems() {
        assertEquals(emptyList(), LinkageCheck.check(jarOf(bytes), javaClass.classLoader))
    }

    @Test
    fun reportsAMethodTheRuntimeLacks() {
        val problems = LinkageCheck.check(jarOf(patched("getWebViewUserAgent", "getWebViewUserAgenX")), javaClass.classLoader)
        assertEquals(
            listOf("NoSuchMethodError: com.lagradost.cloudstream3.network.WebViewResolver\$Companion.getWebViewUserAgenX()Ljava/lang/String;"),
            problems,
        )
    }

    @Test
    fun reportsAClassTheRuntimeLacks() {
        val problems = LinkageCheck.check(jarOf(patched("network/WebViewResolver", "network/WebViewResolveX")), javaClass.classLoader)
        assertContains(problems, "NoClassDefFoundError: com.lagradost.cloudstream3.network.WebViewResolveX\$Companion")
    }
}
