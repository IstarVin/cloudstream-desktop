package desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.plugins.BasePlugin
import desktop.plugins.Cs3Converter
import desktop.plugins.LinkageCheck
import desktop.plugins.PluginHost
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Cs3ConverterTest {
    /** KissKH v2 from the extensions repo's builds branch: published as a .cs3 only (it isn't `isCrossPlatform`). */
    private val cs3 = File(javaClass.classLoader.getResource("KissKH.cs3")!!.toURI())

    @Test
    fun convertedPluginLinksLoadsAndRegistersItsProvider() {
        val jar = File(createTempDirectory().toFile(), "KissKH.jar")
        Cs3Converter.convert(cs3, jar)
        ZipFile(jar).use { zip -> assertNotNull(zip.getEntry("manifest.json"), "the manifest is carried over") }

        val entry = assertNotNull(PluginHost.findEntryClass(jar))
        URLClassLoader(arrayOf(jar.toURI().toURL()), javaClass.classLoader).use { loader ->
            assertEquals(emptyList(), LinkageCheck.check(jar, loader))
            val plugin = loader.loadClass(entry).getDeclaredConstructor().newInstance() as BasePlugin
            plugin.filename = jar.absolutePath
            plugin.load()
            val apis = APIHolder.allProviders.filter { it.sourcePlugin == jar.absolutePath }
            assertTrue(apis.any { it.name.contains("KissKH", ignoreCase = true) }, apis.map { it.name }.toString())
            apis.forEach(APIHolder.allProviders::remove)
        }
    }
}
