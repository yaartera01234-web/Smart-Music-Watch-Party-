package app.party.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.util.concurrent.Executors

/**
 * v111-FIX — BACKGROUND / LOCK-SCREEN PARTY SERVICE
 *
 * Ye service pehle sirf "dummy" thi (MediaSession khokhli, koi engine nahi) — is liye lock screen
 * par kuch nahi bachta tha. Ab iske andar asli MPV engine chalta hai:
 *
 *   MAIN SCREEN  : page ka "premium" player boss (jaisa tha waisa — kuch nahi badla)
 *   LOCK/BACKGROUND: wahi gaana, usi second se, is service ke MPV engine par (audio only)
 *
 * 4 layers:
 *   1. Foreground service (mediaPlayback)  -> process freeze/kill nahi hota
 *   2. PARTIAL_WAKE_LOCK + WiFiLock        -> screen off par CPU aur network zinda
 *   3. AudioFocus + MediaSession           -> lockscreen/notification ke buttons asli kaam karte hain
 *   4. START_STICKY + onTaskRemoved        -> recents se hatao, phir bhi chalta rahe
 */
class MusicService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var session: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private var engine: NativeAudioEngine? = null
    private val ui = Handler(Looper.getMainLooper())
    private val resolver = Executors.newSingleThreadExecutor { r -> Thread(r, "wp-yt-resolve") }

    @Volatile private var loading = false
    @Volatile private var lastNoteBody = ""
    @Volatile private var lastNoteTitle = ""
    @Volatile private var lastError: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            tickCount++
            try {
                engine?.verifySeek()
                maybeAutoNext()               // gaana khatam? -> agla chalao
                if (tickCount % 5L == 0L) maybePrefetchNext()   // ~10s: agle gaane ka URL tayyar
            } catch (t: Throwable) {}
            try { refreshNotification() } catch (t: Throwable) {}
            ui.postDelayed(this, 2000L)       // build 9: 5s -> 2s (auto-next jaldi pakre)
        }
    }

    // ───────────────────────── QUEUE (background auto-next) ─────────────────────────

    data class QueueItem(val type: String, val url: String, val videoId: String, val label: String) {
        val key: String get() = if (type == "youtube") videoId else url
        fun toJson(): String = org.json.JSONObject()
            .put("type", type).put("url", url).put("videoId", videoId).put("label", label)
            .toString()
        val isYoutube: Boolean get() = type == "youtube" && videoId.isNotBlank()
        val isBlank: Boolean get() = if (type == "youtube") videoId.isBlank() else url.isBlank()
    }

    @Volatile private var queueItems: List<QueueItem> = emptyList()
    @Volatile private var queueIndex: Int = -1
    @Volatile private var currentKey: String = ""
    @Volatile private var lastAutoNextAt: Long = 0L

    // ─────────── FAST START (build 9): pehle se nikale hue stream URLs ───────────
    private data class Cached(val url: String, val title: String?, val at: Long)

    private val resolvedCache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private val prefetchTriedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastPrefetchAttempt: Long = 0L
    private var tickCount: Long = 0L

    private fun cacheUrl(id: String, res: YtAudioSource.Result) {
        resolvedCache[id] = Cached(res.url, res.title, System.currentTimeMillis())
        Log.i(TAG, "stream URL cache me: $id")
    }

    private fun cachedUrl(id: String): Cached? {
        val c = resolvedCache[id] ?: return null
        val age = System.currentTimeMillis() - c.at
        return if (age < 10 * 60 * 1000L) c else null
    }

    /** Jab app saamne hai: MPV warm-up + current gaane ka stream URL pehle se nikaal lo.
        (MPV init 1-3s leta hai — is liye background thread par, warna ANR.) */
    private fun handlePrewarm(intent: Intent) {
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        intent.getStringExtra(EXTRA_TITLE)?.let { if (it.isNotBlank()) lastNoteTitle = it }
        startForegroundNow()
        resolver.execute {
            // 1) MPV core pehle se init karo
            if (engine?.isAlive() != true) {
                val t0 = System.currentTimeMillis()
                val e = NativeAudioEngine(applicationContext)
                e.warmUp()
                ui.post { if (engine == null) engine = e }
                Log.i(TAG, "MPV warm-up ${System.currentTimeMillis() - t0}ms")
            }
            // 2) YouTube stream URL pehle se nikaal lo
            if (type == "youtube" && cachedUrl(id) == null) {
                YtAudioSource.ensureInit(applicationContext)
                val t1 = System.currentTimeMillis()
                val r = YtAudioSource.resolve(id, validate = false)
                if (r != null) {
                    ui.post { cacheUrl(id, r) }
                    Log.i(TAG, "URL pre-resolve ${System.currentTimeMillis() - t1}ms — lock par foran chalega")
                }
            }
        }
    }

    /** Agle gaane ka URL bhi pehle se nikaal lo (auto-next foran lage). */
    private fun maybePrefetchNext() {
        val now = System.currentTimeMillis()
        if (now - lastPrefetchAttempt < 10000L) return
        lastPrefetchAttempt = now
        val nxt = queueItems.getOrNull(queueIndex + 1) ?: return
        if (!nxt.isYoutube) return
        val id = nxt.videoId
        if (cachedUrl(id) != null) return
        val tried = prefetchTriedAt[id] ?: 0L
        if (now - tried < 90000L) return
        prefetchTriedAt[id] = now
        resolver.execute {
            val r = YtAudioSource.resolve(id, validate = false)
            if (r != null) ui.post {
                cacheUrl(id, r)
                Log.i(TAG, "prefetch ready (agle gaane ka URL tayyar): ${nxt.label}")
            }
        }
    }

    /** Page ki queue cache karo (MainActivity poll karti hai). */
    private fun setQueue(json: String) {
        try {
            val o = org.json.JSONObject(json)
            val arr = o.optJSONArray("items") ?: return
            val list = ArrayList<QueueItem>(arr.length())
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                list.add(
                    QueueItem(
                        type = it.optString("type", ""),
                        url = it.optString("url", ""),
                        videoId = it.optString("videoId", ""),
                        label = it.optString("label", "")
                    )
                )
            }
            queueItems = list
            queueIndex = o.optInt("index", 0)
            Log.i(TAG, "queue cache: ${list.size} items, index=$queueIndex")
        } catch (t: Throwable) {
            Log.e(TAG, "queue parse fail", t)
        }
    }

    /** Gaana khatam hua? to queue ka agla item khud chala do (app band hone par bhi). */
    private fun maybeAutoNext() {
        val e = engine ?: return
        if (e.currentUrl == null) return
        if (!e.ended()) return
        val now = System.currentTimeMillis()
        if (now - lastAutoNextAt < 8000L) return
        lastAutoNextAt = now

        val items = queueItems
        val next = items.getOrNull(queueIndex + 1)
        if (next == null || next.isBlank) {
            e.stopPlayback()
            lastNoteBody = if (items.isEmpty()) "Auto-next ke liye playlist kholo" else "Playlist khatam 🎶"
            startForegroundNow()
            Log.i(TAG, "auto-next: koi agla item nahi (index=$queueIndex, size=${items.size})")
            return
        }
        queueIndex += 1
        Log.i(TAG, "auto-next -> #$queueIndex ${next.label} (${next.type})")
        playItem(next, 0.0, true)
    }

    /** Ek queue item chalao (handoff aur auto-next dono isi ko use karte hain). */
    private fun playItem(item: QueueItem, pos: Double, wasPlaying: Boolean) {
        currentKey = item.key
        if (item.label.isNotBlank()) lastNoteTitle = item.label

        if (item.isYoutube) {
            val videoId = YtAudioSource.videoIdOf(item.videoId)
            if (videoId == null) {
                lastError = "YouTube video id nahi mili"
                lastNoteBody = "YouTube id nahi mili — app kholo"
                startForegroundNow()
                return
            }

            // ── FAST PATH (build 9): URL pehle se tayyar hai? -> foran chalao ──
            val ready = cachedUrl(videoId)
            if (ready != null) {
                if (!ready.title.isNullOrBlank()) lastNoteTitle = ready.title
                val ok = obtainEngine().play(ready.url, pos, lastNoteTitle, true)
                if (!wasPlaying) obtainEngine().pause()
                lastError = if (ok) null else "MPV engine start nahi hua"
                lastNoteBody = if (ok) "" else "Engine start nahi hua"
                if (ok) Log.i(TAG, "FAST START (cached URL) -> $videoId")
                startForegroundNow()
                return
            }

            loading = true
            lastError = null
            lastNoteBody = "YouTube audio nikal raha hai…"
            startForegroundNow()
            YtAudioSource.ensureInit(applicationContext)
            resolver.execute {
                var res = YtAudioSource.resolve(videoId, validate = false)
                if (res == null) {
                    try { Thread.sleep(400) } catch (t: InterruptedException) {}
                    res = YtAudioSource.resolve(videoId, validate = true)   // doosri koshish: test bhi karo
                }
                val finalRes = res
                ui.post {
                    loading = false
                    if (finalRes == null) {
                        lastError = "YouTube stream nahi mili (bot-check / premium / region)"
                        lastNoteBody = "YouTube background me nahi chala — app kholo"
                        startForegroundNow()
                    } else {
                        lastError = null
                        if (!finalRes.title.isNullOrBlank()) lastNoteTitle = finalRes.title
                        cacheUrl(videoId, finalRes)      // dobara kaam na karna pare
                        val ok = obtainEngine().play(finalRes.url, pos, lastNoteTitle, true)
                        if (!wasPlaying) obtainEngine().pause()
                        lastNoteBody = if (ok) "" else "Engine start nahi hua"
                        if (!ok) lastError = "MPV engine start nahi hua"
                        startForegroundNow()
                    }
                }
            }
        } else {
            if (item.url.startsWith("blob:")) {
                lastError = "Ye stream (blob:) background me nahi chalta"
                lastNoteBody = "Ye stream background me nahi chala sakta"
                startForegroundNow()
                return
            }
            val ok = obtainEngine().play(item.url, pos, lastNoteTitle, false)
            if (!wasPlaying) obtainEngine().pause()
            lastError = if (ok) null else "MPV engine start nahi hua"
            lastNoteBody = if (ok) "" else "Engine start nahi hua"
            startForegroundNow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        acquireLocks()
        takeAudioFocus()
        startMediaSession()
        consumePendingQueue()?.let { setQueue(it) }   // app band hone se pehle wali queue
        ui.removeCallbacks(ticker)
        ui.postDelayed(ticker, 2000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        when (intent?.action) {
            ACTION_HANDOFF -> handleHandoff(intent)
            ACTION_PREWARM -> handlePrewarm(intent)
            ACTION_SET_QUEUE -> intent.getStringExtra(EXTRA_QUEUE)?.let { setQueue(it) }
            ACTION_PLAY -> {
                engine?.resume()
                notifyRemote(true)
            }
            ACTION_PAUSE -> {
                engine?.pause()
                notifyRemote(false)
            }
            ACTION_STOP -> {
                engine?.stopPlayback()
                notifyRemote(false)
            }
            ACTION_UPDATE -> {
                intent.getStringExtra(EXTRA_TITLE)?.let { if (it.isNotBlank()) lastNoteTitle = it }
            }
        }
        startForegroundNow()
        return START_STICKY
    }

    // ───────────────────────── HANDOFF (page -> native) ─────────────────────────

    private fun handleHandoff(intent: Intent) {
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "direct"
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val pos = intent.getDoubleExtra(EXTRA_POS, 0.0)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val wasPlaying = intent.getBooleanExtra(EXTRA_WAS_PLAYING, true)
        val idx = intent.getIntExtra(EXTRA_INDEX, -1)
        // queue bhi sath aayi hai? (auto-next ke liye — WebView frozen hone se pehle ki state)
        intent.getStringExtra(EXTRA_QUEUE)?.let { if (it.isNotBlank() && it != "{}") setQueue(it) }

        if (title != null && title.isNotBlank()) lastNoteTitle = title
        if (idx >= 0) queueIndex = idx                 // page ka index hi sach hai
        lastAutoNextAt = System.currentTimeMillis()    // abhi handoff hua, foran auto-next na ho

        val item = QueueItem(
            type = type,
            url = if (type == "youtube") "" else id,
            videoId = if (type == "youtube") id else "",
            label = title ?: ""
        )
        Log.i(TAG, "handoff: ${item.type} key=${item.key} pos=$pos index=$idx")
        playItem(item, pos, wasPlaying)
    }

    /** Engine ek dafa banta hai (MPV core). */
    private fun obtainEngine(): NativeAudioEngine {
        val existing = engine
        if (existing != null) {
            existing.ensure()
            return existing
        }
        val created = NativeAudioEngine(applicationContext)
        engine = created
        created.ensure()
        return created
    }

    // ───────────────────────── LOCKS ─────────────────────────

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wp:party").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                wakeHeld = true
            }
        } catch (t: Throwable) { Log.e(TAG, "wake lock fail", t) }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock?.isHeld != true) {
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wp:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (t: Throwable) { Log.e(TAG, "wifi lock fail", t) }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (t: Throwable) {}
        wakeLock = null
        wakeHeld = false
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (t: Throwable) {}
        wifiLock = null
    }

    // ───────────────────────── AUDIO FOCUS ─────────────────────────

    private fun takeAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = am
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { change ->
                        when (change) {
                            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                engine?.pause()
                                updateSession()
                                notifyRemote(false)
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> updateSession()
                        }
                    }
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (t: Throwable) { Log.e(TAG, "audio focus fail", t) }
    }

    private fun abandonAudioFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { am.abandonAudioFocusRequest(it) }
            else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (t: Throwable) {}
        focusRequest = null
    }

    // ───────────────────────── MEDIA SESSION (lockscreen buttons) ─────────────────────────

    private fun startMediaSession() {
        if (session != null) return
        try {
            val s = MediaSessionCompat(this, "wp-party")
            s.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { engine?.resume(); updateSession(); notifyRemote(true) }
                override fun onPause() { engine?.pause(); updateSession(); notifyRemote(false) }
                override fun onStop() { engine?.stopPlayback(); updateSession(); notifyRemote(false) }
                override fun onSeekTo(pos: Long) { engine?.seekTo(pos / 1000.0); updateSession() }
            })
            s.isActive = true
            session = s
            updateSession()
        } catch (t: Throwable) { Log.e(TAG, "media session fail", t) }
    }

    private fun updateSession() {
        try {
            val s = session ?: return
            val playing = engine?.playing == true
            val posMs = ((engine?.position() ?: 0.0) * 1000).toLong()
            val durMs = ((engine?.duration() ?: 0.0) * 1000).toLong()
            s.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(
                        if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                        posMs,
                        if (playing) 1f else 0f
                    )
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                            PlaybackStateCompat.ACTION_SEEK_TO
                    )
                    .build()
            )
            if (durMs > 0) {
                val md = android.support.v4.media.MediaMetadataCompat.Builder()
                    .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, lastNoteTitle)
                    .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, durMs)
                    .build()
                s.setMetadata(md)
            }
        } catch (t: Throwable) {}
    }

    private fun notifyRemote(playing: Boolean) {
        try { remoteListener?.invoke(playing) } catch (t: Throwable) {}
    }

    // ───────────────────────── NOTIFICATION ─────────────────────────

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL, "Watch Party (background)", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        } catch (t: Throwable) {}
    }

    private fun piFlags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun action(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, MusicService::class.java).setAction(action), piFlags()
        )

    private fun startForegroundNow() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground fail", t)
        }
        updateSession()
    }

    private fun refreshNotification() {
        // gaana khatam? -> notification update, engine idle
        val e = engine
        if (e != null && e.isAlive() && e.currentUrl != null && e.ended()) {
            e.stopPlayback()
            lastNoteBody = "Gaana khatam — app kholo next ke liye"
            startForegroundNow()
            return
        }
        if (e?.currentUrl != null) startForegroundNow()
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val playing = engine?.playing == true
        val body = when {
            loading -> "Stream taiyar ho raha hai…"
            lastNoteBody.isNotBlank() -> lastNoteBody
            playing -> "Lock screen par bhi chalta rahega 🎧"
            engine?.currentUrl != null -> "Paused — Play dabao"
            else -> "Party ready — screen band karo, gaana chalta rahega"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags()
        )
        val toggleAction = if (playing) ACTION_PAUSE else ACTION_PLAY

        val b = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(lastNoteTitle.ifBlank { "Watch Party" })
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                action(toggleAction, 1)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(ACTION_STOP, 2)
            )
        try {
            val style = MediaStyle().setShowActionsInCompactView(0)
            session?.sessionToken?.let { style.setMediaSession(it) }
            b.setStyle(style)
        } catch (t: Throwable) {}
        return b.build()
    }

    // ───────────────────────── LIFECYCLE ─────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    /** App band hone par memory caches saaf karo. Disk par hum kuch likhte hi nahi. */
    private fun clearMemoryCaches() {
        try { resolvedCache.clear() } catch (t: Throwable) {}
        try { prefetchTriedAt.clear() } catch (t: Throwable) {}
        queueItems = emptyList()
        queueIndex = -1
        currentKey = ""
        lastPrefetchAttempt = 0L
        Log.i(TAG, "memory caches clear (sirf RAM me thay)")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Boss fix (build 7): app ko recents se hata dein -> gaana bhi band ho jaye.
        try {
            engine?.pause()
            engine?.stopPlayback()
            engine?.release()
        } catch (t: Throwable) {}
        engine = null
        clearMemoryCaches()          // build 11: cache bhi saaf
        Log.i(TAG, "onTaskRemoved -> app band, audio band, cache saaf")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        wakeHeld = false
        instance = null
        ui.removeCallbacks(tick)
        ui.removeCallbacks(ticker)
        try { resolver.shutdownNow() } catch (t: Throwable) {}
        try { engine?.release() } catch (t: Throwable) {}
        engine = null
        clearMemoryCaches()                       // build 11: band hone par cache saaf
        releaseLocks()
        abandonAudioFocus()
        try { session?.release() } catch (t: Throwable) {}
        session = null
        try { stopForeground(true) } catch (t: Throwable) {}
        super.onDestroy()
    }

    private val tick = Runnable { refreshNotification() }

    companion object {
        private const val TAG = "WPMusicService"
        private const val NOTIF_ID = 1
        private const val CHANNEL = "wp_background"

        const val ACTION_HANDOFF = "app.smart.wp.mpv.HANDOFF"
        const val ACTION_PREWARM = "app.smart.wp.mpv.PREWARM"
        const val ACTION_SET_QUEUE = "app.smart.wp.mpv.SET_QUEUE"
        const val ACTION_PLAY = "app.smart.wp.mpv.PLAY"
        const val ACTION_PAUSE = "app.smart.wp.mpv.PAUSE"
        const val ACTION_STOP = "app.smart.wp.mpv.STOP"
        const val ACTION_UPDATE = "app.smart.wp.mpv.UPDATE"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val EXTRA_POS = "pos"
        const val EXTRA_TITLE = "title"
        const val EXTRA_WAS_PLAYING = "wasPlaying"
        const val EXTRA_QUEUE = "queue"
        const val EXTRA_INDEX = "index"

        @Volatile var running = false
        @Volatile var wakeHeld = false

        @Volatile private var instance: MusicService? = null
        @Volatile private var remoteListener: ((Boolean) -> Unit)? = null

        /** Notification / lockscreen / headset buttons ke liye (MainActivity register karti hai). */
        fun onRemote(cb: ((Boolean) -> Unit)?) { remoteListener = cb }

        /** Party ke liye service zinda karo (app khulte waqt bhi, handoff se pehle bhi). */
        fun start(context: Context) {
            val i = Intent(context, MusicService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (t: Throwable) { Log.e(TAG, "service start fail", t) }
        }

        /** Page ke premium player se native engine ko baton-shutli (lock screen ke liye). */
        fun handoff(
            context: Context,
            type: String,
            id: String,
            position: Double,
            title: String?,
            wasPlaying: Boolean,
            queueIndex: Int = -1,
            queueJson: String? = null
        ) {
            val i = Intent(context, MusicService::class.java)
                .setAction(ACTION_HANDOFF)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_POS, position)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_WAS_PLAYING, wasPlaying)
                .putExtra(EXTRA_INDEX, queueIndex)
            if (!queueJson.isNullOrBlank()) i.putExtra(EXTRA_QUEUE, queueJson)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (t: Throwable) { Log.e(TAG, "handoff fail", t) }
        }

        /** FAST START: jab app saamne ho, MPV warm-up + stream URL pehle se nikaal lo. */
        fun prewarm(context: Context, type: String, id: String, title: String?) {
            val i = Intent(context, MusicService::class.java)
                .setAction(ACTION_PREWARM)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TITLE, title)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (t: Throwable) {
                // background se FGS start allowed nahi -> agli dafa foreground me ho jayega
            }
        }

        /** Page ki queue cache karo (auto-next ke liye). */
        fun cacheQueue(context: Context, json: String) {
            if (json.isBlank() || json == "{}") return
            val i = Intent(context, MusicService::class.java)
                .setAction(ACTION_SET_QUEUE)
                .putExtra(EXTRA_QUEUE, json)
            try {
                if (running) {
                    context.startService(i)
                } else {
                    // service band hai -> sirf yaad rakho (jab start ho gi to cacheQueue dobara aayega)
                    pendingQueueJson = json
                }
            } catch (t: Throwable) { pendingQueueJson = json }
        }

        @Volatile private var pendingQueueJson: String? = null

        fun consumePendingQueue(): String? {
            val p = pendingQueueJson
            pendingQueueJson = null
            return p
        }

        /** Current native item ki key (videoId ya url) — page ke sath sync ke liye. */
        fun nativeItemKey(): String = instance?.currentKey ?: ""

        /** Current native item ka JSON — page me wahi item load karne ke liye. */
        fun nativeItemJson(): String? {
            val s = instance ?: return null
            val key = s.currentKey
            if (key.isBlank()) return null
            val isYt = s.engine?.isYoutubeSource == true
            return QueueItem(
                type = if (isYt) "youtube" else "direct",
                url = if (isYt) "" else key,
                videoId = if (isYt) key else "",
                label = s.lastNoteTitle
            ).toJson()
        }

        /** Native ka queue index (auto-next ke baad page ko batana parta hai). */
        fun nativeQueueIndex(): Int = instance?.queueIndex ?: -1

        /** Current native stream ka URL (MPV video ko wapas dene ke liye). */
        fun nativeUrl(): String? = instance?.engine?.currentUrl

        fun nativeAlive(): Boolean = instance?.engine?.currentUrl != null
        fun nativeError(): String? = instance?.lastError
        fun clearError() { instance?.lastError = null }
        fun nativePosition(): Double = instance?.engine?.position() ?: 0.0
        fun nativeWasPlaying(): Boolean = instance?.engine?.playing == true
        fun nativeTitle(): String? = instance?.engine?.currentTitle
        fun nativeIsYoutube(): Boolean = instance?.engine?.isYoutubeSource == true

        /** Wapas app me aane par: engine band, page dobara boss. */
        fun stopNative() {
            val s = instance ?: return
            try {
                // pehle pause (foran chup), phir stop + url clear — kuch phones par warna audio
                // ek-do second chalta reh jata hai
                s.engine?.pause()
                s.engine?.stopPlayback()
            } catch (t: Throwable) {}
            s.lastNoteBody = ""
            try { s.startForegroundNow() } catch (t: Throwable) {}
        }

        /** Page ne resume nahi kiya? to background audio sakhti se band (dohri awaz kabhi nahi). */
        fun stopNativeHard() {
            val s = instance ?: return
            try {
                s.engine?.pause()
                s.engine?.stopPlayback()
                s.engine?.release()
            } catch (t: Throwable) {}
            s.engine = null
            s.lastNoteBody = "Background audio band (page ne resume nahi kiya)"
            try { s.startForegroundNow() } catch (t: Throwable) {}
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, MusicService::class.java)) } catch (t: Throwable) {}
        }

        /** App band karne par: memory caches bhi saaf. */
        fun clearCaches() {
            try { instance?.clearMemoryCaches() } catch (t: Throwable) {}
            pendingQueueJson = null
        }
    }
}
