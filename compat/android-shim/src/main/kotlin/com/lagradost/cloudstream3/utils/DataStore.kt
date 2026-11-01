package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import desktop.compat.DesktopContext

/**
 * The app's key/value store: Jackson-encoded JSON strings in the default preferences. Same JVM signatures as the app,
 * including the extension functions (receiver first) that reified `getKey` calls inline into plugin code.
 */
object DataStore {
    val mapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    fun Context.getSharedPrefs(): SharedPreferences = getSharedPreferences(DesktopContext.APP_PREFS, Context.MODE_PRIVATE)
    fun Context.getDefaultSharedPrefs(): SharedPreferences = getSharedPrefs()

    fun getFolderName(folder: String, path: String): String = "$folder/$path"

    fun Context.getKeys(folder: String): List<String> =
        getSharedPrefs().getAll().keys.filter { it.startsWith(folder) }

    fun Context.removeKey(folder: String, path: String) = removeKey(getFolderName(folder, path))
    fun Context.containsKey(folder: String, path: String): Boolean = containsKey(getFolderName(folder, path))
    fun Context.containsKey(path: String): Boolean = getSharedPrefs().contains(path)

    fun Context.removeKey(path: String) {
        getSharedPrefs().edit().remove(path).apply()
    }

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys("$folder/")
        val editor = getSharedPrefs().edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
        return keys.size
    }

    fun <T> Context.setKey(path: String, value: T) {
        getSharedPrefs().edit().putString(path, mapper.writeValueAsString(value)).apply()
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) = setKey(getFolderName(folder, path), value)

    fun <T> Context.getKey(path: String, valueType: Class<T>): T? = try {
        getSharedPrefs().getString(path, null)?.let { toKotlinObject(it, valueType) }
    } catch (e: Exception) {
        null
    }

    fun <T> toKotlinObject(json: String, valueType: Class<T>): T = mapper.readValue(json, valueType)

    // The app declares the rest as inline reified, so plugin code never calls them; these keep binary compatibility
    // for anything compiled against an older, non-inline shape. Without the reified type they decode generically.
    @Suppress("UNCHECKED_CAST")
    fun <T> toKotlinObject(json: String): T = mapper.readValue(json, Any::class.java) as T

    @Suppress("UNCHECKED_CAST")
    fun <T> Context.getKey(path: String, defVal: T?): T? = try {
        getSharedPrefs().getString(path, null)?.let { mapper.readValue(it, Any::class.java) as T } ?: defVal
    } catch (e: Exception) {
        defVal
    }

    fun <T> Context.getKey(path: String): T? = getKey(path, null)
    fun <T> Context.getKey(folder: String, path: String): T? = getKey(getFolderName(folder, path), null)
    fun <T> Context.getKey(folder: String, path: String, defVal: T?): T? = getKey(getFolderName(folder, path), defVal)
}
