package desktop

import com.lagradost.cloudstream3.network.WebViewResolver
import okhttp3.Request
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop WebViewResolver must win over the library's (same class name) and keep its exact public API, or
 * plugins compiled against the library would fail to link.
 */
class WebViewResolverShadowTest {
    private val resource = "com/lagradost/cloudstream3/network/WebViewResolver.class"

    @Test
    fun theDesktopClassComesFirstOnTheClasspath() {
        val copies = javaClass.classLoader.getResources(resource).toList().map { it.toString() }
        assertTrue(copies.size >= 2, "both the compat and the library class are on the classpath: $copies")
        assertTrue("webview-cdp" in copies.first(), "compat/webview-cdp comes first: $copies")
        assertTrue(WebViewResolver::class.java.declaredFields.any { it.name == "DESKTOP_IMPLEMENTATION" })
    }

    @Test
    fun itHasTheLibrarysPublicApi() {
        val libraryJar = javaClass.classLoader.getResources(resource).toList().map { it.toString() }
            .first { "library-jvm" in it }.removePrefix("jar:").substringBefore("!/")
        // Just enough to resolve the signatures: the library, Kotlin and OkHttp.
        val deps = listOf(Regex::class.java, Request::class.java).map { it.protectionDomain.codeSource.location }
        val loader = URLClassLoader(arrayOf(URL(libraryJar)) + deps, ClassLoader.getPlatformClassLoader())
        for (name in listOf("com.lagradost.cloudstream3.network.WebViewResolver", "com.lagradost.cloudstream3.network.WebViewResolver\$Companion")) {
            val library = api(loader.loadClass(name))
            val desktop = api(Class.forName(name)) - "field DESKTOP_IMPLEMENTATION:boolean"
            assertEquals(library, desktop, name)
        }
    }

    private fun api(c: Class<*>): Set<String> = buildSet {
        c.declaredMethods.filter { Modifier.isPublic(it.modifiers) }
            .forEach { m -> add("${if (Modifier.isStatic(m.modifiers)) "static " else ""}${m.name}(${m.parameterTypes.joinToString { it.name }}):${m.returnType.name}") }
        c.declaredConstructors.filter { Modifier.isPublic(it.modifiers) }
            .forEach { k -> add("<init>(${k.parameterTypes.joinToString { it.name }})") }
        c.declaredFields.filter { Modifier.isPublic(it.modifiers) }.forEach { f -> add("field ${f.name}:${f.type.name}") }
    }
}
