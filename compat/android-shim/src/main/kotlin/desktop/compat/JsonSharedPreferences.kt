package desktop.compat

import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * SharedPreferences stored as one flat JSON object per file. Values keep Android's types (string, string set, int,
 * long, float, boolean); the file records each value's type so it reads back the same.
 *
 * It also remembers which keys the plugin asked for and with what default, so the web UI's generic settings form can
 * list keys that were never written.
 */
class JsonSharedPreferences(val file: File) : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    /** Keys read so far, with the default the plugin passed (`null` when it passed none). */
    val requestedKeys = ConcurrentHashMap<String, String>()

    init {
        if (file.exists()) values.putAll(PrefsCodec.decode(file.readText()))
    }

    private fun requested(key: String, def: Any?) {
        requestedKeys.putIfAbsent(key, def?.toString() ?: "")
    }

    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? =
        (values[key] as? String ?: defValue).also { requested(key, defValue) }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (values[key] as? Set<String> ?: defValues).also { requested(key, defValues?.joinToString(",")) }

    override fun getInt(key: String, defValue: Int): Int =
        ((values[key] as? Number)?.toInt() ?: defValue).also { requested(key, defValue) }

    override fun getLong(key: String, defValue: Long): Long =
        ((values[key] as? Number)?.toLong() ?: defValue).also { requested(key, defValue) }

    override fun getFloat(key: String, defValue: Float): Float =
        ((values[key] as? Number)?.toFloat() ?: defValue).also { requested(key, defValue) }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (values[key] as? Boolean ?: defValue).also { requested(key, defValue) }

    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    /** Replaces every value at once (the settings form saves this way). Values are typed as in [PrefsCodec]. */
    fun replaceAll(newValues: Map<String, Any>) {
        val e = edit().clear() as EditorImpl
        newValues.forEach { (k, v) -> e.pending[k] = v }
        e.commit()
    }

    private fun save() = synchronized(this) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(PrefsCodec.encode(values))
        tmp.renameTo(file)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        val pending = LinkedHashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            val changed = synchronized(this@JsonSharedPreferences) {
                val before = HashMap(values)
                if (clear) values.clear()
                pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                save()
                (before.keys + values.keys).filter { before[it] != values[it] }
            }
            changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@JsonSharedPreferences, key) } }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
