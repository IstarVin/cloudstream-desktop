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
    @Volatile private var repoName = "Test Repo"
    @Volatile private var repoIcon = "images/icon.png"
    @Volatile private var pluginList = "builds/plugins.json"

    /** Published as a .cs3 only, like the repo's non-cross-platform plugins. */
    private val kissKh = javaClass.classLoader.getResource("KissKH.cs3")!!.readBytes()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val server = LocalServer { ex ->
        val path = ex.requestURI.path
        when {
            path == "/short" -> ex.respond(302, "", headers = mapOf("Location" to "/nested/repo.json"))
            path == "/nested/repo.json" -> ex.respond(200, """{"name":"Redirected","iconUrl":"icon.png","pluginLists":["plugins.json"]}""", "application/json")
            path == "/nested/plugins.json" -> ex.respond(302, "", headers = mapOf("Location" to "/builds/plugins.json"))
            path == "/repo.json" -> ex.respond(200, """{"name":"$repoName","iconUrl":"$repoIcon","pluginLists":["$pluginList"]}""", "application/json")
            path == "/new/plugins.json" -> ex.respond(200, """[{"internalName":"NewPlugin","version":1}]""", "application/json")
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
    private fun jarFiles() = File(dir, "jars").walk().filter { it.isFile }.map { it.name }.sorted().toList()
    private fun id(name: String, repo: String = "${server.url}/repo.json") = RepoSource.idOf(name, repo)

    @AfterTest
    fun close() = server.close()

    @Test
    fun followsRedirectsAndResolvesRelativeUrlsFromTheirDestinations() = runBlocking {
        val repo = RepoSource("${server.url}/short", dir)
        val prepared = repo.resolveRepository("${server.url}/short")
        assertEquals("${server.url}/nested/repo.json", prepared.url)
        val resolvedRepo = RepoSource(prepared.url, createTempDirectory().toFile())
        resolvedRepo.addPrepared(prepared)
        assertEquals("${server.url}/nested/repo.json", resolvedRepo.available("Dulo")!!.repo)
        repo.refresh()
        assertEquals("Redirected", repo.repoName)
        assertEquals("${server.url}/nested/icon.png", repo.repos().single().iconUrl)
        assertEquals("${server.url}/builds/Dulo-1.jar", repo.available("Dulo")!!.jarUrl)
    }

    @Test
    fun readsARepoJsonAndResolvesRelativeUrls() = runBlocking {
        val repo = source()
        repo.refresh()
        assertEquals("Test Repo", repo.repoName)
        assertEquals("${server.url}/images/icon.png", repo.repos().single().iconUrl)
        assertEquals("${server.url}/images/icon.png", source().repos().single().iconUrl, "icon survives restart")
        assertEquals("${server.url}/builds/Dulo-1.jar", repo.available("Dulo")!!.jarUrl)
        assertEquals(listOf("Dulo", "Kayoanime", "KissKH"), repo.discover().map { it.name })
        assertTrue("neither a jar nor a .cs3" in repo.missingReason("Kayoanime"))
        assertFailsWith<Exception> { repo.install("Kayoanime") }
        Unit
    }

    @Test
    fun refreshRefetchesRepositoryMetadataAndPluginLists() = runBlocking {
        val repo = source()
        repo.refresh()
        repoName = "Updated Repo"
        repoIcon = "images/new.png"
        pluginList = "new/plugins.json"

        repo.refresh()

        assertEquals("Updated Repo", repo.repos().single().name)
        assertEquals("${server.url}/images/new.png", repo.repos().single().iconUrl)
        assertEquals(listOf("NewPlugin"), repo.discover().map { it.name })
        val restored = source()
        assertEquals("Updated Repo", restored.repos().single().name)
        assertEquals("${server.url}/images/new.png", restored.repos().single().iconUrl)
        assertEquals(listOf("NewPlugin"), restored.discover().map { it.name })
    }

    @Test
    fun keepsSameNamedPluginsFromEachRepoAndSurvivesOneFailing() = runBlocking {
        val a = "${server.url}/repo.json"
        val b = "${server.url}/repo.json?again"
        val repo = RepoSource(listOf("${server.url}/missing.json", a, b), dir)
        repo.refresh()
        val found = repo.discover()
        assertEquals(listOf("Dulo", "Dulo", "Kayoanime", "Kayoanime", "KissKH", "KissKH"), found.map { it.name }.sorted())
        assertEquals(6, found.map { it.id }.toSet().size, "every repo's copy has its own id")
        assertEquals(a, repo.available("Dulo")!!.repo, "a bare name means the earliest repo's copy")
        assertTrue("missing.json" in repo.error!!)
        assertTrue(repo.repos().first().error != null)
        assertEquals("Test Repo", repo.repos()[1].name)

        // Both copies install, each into its repo's folder.
        repo.install(listOf(id("Dulo", a), id("Dulo", b)))
        val jarA = repo.jar(id("Dulo", a))!!
        val jarB = repo.jar(id("Dulo", b))!!
        assertTrue(jarA != jarB && jarA.exists() && jarB.exists())
        assertEquals(RepoSource.repoKey(a), jarA.parentFile.name)
        Unit
    }

    @Test
    fun sameNamedProvidersAreToldApartByRepo() {
        assertEquals(
            listOf("Dulo", "KissKH", "Dulo (Other Repo)", "Dulo (Other Repo) 2"),
            PluginHost.providerIds(listOf("Dulo" to "Main", "KissKH" to "Main", "Dulo" to "Other Repo", "Dulo" to "Other Repo")),
        )
    }

    @Test
    fun movesPluginsSavedByNameToTheirRepo() = runBlocking {
        source().apply { refresh(); install("Dulo") }
        // As saved before plugins were kept per repo: keyed by name, entries without a repo.
        val state = File(dir, "state.json")
        state.writeText(state.readText().replace("\"${id("Dulo")}\"", "\"Dulo\"").replace(Regex(",?\"repo\":\"[^\"]*\""), ""))
        val again = source()
        assertEquals("jar v1", again.jar(id("Dulo"))!!.readText())
        assertEquals(setOf(id("Dulo"), id("Kayoanime"), id("KissKH")), again.discover().map { it.id }.toSet())
        Unit
    }

    @Test
    fun removingARepoClearsItsPlugins() = runBlocking {
        val a = "${server.url}/repo.json"
        val b = "${server.url}/repo.json?second"
        val both = RepoSource(listOf(a, b), dir)
        both.refresh()
        both.install(listOf("Dulo", "KissKH"))
        val jar = both.jar("Dulo")!!
        assertTrue(jar.exists())

        // Deleting the repo that provided them drops its index and jars; reopening the folder sees the same.
        val none = RepoSource(emptyList(), dir)
        assertNull(none.installed("Dulo"))
        assertNull(none.jar("Dulo"))
        assertTrue(!jar.exists())
        assertTrue(none.discover().isEmpty())
        none.refresh()

        // Other repos' plugins stay.
        val again = RepoSource(listOf(a), dir)
        again.refresh()
        again.install("Dulo")
        val kept = RepoSource(listOf(a, b), dir)
        assertNotNull(kept.jar("Dulo"))
        val other = RepoSource(listOf(b), dir)
        assertNull(other.jar("Dulo"))
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
        val since = repo.installed("Dulo")!!.since
        assertEquals(repo.installed("Dulo")!!.current.installedAt, since)

        published = 2
        repo.refresh()
        assertEquals(listOf(id("Dulo")), repo.updates())
        assertEquals(1, repo.discover().first { it.name == "Dulo" }.version, "metadata follows what's installed")
        repo.install("Dulo")
        assertEquals("jar v2", repo.jar("Dulo")!!.readText())
        assertEquals(listOf(1), repo.installed("Dulo")!!.previous.map { it.entry.version })
        assertEquals(since, repo.installed("Dulo")!!.since, "an update keeps the first install time")

        repo.rollback("Dulo")
        assertEquals("jar v1", repo.jar("Dulo")!!.readText())
        assertEquals(since, repo.installed("Dulo")!!.since, "so does a rollback")
        assertEquals(listOf(id("Dulo")), repo.updates(), "the rolled-back version is on offer again")
        assertFailsWith<IllegalArgumentException> { repo.rollback("Dulo") }
        Unit
    }

    @Test
    fun keepsOnlyTwoPreviousVersions() = runBlocking {
        val repo = source()
        var first = 0L
        for (v in 1..4) {
            published = v
            repo.refresh()
            repo.install("Dulo")
            if (v == 1) first = repo.installed("Dulo")!!.current.installedAt
        }
        assertEquals(listOf(3, 2), repo.installed("Dulo")!!.previous.map { it.entry.version })
        assertEquals(first, repo.installed("Dulo")!!.since, "the first install time outlives the v1 jar")
        val files = jarFiles()
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
        assertEquals(emptyList(), jarFiles(), "no partial file is left behind")
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
        assertEquals(emptyList(), jarFiles().filter { it.endsWith(".cs3") }, "only the jar is kept")

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
