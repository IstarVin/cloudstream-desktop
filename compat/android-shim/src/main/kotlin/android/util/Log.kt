package android.util

import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.Level
import java.util.logging.Logger

/** `android.util.Log` over java.util.logging (which the server routes to its own log). */
object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    private fun log(level: Level, tag: String?, msg: String?, tr: Throwable? = null): Int {
        Logger.getLogger("plugin.${tag ?: "?"}").log(level, msg.orEmpty(), tr)
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String?): Int = log(Level.FINER, tag, msg)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.FINER, tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String?): Int = log(Level.FINE, tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.FINE, tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String?): Int = log(Level.INFO, tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.INFO, tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String?): Int = log(Level.WARNING, tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.WARNING, tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = log(Level.WARNING, tag, null, tr)
    @JvmStatic fun e(tag: String?, msg: String?): Int = log(Level.SEVERE, tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.SEVERE, tag, msg, tr)
    @JvmStatic fun wtf(tag: String?, msg: String?): Int = log(Level.SEVERE, tag, msg)
    @JvmStatic fun wtf(tag: String?, msg: String?, tr: Throwable?): Int = log(Level.SEVERE, tag, msg, tr)
    @JvmStatic fun println(priority: Int, tag: String?, msg: String?): Int =
        log(if (priority >= ERROR) Level.SEVERE else if (priority >= WARN) Level.WARNING else Level.INFO, tag, msg)
    @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = level >= INFO

    @JvmStatic
    fun getStackTraceString(tr: Throwable?): String {
        if (tr == null) return ""
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
