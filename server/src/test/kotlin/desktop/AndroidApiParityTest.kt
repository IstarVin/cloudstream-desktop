package desktop

import desktop.plugins.AndroidStubs
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Android classes the desktop emulates (android.webkit for StreamCorner, android.net.Uri) must declare their
 * members with android.jar's exact signatures, or plugins compiled against the SDK fail to link. Members the desktop
 * leaves out simply don't exist. Skipped without an Android SDK.
 */
class AndroidApiParityTest {
    private val classes = listOf(
        "android.webkit.WebView",
        "android.webkit.WebViewClient",
        "android.webkit.WebSettings",
        "android.webkit.WebResourceRequest",
        "android.webkit.WebResourceResponse",
        "android.webkit.ValueCallback",
        "android.webkit.CookieManager",
        "android.net.Uri",
    )

    @Test
    fun emulatedMembersMatchAndroidJar() {
        val jar = AndroidStubs.findJar(null, File("/mnt/AJ/Projects/cloudstream-extensions-istarvin"))
        assumeTrue(jar != null, "No Android SDK")
        val android = URLClassLoader(arrayOf(jar!!.toURI().toURL()), ClassLoader.getPlatformClassLoader())
        fun androidType(c: Class<*>): Class<*> = if (c.isPrimitive) c else Class.forName(c.name, false, android)

        val problems = mutableListOf<String>()
        for (name in classes) {
            val ours = Class.forName(name)
            val theirs = android.loadClass(name)
            for (m in ours.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }) {
                val match = runCatching { theirs.getMethod(m.name, *m.parameterTypes.map(::androidType).toTypedArray()) }.getOrNull()
                when {
                    match == null -> problems += "$name.${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) isn't in android.jar"
                    match.returnType.name != m.returnType.name -> problems += "$name.${m.name} returns ${m.returnType.name}, android.jar ${match.returnType.name}"
                    Modifier.isStatic(match.modifiers) != Modifier.isStatic(m.modifiers) -> problems += "$name.${m.name} static mismatch"
                }
            }
            for (k in ours.declaredConstructors.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }) {
                if (runCatching { theirs.getConstructor(*k.parameterTypes.map(::androidType).toTypedArray()) }.isFailure) {
                    problems += "$name(${k.parameterTypes.joinToString { it.simpleName }}) isn't in android.jar"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
