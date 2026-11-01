package desktop

import desktop.plugins.Cs3Converter
import desktop.plugins.PluginHost
import desktop.plugins.RepoSource
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepoSourceTest {
    /** Jar bytes by version; the repo serves whatever [published] says, so tests can bump versions. */
    private val jars = (1..4).associateWith { "jar v$it".toByteArray() }
    @Volatile private var published = 1
    @Volatile private var badHash = false

    /** Published as a .cs3 only, like the repo's non-cross-platform plugins. */
    private val kissKh = javaClass.classLoader.getResource("KissKH.cs3")!!.readBytes()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val server = LocalServer { ex ->
        val path = ex.requestURI.path
        when {
            path == "/repo.json" -> ex.respond(200, """{"name":"Test Repo","pluginLists":["builds/plugins.json"]}""", "application/json")
            path == "/builds/plugins.json" -> {
                val jar = jars.getValue(published)
                val hash = if (badHash) sha("other".toByteArray()) else sha(jar)
                ex.respond(
                    200,
                    """[
                      {"internalName":"Dulo","version":$published,"tvTypes":["Movie"],"status":1,"unknownField":true,
                       "jarUrl":"Dulo-$published.jar","jarHash":"sha256-$hash","jarFileSize":${jar.size}},
                      {"internalName":"Kayoanime","version":2,"tvTypes":["Anime"]},
                      {"internalName":"KissKH","version":2,"url":"KissKH.cs3","fileHash":"sha256-${sha(kissKh)}","fileSize":${kissKh.size}}
                    ]""",
                    "application/json",
                )
            }
            path == "/builds/KissKH.cs3" -> ex.respond(200, kissKh, "application/octet-stream")
            path.startsWith("/builds/Dulo-") -> ex.respond(200, jars.getValue(path.substringAfter("Dulo-").removeSuffix(".jar").toInt()), "application/java-archive")
            else -> ex.respond(404, "")
        }
    }
    private val dir: File = createTempDirectory().toFile()
    private fun source() = RepoSource("${server.url}/repo.json", dir)

    @AfterTest
    fun close() = server.close()

    @Test
    fun readsARepoJsonAndResolvesRelativeUrls() = runBlocking {
        val repo = source()
        repo.refresh()
        assertEquals("Test Repo", repo.repoName)
        assertEquals("${server.url}/builds/Dulo-1.jar", repo.available("Dulo")!!.jarUrl)
        assertEquals(listOf("Dulo", "Kayoanime", "KissKH"), repo.discover().map { it.name })
        assertTrue("neither a jar nor a .cs3" in repo.missingReason("Kayoanime"))
        assertFailsWith<Exception> { repo.install("Kayoanime") }
        Unit
    }

    @Test
    fun installsVerifiedJarsAndRollsBack() = runBlocking {
        val repo = source()
        repo.refresh()
        assertNull(repo.jar("Dulo"))
        repo.install("Dulo")
        assertEquals("jar v1", repo.jar("Dulo")!!.readText())
        assertEquals(emptyList(), repo.updates())

        published = 2
        repo.refresh()
        assertEquals(listOf("Dulo"), repo.updates())
        assertEquals(1, repo.discover().first { it.name == "Dulo" }.version, "metadata follows what's installed")
        repo.install("Dulo")
        assertEquals("jar v2", repo.jar("Dulo")!!.readText())
        assertEquals(listOf(1), repo.installed("Dulo")!!.previous.map { it.entry.version })

        repo.rollback("Dulo")
        assertEquals("jar v1", repo.jar("Dulo")!!.readText())
        assertEquals(listOf("Dulo"), repo.updates(), "the rolled-back version is on offer again")
        assertFailsWith<IllegalArgumentException> { repo.rollback("Dulo") }
        Unit
    }

    @Test
    fun keepsOnlyTwoPreviousVersions() = runBlocking {
        val repo = source()
        for (v in 1..4) {
            published = v
            repo.refresh()
            repo.install("Dulo")
        }
        assertEquals(listOf(3, 2), repo.installed("Dulo")!!.previous.map { it.entry.version })
        val files = File(dir, "jars").list()!!.sorted()
        assertEquals(3, files.size, "older jars are deleted: $files")
    }

    @Test
    fun rejectsAJarThatDoesNotMatchItsHash() = runBlocking {
        badHash = true
        val repo = source()
        repo.refresh()
        val e = assertFailsWith<Exception> { repo.install("Dulo") }
        assertTrue("hash" in e.message!!, e.message)
        assertNull(repo.jar("Dulo"))
        assertTrue("Install failed" in repo.missingReason("Dulo"))
        assertEquals(emptyList(), File(dir, "jars").list()!!.toList(), "no partial file is left behind")
    }

    @Test
    fun stateSurvivesARestartAndAnUnreachableRepo() = runBlocking {
        source().apply { refresh(); install("Dulo") }
        server.close()
        val again = source()
        assertEquals("jar v1", again.jar("Dulo")!!.readText())
        assertEquals(listOf("Dulo", "Kayoanime", "KissKH"), again.discover().map { it.name })
        assertFailsWith<Exception> { again.refresh() }
        assertNotNull(again.error)
        assertTrue(again.error!!.startsWith("Couldn't check for updates"), again.error)
        assertEquals("jar v1", again.jar("Dulo")!!.readText(), "a failed refresh keeps what's installed")
    }

    @Test
    fun convertsACs3AndRedoesConversionsFromAnOlderConverter() = runBlocking {
        val repo = source()
        repo.refresh()
        repo.install("KissKH")
        val jar = repo.jar("KissKH")!!
        assertTrue(jar.name.endsWith("-converted.jar"), jar.name)
        assertNotNull(PluginHost.findEntryClass(jar))
        assertTrue(repo.installed("KissKH")!!.current.converted)
        assertEquals(emptyList(), File(dir, "jars").list()!!.filter { it.endsWith(".cs3") }, "only the jar is kept")

        // As if an older converter had made it.
        val state = File(dir, "state.json")
        state.writeText(state.readText().replace("\"converter\":${Cs3Converter.VERSION}", "\"converter\":0"))
        val again = source()
        assertTrue(again.staleConversion("KissKH"))
        again.install("KissKH")
        assertEquals(Cs3Converter.VERSION, again.installed("KissKH")!!.current.converter)
        assertTrue(again.jar("KissKH")!!.exists())
        assertEquals(emptyList(), again.installed("KissKH")!!.previous, "a reconversion isn't a rollback target")
    }
}
