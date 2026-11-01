package desktop.config

/**
 * Reads the subset of TOML `config.toml` uses: `[tables]`, `key = value` with dotted keys, strings, integers,
 * floats, booleans, and arrays of those (arrays may span lines). Returns a flat map keyed by the full dotted path.
 */
object Toml {
    fun parse(text: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        var table = ""
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            var line = stripComment(lines[i]).trim()
            i++
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                table = line.trim('[', ']').trim()
                continue
            }
            val eq = line.indexOf('=')
            require(eq > 0) { "config.toml line $i: expected key = value" }
            // A multi-line array continues until its brackets balance.
            while (line.count { it == '[' } > line.count { it == ']' } && i < lines.size) {
                line += " " + stripComment(lines[i]).trim()
                i++
            }
            val key = line.substring(0, eq).trim().split('.').joinToString(".") { it.trim().trim('"') }
            val full = if (table.isEmpty()) key else "$table.$key"
            out[full] = value(line.substring(eq + 1).trim(), i)
        }
        return out
    }

    private fun stripComment(line: String): String {
        var inString: Char? = null
        for ((idx, c) in line.withIndex()) {
            when {
                inString != null && c == '\\' && inString == '"' -> continue
                inString != null && c == inString && (idx == 0 || line[idx - 1] != '\\') -> inString = null
                inString == null && (c == '"' || c == '\'') -> inString = c
                inString == null && c == '#' -> return line.substring(0, idx)
            }
        }
        return line
    }

    private fun value(raw: String, line: Int): Any = when {
        raw.startsWith("[") -> splitArray(raw.removePrefix("[").removeSuffix("]")).map { value(it, line) }
        raw.startsWith("\"") -> unescape(raw.substring(1, raw.lastIndexOf('"')))
        raw.startsWith("'") -> raw.substring(1, raw.lastIndexOf('\''))
        raw == "true" -> true
        raw == "false" -> false
        else -> raw.replace("_", "").let { it.toLongOrNull() ?: it.toDoubleOrNull() }
            ?: error("config.toml line $line: can't read value `$raw`")
    }

    private fun splitArray(body: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var inString: Char? = null
        var depth = 0
        for (c in body) {
            when {
                inString != null -> { cur.append(c); if (c == inString) inString = null }
                c == '"' || c == '\'' -> { cur.append(c); inString = c }
                c == '[' -> { depth++; cur.append(c) }
                c == ']' -> { depth--; cur.append(c) }
                c == ',' && depth == 0 -> { parts.add(cur.toString().trim()); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) parts.add(cur.toString().trim())
        return parts.filter { it.isNotEmpty() }
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                when (s[i]) {
                    'n' -> append('\n'); 't' -> append('\t'); 'r' -> append('\r')
                    '"' -> append('"'); '\\' -> append('\\')
                    'u' -> { append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> append(s[i])
                }
            } else append(c)
            i++
        }
    }
}
