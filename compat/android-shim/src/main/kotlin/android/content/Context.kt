package android.content

import java.io.File

/**
 * The part of `android.content.Context` plugins use. Plugins compile `Context.MODE_PRIVATE` to a constant, so only the
 * methods matter. The concrete instance is [desktop.compat.DesktopContext].
 */
abstract class Context {
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    abstract fun getApplicationContext(): Context
    abstract fun getPackageName(): String
    abstract fun getFilesDir(): File
    abstract fun getCacheDir(): File

    companion object {
        const val MODE_PRIVATE = 0
    }
}
