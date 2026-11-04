@file:OptIn(UnstableApi::class)

package com.istarvin.cloudstream.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.util.Base64
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.SubtitleView
import desktop.api.ClearKeyDto
import desktop.api.StreamDto
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ExoPlayer with libass for ASS/SSA subtitles (side-loaded and inside MKV), HLS, DASH and ClearKey, fetching through
 * the app's OkHttp client so requests to the desktop carry the pairing token.
 *
 * ASS renders in an OpenGL overlay on top of the [SubtitleView] ([attach]), at the viewport's resolution, so styles,
 * positions and animations come out as in mpv. Other formats render as ordinary cues in the same view.
 */
class Engine(context: Context, http: OkHttpClient) {
    private val httpFactory = OkHttpDataSource.Factory(http)
    private val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpFactory)

    /** The ClearKey key of the link being prepared; the DRM provider reads it when ExoPlayer asks. */
    @Volatile private var clearKey: ClearKeyDto? = null

    val ass = AssHandler(AssRenderType.OVERLAY_OPEN_GL, AssHandlerConfig())

    val player: ExoPlayer

    init {
        val parsers = AssSubtitleParserFactory(ass)
        val sources = DefaultMediaSourceFactory(dataSourceFactory, DefaultExtractorsFactory().withAssMkvSupport(parsers, ass))
            .setSubtitleParserFactory(parsers)
            .setDrmSessionManagerProvider { item ->
                val key = clearKey
                if (item.localConfiguration?.drmConfiguration == null || key == null) DrmSessionManager.DRM_UNSUPPORTED
                else DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(ClearKeyCallback(key))
            }
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        // Keep recent samples for backward seeks, including the keyframe needed to decode them.
        // Buffer ahead without delaying startup; high-bitrate streams hit the byte target sooner.
        val loadControl = DefaultLoadControl.Builder()
            .setBackBuffer(30_000, true)
            .setBufferDurationsMs(
                60_000,
                120_000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(128 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sources)
            .setRenderersFactory(renderers.withAssSupport(ass))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        ass.init(player)
    }

    /** Puts the libass overlay into the player view's subtitle layer. Call once per view. */
    fun attach(view: SubtitleView) {
        view.withAssSupport(ass)
    }

    /** Text size for ASS subtitles (1 = as authored). */
    fun setAssScale(scale: Float) {
        ass.render?.setFontScale(scale)
        ass.renderCallback = { it?.setFontScale(scale) }
    }

    /**
     * The media item for [stream] at [url] (already resolved against the server), with the side-loaded [subtitles].
     * Direct links carry their own headers; proxied ones only need the token the client adds.
     */
    fun item(stream: StreamDto, url: String, subtitles: List<MediaItem.SubtitleConfiguration>, title: String): MediaItem {
        clearKey = stream.clearKey
        httpFactory.setDefaultRequestProperties(if (stream.proxied) emptyMap() else stream.headers)
        return MediaItem.Builder()
            .setUri(url)
            .setMimeType(
                when (stream.type) {
                    "M3U8" -> MimeTypes.APPLICATION_M3U8
                    "DASH" -> MimeTypes.APPLICATION_MPD
                    else -> null
                },
            )
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())
            .apply { if (stream.clearKey != null) setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build()) }
            .build()
    }

    fun release() {
        player.release()
    }
}

/**
 * Answers ClearKey license requests locally with the link's key. The request lists the key ids the content needs; a
 * single-key link's key goes to the id the server named, or to whichever id the content asks for.
 */
private class ClearKeyCallback(private val key: ClearKeyDto) : MediaDrmCallback {
    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response =
        throw UnsupportedOperationException("ClearKey needs no provisioning")

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
        val requested = runCatching {
            val kids = JSONObject(String(request.data)).getJSONArray("kids")
            (0 until kids.length()).map { kids.getString(it) }
        }.getOrDefault(emptyList())
        val kids = key.kid?.let { listOf(b64(hex(it))) } ?: requested.ifEmpty { listOf("") }
        val keys = JSONArray()
        kids.forEach { kid -> keys.put(JSONObject().put("kty", "oct").put("kid", kid).put("k", b64(hex(key.key)))) }
        return MediaDrmCallback.Response(JSONObject().put("keys", keys).put("type", "temporary").toString().toByteArray())
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
