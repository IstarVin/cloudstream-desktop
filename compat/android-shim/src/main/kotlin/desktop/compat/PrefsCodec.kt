package desktop.compat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature

/** Each preference is stored as `{"t": type, "v": value}`, so ints, longs and floats survive a round trip. */
object PrefsCodec {
    private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    fun encode(values: Map<String, Any>): String =
        mapper.writeValueAsString(values.toSortedMap().mapValues { (_, v) -> mapOf("t" to typeOf(v), "v" to v) })

    fun decode(text: String): Map<String, Any> {
        val root = runCatching { mapper.readValue(text, Map::class.java) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Any>()
        for ((k, entry) in root) {
            val m = entry as? Map<*, *> ?: continue
            val v = m["v"] ?: continue
            out[k.toString()] = typed(m["t"]?.toString() ?: "string", v) ?: continue
        }
        return out
    }

    /** Converts a loosely typed value (from JSON or the settings form) to the stored type. */
    fun typed(type: String, v: Any): Any? = when (type) {
        "boolean" -> v as? Boolean ?: v.toString().toBooleanStrictOrNull()
        "int" -> (v as? Number)?.toInt() ?: v.toString().toIntOrNull()
        "long" -> (v as? Number)?.toLong() ?: v.toString().toLongOrNull()
        "float" -> (v as? Number)?.toFloat() ?: v.toString().toFloatOrNull()
        "stringSet" -> (v as? Collection<*>)?.map { it.toString() }?.toSet()
        else -> v.toString()
    }

    fun typeOf(v: Any): String = when (v) {
        is Boolean -> "boolean"
        is Int -> "int"
        is Long -> "long"
        is Float -> "float"
        is Set<*> -> "stringSet"
        else -> "string"
    }
}
