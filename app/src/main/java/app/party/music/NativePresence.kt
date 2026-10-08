package app.party.music

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * ACT5 — LOCK-SCREEN PROOF PRESENCE (pure native).
 *
 * Masla: lock screen par page (WebView) so jata hai -> uska MQTT keepalive ruk jata hai
 * -> tower "gaya" samajh kar Left jhank deta hai (aur tower bhi disconnect).
 * Hal: jab tak native gaana chal raha hai (MusicService ka wake lock), ye apne chhote
 * MQTT client se har 25s me member-entry refresh karti rahegi. Page so jaye, Android
 * timers throttle kare, battery killer kuch kare — is client ko farq nahi parta,
 * kyunke ye wahi native side hai jis par awaz chal rahi hoti hai.
 * Jab tak song chale: LEFT MUMKIN HI NAHI. (boss rule)
 *
 * Page (v59) publishPresence() ke sath YaarNative.wpPresence(cfg) bhejta hai — config
 * (room/id/name/avatar/tower) taaza rehta hai jab tak page jaag raha hai; page sota hai
 * to yahi aakhri config se refresh hoti rehti hai. wpRoomLeave/app band -> clean Left.
 *
 * ACT6 — NATIVE KAAN (room commands on lock screen):
 * Wahi zinda client ab ROOM/cmd, ROOM/state, ROOM/queue bhi SUBSCRIBE karti hai.
 * Jo bhi aaye wo onMessage hook se page ke onMsg() tak pahunchta hai — is liye lock
 * par bhi room ka STOP/seek/naya-song lock wale ke player par lagu hota hai (pehle
 * wo purana song chalata rehta tha / playlist ka next khud chala leta tha).
 * Duplicate delivery ka khatra nahi (page jaag raha ho to uske client se bhi aata hai):
 * page ke onMsg me dup(rid/mid) pehle se hai. Purana page (v59 se purana) ho to
 * window.__wpNativeRoomMsg nahi hoga -> hook chup-chaap no-op, ACT5 wala behave.
 * Force-kill (swipe/Force Stop/crash) par ab broker WILL bhi jata hai — empty retained
 * member entry = sacha Left (pehle app aise hi gayab ho jati thi).
 */
object NativePresence {
    private const val TAG = "WPNativePresence"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var client: MqttClient? = null
    @Volatile private var cfg: JSONObject? = null
    @Volatile private var running = false

    /**
     * ACT6: page kaan — (topic, payload) room ke cmd/state/queue messages ka.
     * MainActivity isay set karti hai (page ke onMsg tak evaluateJavascript se).
     * Null = koi kaan nahi (purana page / destroy ho chuka) -> forward band.
     */
    @Volatile var onMessage: ((String, String) -> Unit)? = null

    /** Tower URLs — page ke BROKERS se bilkul same order (0=EMQX, 1=HiveMQ, 2=tyckr). */
    private val URLS = arrayOf(
        "wss://broker.emqx.io:8084/mqtt",
        "wss://broker.hivemq.com:8884/mqtt",
        "wss://mqtt.tyckr.io:8081"
    )

    private val beat = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            main.postDelayed(this, 25_000L)
        }
    }

    /** Page se naya config (har presence beat par aata hai). Room/tower badla to client naya. */
    fun start(json: String) {
        try {
            val o = JSONObject(json)
            if (o.optString("room").isBlank() || o.optString("id").isBlank()) return
            synchronized(this) {
                val old = cfg
                val same = old != null &&
                    old.optString("room") == o.optString("room") &&
                    old.optString("id") == o.optString("id") &&
                    old.optInt("tower", 0) == o.optInt("tower", 0)
                cfg = o
                if (!running || !same) {
                    if (!same) stopClient()
                    running = true
                    io.execute { connect() }
                    main.removeCallbacks(beat)
                    main.postDelayed(beat, 5_000L)
                }
            }
        } catch (t: Throwable) { Log.e(TAG, "start", t) }
    }

    /** Band ho to sacha Left publish karo (clean=true) — sirf tab jab sach me leave/app band. */
    fun stop(clean: Boolean) {
        synchronized(this) {
            running = false
            main.removeCallbacks(beat)
            val c = client; client = null
            val o = cfg
            io.execute {
                if (clean && c != null && o != null) {
                    try {
                        c.publish(o.optString("room") + "/members/" + o.optString("id"),
                            ByteArray(0), 1, true)
                    } catch (_: Throwable) {}
                }
                try { c?.disconnect() } catch (_: Throwable) {}
                try { c?.close() } catch (_: Throwable) {}
            }
            cfg = null
        }
    }

    private fun refresh() {
        val o = cfg ?: return
        try {
            val c = client
            if (c != null && c.isConnected) {
                val payload = JSONObject()
                    .put("name", o.optString("name"))
                    .put("color", o.optString("color"))
                    .put("avatar", o.optString("avatar"))
                    .put("ts", System.currentTimeMillis())
                c.publish(o.optString("room") + "/members/" + o.optString("id"),
                    payload.toString().toByteArray(), 1, true)
            }
        } catch (t: Throwable) { Log.w(TAG, "refresh fail (auto-reconnect chalta rahega)", t) }
    }

    private fun connect() {
        val o = cfg ?: return
        try {
            val url = URLS[o.optInt("tower", 0).coerceIn(0, URLS.size - 1)]
            val c = MqttClient(url, "wpn_" + o.optString("id") + "_" +
                (System.currentTimeMillis() % 100000), MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 12
                keepAliveInterval = 60
                isAutomaticReconnect = true
                maxInflight = 10
                /* ACT6: force-kill (swipe/Force Stop/crash) par broker khud Left bhej dega
                   (empty retained member entry — page ka members handler isi ko Left samajhta hai). */
                try {
                    setWill(o.optString("room") + "/members/" + o.optString("id"),
                        ByteArray(0), 1, true)
                } catch (t: Throwable) { Log.w(TAG, "will set fail", t) }
            }
            /* ACT6: kaan — connect (aur har auto-reconnect) ke baad room topics subscribe.
               cleanSession=true hai is liye har connect par dobara subscribe zaroori hai. */
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    try {
                        val r = o.optString("room")
                        c.subscribe(arrayOf(r + "/cmd", r + "/state", r + "/queue"),
                            intArrayOf(1, 1, 1))
                        Log.i(TAG, "native room-ears UP" + (if (reconnect) " (re)" else "") + ": $serverURI")
                    } catch (t: Throwable) { Log.w(TAG, "room subscribe fail", t) }
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "native connection lost (auto-reconnect on)")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val t = topic ?: return
                    val h = onMessage ?: return
                    val p = String(message?.payload ?: ByteArray(0))
                    main.post {
                        try { h(t, p) } catch (_: Throwable) {}
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            c.connect(opts)
            client = c
            Log.i(TAG, "native presence UP: $url")
        } catch (t: Throwable) { Log.e(TAG, "connect fail", t) }
    }

    private fun stopClient() {
        val c = client; client = null
        try { c?.disconnect() } catch (_: Throwable) {}
        try { c?.close() } catch (_: Throwable) {}
    }
}
