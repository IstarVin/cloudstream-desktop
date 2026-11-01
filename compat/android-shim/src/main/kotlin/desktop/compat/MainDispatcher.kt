package desktop.compat

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * `Dispatchers.Main` on the desktop: one daemon thread named "main", standing in for Android's main thread. The JVM
 * has no Main dispatcher, so without this `withContext(Dispatchers.Main)` throws; StreamCorner drives its WebViews
 * from it. Registered through META-INF/services, which kotlinx.coroutines reads on first use.
 */
@OptIn(InternalCoroutinesApi::class)
class DesktopMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority = 0
    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = DesktopMain
    override fun hintOnError(): String? = null
}

object DesktopMain : MainCoroutineDispatcher() {
    @Volatile private var thread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "main").apply { isDaemon = true; thread = this }
    }

    val isMainThread get() = Thread.currentThread() === thread

    override val immediate: MainCoroutineDispatcher = Immediate

    override fun dispatch(context: CoroutineContext, block: Runnable) = executor.execute(block)

    override fun toString() = "Dispatchers.Main"

    private object Immediate : MainCoroutineDispatcher() {
        override val immediate get() = this
        override fun isDispatchNeeded(context: CoroutineContext) = !isMainThread
        override fun dispatch(context: CoroutineContext, block: Runnable) = executor.execute(block)
        override fun toString() = "Dispatchers.Main.immediate"
    }
}
