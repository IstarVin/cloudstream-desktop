package com.lagradost.cloudstream3

import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import desktop.compat.DesktopContext

/** The app's static key/value helpers, backed by the server's preferences directory. */
class CloudStreamApp {
    companion object {
        val context: Context? get() = DesktopContext.app

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? = context?.getKey(path, valueType)
        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }
        fun removeKeys(folder: String): Int? = context?.removeKeys(folder)
        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }
        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }
        fun <T> getKey(path: String, defVal: T?): T? = context?.getKey(path, defVal)
        fun <T> getKey(path: String): T? = context?.getKey(path)
        fun <T> getKey(folder: String, path: String): T? = context?.getKey(folder, path)
        fun <T> getKey(folder: String, path: String, defVal: T?): T? = context?.getKey(folder, path, defVal)
        fun getKeys(folder: String): List<String>? = context?.getKeys(folder)
        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }
        fun removeKey(path: String) {
            context?.removeKey(path)
        }
    }
}

/** Older name for [CloudStreamApp]; some plugin builds still reference it. */
class AcraApplication {
    companion object {
        val context: Context? get() = CloudStreamApp.context

        fun removeKeys(folder: String): Int? = CloudStreamApp.removeKeys(folder)
        fun <T> setKey(path: String, value: T) {
            CloudStreamApp.setKey(path, value)
        }
        fun <T> setKey(folder: String, path: String, value: T) {
            CloudStreamApp.setKey(folder, path, value)
        }
        fun <T> getKey(path: String, defVal: T?): T? = CloudStreamApp.getKey(path, defVal)
        fun <T> getKey(path: String): T? = CloudStreamApp.getKey(path)
        fun <T> getKey(folder: String, path: String): T? = CloudStreamApp.getKey(folder, path)
        fun <T> getKey(folder: String, path: String, defVal: T?): T? = CloudStreamApp.getKey(folder, path, defVal)
    }
}
