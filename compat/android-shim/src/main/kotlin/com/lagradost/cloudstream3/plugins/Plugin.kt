package com.lagradost.cloudstream3.plugins

import android.content.Context

/**
 * The app's Context-taking plugin base. The server calls [load] with a [desktop.compat.DesktopContext].
 * [openSettings] is recorded but never invoked: the web UI edits the plugin's preferences with a generic form instead.
 */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    var openSettings: ((Context) -> Unit)? = null
}
