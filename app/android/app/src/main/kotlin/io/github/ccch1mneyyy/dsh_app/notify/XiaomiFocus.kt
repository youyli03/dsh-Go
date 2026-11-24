package io.github.ccch1mneyyy.dsh_app.notify

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.phone.Json
import io.github.ccch1mneyyy.dsh_app.runtime.VendorSettings
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HyperOS 焦点通知 / 超级岛: the live activity's notification with a `miui.focus.param` template
 * and its picture (dev.mi.com/xiaomihyperos/documentation/detail?pId=2131, limits pId=2146).
 *
 * Only for an app SystemUI lets through (`canShowFocus`): domestic HyperOS grants that after
 * Xiaomi's review of a store-listed app, global builds behind a per-app switch. Without it the
 * extras would be dead weight, so they stay off. HyperOS 3.1+ also puts plain Live Updates in the
 * island ([LiveActivity]), which needs none of this.
 */
object XiaomiFocus {
    private const val TAG = "DshLive"
    const val EXTRA_PARAM = "miui.focus.param"
    const val EXTRA_PICS = "miui.focus.pics"
    const val PIC_ICON = "miui.focus.pic_icon"

    private const val PROTOCOL_SETTING = "notification_focus_protocol"
    private const val PROVIDER = "content://miui.statusbar.notification.public"
    private const val CHECK_TTL_MS = 60_000L

    /** The template's cap, in UTF-8 bytes. */
    const val PARAM_MAX_BYTES = 3072

    /** The island's text areas hold about four characters. */
    private const val ISLAND_CHARS = 6

    @Volatile private var checked: Pair<Long, Boolean>? = null
    private val checkLock = Any()
    private val checkPending = AtomicBoolean()
    private val checkWorker = Executors.newSingleThreadExecutor { Thread(it, "dsh-xiaomi-focus") }
    private val isXiaomi by lazy {
        VendorSettings.vendorOf(Build.MANUFACTURER, Build.BRAND) == VendorSettings.XIAOMI
    }

    /**
     * The template for [c]: the pet (or the app icon) and the chip's word in the island, the title
     * and the line in the card. Long titles and lines are cut to stay within [PARAM_MAX_BYTES].
     */
    fun param(c: LiveContent): String {
        val word = (c.chip ?: c.title).take(ISLAND_CHARS)
        var title = c.title
        var text = c.text
        while (true) {
            val json = Json.write(template(c, word, title, text))
            if (json.toByteArray(Charsets.UTF_8).size <= PARAM_MAX_BYTES || (title.length <= 8 && text.isEmpty())) return json
            if (text.isNotEmpty()) text = text.take(text.length / 2) else title = title.take(title.length / 2)
        }
    }

    private fun template(c: LiveContent, word: String, title: String, text: String): Map<String, Any?> {
        val pic = mapOf("type" to 1, "pic" to PIC_ICON)
        return mapOf(
            "param_v2" to mapOf(
                "protocol" to 1,
                "business" to "dsh_live_${c.kind.wire}",
                "updatable" to true,
                "enableFloat" to false,
                "ticker" to "$word · $title",
                "aodTitle" to word,
                "param_island" to mapOf(
                    "islandProperty" to 1,
                    "bigIslandArea" to mapOf(
                        "imageTextInfoLeft" to mapOf(
                            "type" to 1,
                            "picInfo" to pic,
                            "textInfo" to mapOf("title" to word),
                        ),
                    ),
                    "smallIslandArea" to mapOf("picInfo" to pic),
                ),
                "baseInfo" to mapOf("type" to 2, "title" to title, "content" to text),
            ),
        )
    }

    /** Adds the focus template after permission has been checked. */
    fun apply(builder: Notification.Builder, c: LiveContent, picture: Icon) {
        builder.addExtras(
            Bundle().apply {
                putString(EXTRA_PARAM, param(c))
                putBundle(EXTRA_PICS, Bundle().apply { putParcelable(PIC_ICON, picture) })
            },
        )
    }

    /** The notification worker uses the last result while a single refresh runs separately. */
    fun cachedAllowed(context: Context, onChecked: () -> Unit): Boolean {
        if (!isXiaomi) return false
        val previous = checked
        if (freshResult() == null && checkPending.compareAndSet(false, true)) {
            checkWorker.execute {
                try {
                    allowed(context)
                } finally {
                    checkPending.set(false)
                }
                onChecked()
            }
        }
        return previous?.second == true
    }

    private fun freshResult(): Boolean? =
        checked?.takeIf { SystemClock.elapsedRealtime() - it.first < CHECK_TTL_MS }?.second

    /**
     * SystemUI shows this app's focus notifications. A binder call into SystemUI, cached for a
     * minute: call off the main thread.
     */
    fun allowed(context: Context): Boolean {
        if (!isXiaomi) return false
        return synchronized(checkLock) {
            freshResult()?.let { return@synchronized it }
            val started = SystemClock.elapsedRealtime()
            Log.d(TAG, "Xiaomi focus permission check started")
            val ok = protocol(context) >= 1 && canShowFocus(context)
            val finished = SystemClock.elapsedRealtime()
            checked = finished to ok
            Log.d(TAG, "Xiaomi focus permission check finished: allowed=$ok elapsedMs=${finished - started}")
            ok
        }
    }

    /** 1: HyperOS 1, 2: HyperOS 2, 3: HyperOS 3 (超级岛); 0 elsewhere. */
    fun protocol(context: Context): Int {
        if (!isXiaomi) return 0
        return runCatching { Settings.System.getInt(context.contentResolver, PROTOCOL_SETTING, 0) }.getOrDefault(0)
    }

    private fun canShowFocus(context: Context): Boolean = runCatching {
        val extras = Bundle().apply { putString("package", context.packageName) }
        context.contentResolver.call(Uri.parse(PROVIDER), "canShowFocus", null, extras)?.getBoolean("canShowFocus", false) == true
    }.onFailure { Log.w(TAG, "Xiaomi focus permission check failed: ${it.javaClass.simpleName}") }.getOrDefault(false)
}
