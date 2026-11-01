package desktop.plugins

import com.googlecode.d2j.Method
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.node.DexMethodNode
import org.objectweb.asm.MethodVisitor
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystems
import java.util.zip.ZipFile

/**
 * Turns a `.cs3` (a zip of Android dex files and `manifest.json`) into a JVM jar with dex2jar, for plugins a repo
 * publishes no jar for. The result loads like a gradle-built jar; [LinkageCheck] and the JVM verifier still apply.
 * A method dex2jar can't translate fails the whole conversion: a plugin with a hole in it is worse than none.
 */
object Cs3Converter {
    /** Bump when the conversion changes, so installed conversions are redone. */
    const val VERSION = 1
    private val DEX = Regex("""classes\d*\.dex""")

    fun convert(cs3: File, jar: File) {
        jar.delete()
        val failures = mutableListOf<String>()
        val handler = object : DexExceptionHandler {
            override fun handleFileException(e: Exception) {
                failures += "${e.javaClass.simpleName}: ${e.message}"
            }

            override fun handleMethodTranslateException(method: Method, node: DexMethodNode, mv: MethodVisitor, e: Exception) {
                failures += "${method.owner}.${method.name}: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        ZipFile(cs3).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val dexes = entries.filter { DEX.matches(it.name) }.sortedBy { it.name }
            if (dexes.isEmpty()) throw IOException("${cs3.name} has no classes.dex")
            FileSystems.newFileSystem(URI.create("jar:${jar.toURI()}"), mapOf("create" to "true")).use { fs ->
                for (dex in dexes) {
                    val bytes = zip.getInputStream(dex).use { it.readBytes() }
                    Dex2jar.from(bytes)
                        .withExceptionHandler(handler)
                        .reUseReg(false)
                        .topoLogicalSort()
                        .skipDebug(false)
                        .optimizeSynchronized(true)
                        // Kotlin names members like `constructor-impl`: legal on the JVM, and other classes link to them.
                        .dontSanitizeNames(true)
                        .to(fs.getPath("/"))
                }
                // The manifest names the entry class, as in the app.
                entries.firstOrNull { it.name == "manifest.json" }?.let { e ->
                    zip.getInputStream(e).use { java.nio.file.Files.copy(it, fs.getPath("/manifest.json")) }
                }
            }
        }
        if (failures.isNotEmpty()) {
            jar.delete()
            throw IOException("dex2jar couldn't translate ${failures.size} method(s) of ${cs3.name}, first: ${failures.first()}")
        }
    }
}
