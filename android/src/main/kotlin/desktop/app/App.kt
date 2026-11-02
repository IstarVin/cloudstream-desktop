package desktop.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import desktop.app.data.Api
import desktop.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class App : Application(), SingletonImageLoader.Factory {
    lateinit var prefs: Prefs
        private set
    lateinit var api: Api
        private set

    /** For writes that must outlive the screen that started them (the last progress save of a closing player). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // One small DataStore read, so every screen starts with the paired server known.
        api = Api(runBlocking { prefs.server() })
    }

    // Posters load through the API's client: images the server proxies need the token too.
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { api.http })) }
        .crossfade(true)
        .build()
}

val Context.app: App get() = applicationContext as App
