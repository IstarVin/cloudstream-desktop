package com.istarvin.cloudstream

import android.app.Activity
import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import desktop.api.DeviceKind
import com.istarvin.cloudstream.data.Api
import com.istarvin.cloudstream.data.Cast
import com.istarvin.cloudstream.data.Prefs
import com.istarvin.cloudstream.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class App : Application(), SingletonImageLoader.Factory {
    internal val updater by lazy { com.istarvin.cloudstream.update.AppUpdater(this, scope) }

    lateinit var prefs: Prefs
        private set
    lateinit var api: Api
        private set
    lateinit var cast: Cast
        private set

    /** For writes that must outlive the screen that started them (the last progress save of a closing player). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var started = 0
    private var pendingStop: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // One small DataStore read, so every screen starts with the paired server known.
        val (server, castPrefs) = runBlocking { prefs.server() to prefs.cast() }
        api = Api(server)
        cast = Cast(api, prefs, scope, if (isTv()) DeviceKind.TV else DeviceKind.PHONE, castPrefs, deviceName())
        cast.onPlay = { req -> PlayerActivity.startCast(this, req) }
        registerActivityLifecycleCallbacks(Visibility())
    }

    /** The casting socket is up while any screen of the app is visible (PiP included). */
    private inner class Visibility : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) {
                pendingStop?.cancel()
                cast.start()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (--started == 0) {
                // Rotation and switching between screens stop one activity just before starting the next.
                pendingStop = scope.launch {
                    delay(5000)
                    if (started == 0) cast.stop()
                }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun isTv(): Boolean =
        (getSystemService(UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** The name the user gave the device in system settings, else its model, until they name it here. */
    private fun deviceName(): String {
        val named = runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        if (!named.isNullOrBlank()) return named.take(60)
        val model = Build.MODEL.orEmpty()
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").trim().ifEmpty { "Android" }.take(60)
    }

    // Posters load through the API's client: images the server proxies need the token too.
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { api.http })) }
        .crossfade(true)
        .build()
}

val Context.app: App get() = applicationContext as App
