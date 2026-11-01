package desktop.plugins

import java.io.DataInputStream
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Finds the references a plugin jar makes into the host's runtime that won't link: classes that don't exist and
 * methods or fields with a different signature. It reads each class file's constant pool and resolves the references
 * through the plugin's class loader, so a plugin built against a newer `pre-release` stub reports its
 * `NoSuchMethodError`s at load time instead of when someone first hits that code path.
 *
 * Only the runtime the host provides is checked (the Cloudstream library and shims, Kotlin, OkHttp, Jackson, jsoup,
 * Rhino…). Android and androidx references aren't: they only back settings screens that never run here. For the same
 * reason, classes that extend an Android class (fragments, dialogs) and their nested classes are skipped.
 */
object LinkageCheck {
    private val CHECKED = listOf(
        "com/lagradost/", "kotlin/", "kotlinx/", "okhttp3/", "okio/", "com/fasterxml/", "org/jsoup/", "org/mozilla/",
        "com/google/gson/", "me/xdrop/", "org/bouncycastle/",
    )
    private val ANDROID = listOf("android/", "androidx/", "com/google/android/")

    private class Ref(val owner: String, val name: String, val descriptor: String, val field: Boolean)

    private class ClassInfo(val name: String, val superName: String?, val classRefs: Set<String>, val refs: List<Ref>)

    /** Member descriptors per host class; the host classes are shared by every plugin. */
    private val members = ConcurrentHashMap<Class<*>, Set<String>>()

    /** Problems found in [jar], as `NoSuchMethodError: owner.name(descriptor)` lines (deduplicated, sorted). */
    fun check(jar: File, loader: ClassLoader): List<String> {
        val classes = ZipFile(jar).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                .mapNotNull { e -> runCatching { parse(zip.getInputStream(e).use { it.readBytes() }) }.getOrNull() }
                .associateBy { it.name }
        }
        val skipped = classes.values.filter { c -> c.superName != null && ANDROID.any { c.superName.startsWith(it) } }
            .map { it.name }.toSet()
        fun isSkipped(name: String) = name in skipped || skipped.any { name.startsWith("$it$") }

        val problems = sortedSetOf<String>()
        for (c in classes.values) {
            if (isSkipped(c.name)) continue
            for (ref in c.classRefs) {
                if (ref in classes || !checked(ref)) continue
                if (load(ref, loader) == null) problems += "NoClassDefFoundError: ${ref.replace('/', '.')}"
            }
            for (ref in c.refs) {
                if (ref.owner in classes || !checked(ref.owner)) continue
                val owner = load(ref.owner, loader) ?: continue // reported as a class problem above
                val known = runCatching { membersOf(owner) }.getOrElse { e ->
                    problems += "NoClassDefFoundError: ${e.message} (needed by ${owner.name})"
                    continue
                }
                if ((if (ref.field) "F" else "M") + ref.name + ref.descriptor !in known) {
                    val kind = if (ref.field) "NoSuchFieldError" else "NoSuchMethodError"
                    problems += "$kind: ${owner.name}.${ref.name}${ref.descriptor}"
                }
            }
        }
        return problems.toList()
    }

    private fun checked(internalName: String) =
        !internalName.startsWith("[") && CHECKED.any { internalName.startsWith(it) }

    private fun load(internalName: String, loader: ClassLoader): Class<*>? =
        try {
            Class.forName(internalName.replace('/', '.'), false, loader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }

    /** Every field and method (with its descriptor) visible on [cls], including inherited ones. */
    private fun membersOf(cls: Class<*>): Set<String> = members.getOrPut(cls) {
        buildSet {
            val seen = HashSet<Class<*>>()
            fun walk(c: Class<*>?) {
                if (c == null || !seen.add(c)) return
                c.declaredFields.forEach { add("F" + it.name + descriptor(it.type)) }
                c.declaredMethods.forEach { add("M" + it.name + descriptor(it)) }
                if (c === cls) c.declaredConstructors.forEach { add("M<init>" + descriptor(it)) }
                walk(if (c.isInterface) Any::class.java else c.superclass)
                c.interfaces.forEach(::walk)
            }
            walk(cls)
        }
    }

    private fun descriptor(m: Method) = "(" + m.parameterTypes.joinToString("") { descriptor(it) } + ")" + descriptor(m.returnType)
    private fun descriptor(c: Constructor<*>) = "(" + c.parameterTypes.joinToString("") { descriptor(it) } + ")V"

    fun descriptor(c: Class<*>): String = when {
        c.isArray -> "[" + descriptor(c.componentType)
        c == Void.TYPE -> "V"
        c == java.lang.Boolean.TYPE -> "Z"
        c == java.lang.Byte.TYPE -> "B"
        c == Character.TYPE -> "C"
        c == java.lang.Short.TYPE -> "S"
        c == Integer.TYPE -> "I"
        c == java.lang.Long.TYPE -> "J"
        c == java.lang.Float.TYPE -> "F"
        c == java.lang.Double.TYPE -> "D"
        else -> "L" + c.name.replace('.', '/') + ";"
    }

    /** The class name, super class and external references from a class file's constant pool. */
    private fun parse(bytes: ByteArray): ClassInfo {
        val input = DataInputStream(bytes.inputStream())
        require(input.readInt() == 0xCAFEBABE.toInt()) { "Not a class file" }
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classIndex = IntArray(count)
        val memberRefs = ArrayList<Triple<Int, Int, Int>>() // tag, class index, name-and-type index
        val nameAndType = HashMap<Int, Pair<Int, Int>>()
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[i] = input.readUTF()
                3, 4 -> input.readInt()
                5, 6 -> { input.readLong(); i++ }
                7 -> classIndex[i] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.readUnsignedShort()
                9, 10, 11 -> memberRefs += Triple(tag, input.readUnsignedShort(), input.readUnsignedShort())
                12 -> nameAndType[i] = input.readUnsignedShort() to input.readUnsignedShort()
                15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                17, 18 -> { input.readUnsignedShort(); input.readUnsignedShort() }
                else -> error("Unknown constant pool tag $tag")
            }
            i++
        }
        input.readUnsignedShort() // access flags
        fun className(index: Int) = utf8[classIndex[index]]!!
        val name = className(input.readUnsignedShort())
        val superName = input.readUnsignedShort().takeIf { it != 0 }?.let(::className)
        val classRefs = classIndex.indices.filter { classIndex[it] != 0 }.map(::className)
            .filter { !it.startsWith("[") }.toSet()
        val refs = memberRefs.map { (tag, cls, nat) ->
            val (n, d) = nameAndType.getValue(nat)
            Ref(className(cls), utf8[n]!!, utf8[d]!!, field = tag == 9)
        }
        return ClassInfo(name, superName, classRefs, refs)
    }
}
