package desktop.api

import desktop.cdp.ChromiumUnavailableException
import kotlinx.coroutines.TimeoutCancellationException

/** A short reason for a failed call into a plugin, shown per provider or per source in the UI. */
fun describe(t: Throwable): String = when (t) {
    is ChromiumUnavailableException -> t.message ?: "Needs Chromium"
    is TimeoutCancellationException -> "Timed out"
    is NotImplementedError -> "Uses a Cloudstream feature the desktop runtime doesn't have (${t.message})"
    is LinkageError -> "The plugin doesn't match this runtime (${t.javaClass.simpleName}: ${t.message}); rebuild it"
    else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
}
