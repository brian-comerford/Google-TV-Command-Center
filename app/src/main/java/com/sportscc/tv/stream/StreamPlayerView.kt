package com.sportscc.tv.stream

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sportscc.core.Channel

data class StreamStatus(val state: State, val message: String? = null) {
    enum class State { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }
}

/**
 * The built-in video player for stream links (.m3u8 and the like) and playlist channels.
 * Remote: OK pauses / plays (or retries after an error), left/right skip 10 s, up goes to the
 * toolbar, down opens the channel list, and channel up/down switch channels.
 */
@OptIn(UnstableApi::class)
@SuppressLint("ViewConstructor")
class StreamPlayerView(context: Context) : FrameLayout(context) {

    var onExitTop: (() -> Unit)? = null
    var onDown: (() -> Unit)? = null
    var onChannelStep: ((Int) -> Unit)? = null
    var onStatus: ((StreamStatus) -> Unit)? = null
    /** OK pressed; return true to take it (e.g. to accept a "switch to this game?" prompt) instead of pausing. */
    var onOk: (() -> Boolean)? = null
    /** The video's shape (width / height) once the stream reports it. */
    var onAspect: ((Float) -> Unit)? = null
    /** Split screen: left/right move between the two sides instead of skipping. */
    var onSideStep: ((Int) -> Unit)? = null

    var current: Channel? = null
        private set
    private var hls = false

    private val view = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        setShutterBackgroundColor(Color.BLACK)
        isFocusable = false
    }
    private var player: ExoPlayer? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setBackgroundColor(Color.BLACK)
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * Plays [channel]. [isHls] says it's HLS when the link alone doesn't show it. If it's the stream
     * [preload] has been keeping warm, that player takes over and starts from what it already buffered.
     */
    fun play(channel: Channel, isHls: Boolean = false) {
        current = channel
        hls = isHls
        val warm = standby?.takeIf { standbyChannel?.url == channel.url && it.playerError == null }
        standby = null
        standbyChannel = null
        stopRefreshing()
        release()
        val p = warm ?: build(channel, isHls)
        player = p
        p.volume = if (muted) 0f else 1f
        view.player = p
        p.playWhenReady = true
        if (warm == null) p.prepare()
        else p.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { onAspect?.invoke(it.width * it.pixelWidthHeightRatio / it.height) }
        onStatus?.invoke(StreamStatus(if (warm != null && p.playbackState == Player.STATE_READY) StreamStatus.State.PLAYING else StreamStatus.State.LOADING))
    }

    /** Silences this player (the other side of a split screen). */
    var muted = false
        set(value) {
            field = value
            player?.volume = if (value) 0f else 1f
        }

    /**
     * Loads [channel] in the background, silent and off screen, so [play] can switch to it right away.
     * Live streams are moved back to the live edge every few seconds so the switch isn't behind.
     * Only one stream is kept warm; null lets it go.
     */
    fun preload(channel: Channel?, isHls: Boolean = false) {
        if (channel?.url == standbyChannel?.url) return
        stopRefreshing()
        standby?.release()
        standby = null
        standbyChannel = null
        if (channel == null || channel.url == current?.url) return
        standby = build(channel, isHls).apply {
            volume = 0f
            playWhenReady = false
            prepare()
        }
        standbyChannel = channel
        handler.postDelayed(refresh, REFRESH_MS)
    }

    /** The stream [preload] is keeping warm, if any. */
    val preloaded: Channel? get() = standbyChannel

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            standby?.let { if (it.isCurrentMediaItemLive && it.playerError == null) it.seekToDefaultPosition() }
            if (standby != null) handler.postDelayed(this, REFRESH_MS)
        }
    }

    private fun stopRefreshing() = handler.removeCallbacks(refresh)

    private var standby: ExoPlayer? = null
    private var standbyChannel: Channel? = null

    private fun build(channel: Channel, isHls: Boolean): ExoPlayer {
        val headers = channel.headers
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: USER_AGENT)
            .setDefaultRequestProperties(headers - "User-Agent")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            // Only the player on screen asks for audio focus, so a preloading one never pauses it.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (p === player) report() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (p === player) report() }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (p === player && videoSize.width > 0 && videoSize.height > 0) {
                    onAspect?.invoke(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                // Paused too long on a live stream: jump back to live instead of failing.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                if (p === player) onStatus?.invoke(StreamStatus(StreamStatus.State.ERROR, describe(error)))
            }
        })
        val item = MediaItem.Builder().setUri(channel.url)
        if (isHls || channel.url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        p.setMediaItem(item.build())
        return p
    }

    fun retry() {
        val c = current ?: return
        play(c, hls)
    }

    fun togglePause() {
        val p = player ?: return
        if (p.playerError != null) return retry()
        if (p.isPlaying) p.pause() else {
            if (p.isCurrentMediaItemLive) p.seekToDefaultPosition() // back to live after a pause
            p.play()
        }
    }

    val isPaused: Boolean get() = player?.let { !it.playWhenReady } ?: false

    /** The app went to the background. */
    fun pause() {
        player?.pause()
        preload(null) // the Watch screen asks again when you come back
    }

    fun resume() {
        val p = player ?: return
        if (p.playerError != null) return retry()
        if (p.isCurrentMediaItemLive) p.seekToDefaultPosition()
        p.play()
    }

    /** Stops and lets go of the stream (and the network). */
    fun stop() {
        preload(null)
        release()
        current = null
        onStatus?.invoke(StreamStatus(StreamStatus.State.IDLE))
    }

    private fun release() {
        view.player = null
        player?.release()
        player = null
    }

    private fun report() {
        val p = player ?: return
        if (p.playerError != null) return
        val s = when {
            p.playbackState == Player.STATE_ENDED -> StreamStatus.State.ENDED
            p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_IDLE -> if (p.playWhenReady) StreamStatus.State.LOADING else StreamStatus.State.PAUSED
            p.isPlaying -> StreamStatus.State.PLAYING
            else -> StreamStatus.State.PAUSED
        }
        onStatus?.invoke(StreamStatus(s))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val p = player
        val center = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (center && event.repeatCount == 0 && onOk?.invoke() == true) return true
        val side = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> 0
        }
        if (side != 0 && onSideStep != null) {
            if (event.repeatCount == 0) onSideStep?.invoke(side)
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> onExitTop?.invoke()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (event.repeatCount == 0) onDown?.invoke()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (p != null && p.isCurrentMediaItemSeekable) p.seekBack()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (p != null && p.isCurrentMediaItemSeekable) p.seekForward()
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> if (event.repeatCount == 0) onChannelStep?.invoke(1)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (event.repeatCount == 0) onChannelStep?.invoke(-1)
            KeyEvent.KEYCODE_MEDIA_PLAY -> p?.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> p?.pause()
            else -> if (event.repeatCount == 0) togglePause()
        }
        return true
    }

    companion object {
        private const val REFRESH_MS = 8_000L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun describe(e: PlaybackException): String = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                when (code) {
                    401, 403 -> "The stream refused this TV (HTTP $code). The link may have expired, or it needs a sign-in."
                    404, 410 -> "The stream isn't there any more (HTTP $code)."
                    null -> "The stream server returned an error."
                    else -> "The stream server returned an error (HTTP $code)."
                }
            }
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Couldn't reach the stream. Check the link and your connection."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The stream isn't there any more."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "That link isn't a video stream this player understands."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This TV can't decode this stream's video format."
            in 6000..6999 -> "This stream is copy-protected (DRM), so it can't play here."
            else -> "Couldn't play this stream (${e.errorCodeName})."
        }
    }
}
