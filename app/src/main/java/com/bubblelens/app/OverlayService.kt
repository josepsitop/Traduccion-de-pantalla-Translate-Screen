package com.bubblelens.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.bubblelens.app.START"
        const val ACTION_STOP = "com.bubblelens.app.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val CHANGE = 0.008f
        val running = MutableStateFlow(false)
    }

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    private var capturer: ScreenCapturer? = null
    private var screenW = 0
    private var screenH = 0
    private var dpi = 320
    private var capScale = 1f
    private var capW = 2
    private var capH = 2

    private var bubble: BubbleView? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var bubbleSize = 0
    private var menu: MenuView? = null
    private var tapOverlay: TranslationOverlayView? = null
    private var subtitle: SubtitleView? = null
    private var subtitleLp: WindowManager.LayoutParams? = null
    private var editor: RegionEditorView? = null

    private var autoJob: Job? = null
    private var tapJob: Job? = null
    private var autoReset = false
    private val readyPairs = HashSet<String>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "mode" -> {
                main.post { onModeChanged() }
            }
            "bubbleAlpha" -> {
                bubble?.alpha = Prefs.bubbleAlpha
            }
            "src", "tgt" -> {
                autoReset = true
            }
            else -> {}
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onScreenChanged()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenSize()
        Prefs.register(prefListener)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, main)
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent
        if (cmd == null) {
            if (!running.value) stopSelf()
            return START_NOT_STICKY
        }
        when (cmd.action) {
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_START -> {
                startAsForeground()
                val code = cmd.getIntExtra(EXTRA_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    cmd.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    cmd.getParcelableExtra<Intent>(EXTRA_DATA)
                }
                if (data != null && code == Activity.RESULT_OK) initProjection(code, data)
                running.value = true
                ensureBubble()
            }
            else -> {
                if (!running.value) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.value = false
        scope.cancel()
        dismissMenuNow()
        tapOverlay?.let { removeView(it, true) }
        tapOverlay = null
        subtitle?.let { removeView(it, true) }
        subtitle = null
        editor?.let { removeView(it, true) }
        editor = null
        bubble?.let { removeView(it, true) }
        bubble = null
        releaseCapture()
        Prefs.unregister(prefListener)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ servicio / captura

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("svc", "Burbuja activa", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "svc")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BubbleLens activo")
            .setContentText("Toca la burbuja para traducir")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat), "Detener", stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun updateScreenSize() {
        val dm = DisplayMetrics()
        val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        d.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        dpi = dm.densityDpi
        capScale = min(1f, 1920f / max(screenW, screenH))
        capW = max(2, (screenW * capScale).roundToInt())
        capH = max(2, (screenH * capScale).roundToInt())
    }

    private fun isLandscape() = screenW > screenH

    private fun initProjection(code: Int, data: Intent) {
        releaseCapture()
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp: MediaProjection? = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            toast("No se pudo iniciar la captura de pantalla", true)
            return
        }
        updateScreenSize()
        val cap = ScreenCapturer(mp, capW, capH, dpi)
        cap.onStopped = { main.post { onProjectionStopped() } }
        try {
            cap.start()
        } catch (e: Exception) {
            cap.release()
            toast("No se pudo iniciar la captura: ${e.message}", true)
            return
        }
        capturer = cap
    }

    private fun releaseCapture() {
        val c = capturer
        capturer = null
        c?.release()
    }

    private fun onProjectionStopped() {
        stopAuto()
        hideTapOverlay()
        releaseCapture()
        toast("Captura detenida. Toca la burbuja para activarla otra vez.", true)
    }

    private fun requestProjection() {
        try {
            startActivity(
                Intent(this, ProjectionRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast("Abre la app para activar la captura", true)
        }
    }

    private fun onScreenChanged() {
        val ow = screenW
        val oh = screenH
        updateScreenSize()
        if (ow == screenW && oh == screenH) return
        capturer?.resize(capW, capH)
        hideTapOverlay()
        hideSubtitle()
        if (editor != null) closeEditor()
        autoReset = true
        bubble?.let {
            bubbleLp.x = bubbleLp.x.coerceIn(0, max(0, screenW - bubbleSize))
            bubbleLp.y = bubbleLp.y.coerceIn(0, max(0, screenH - bubbleSize))
            try {
                wm.updateViewLayout(it, bubbleLp)
            } catch (e: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ ventanas

    private fun baseLp(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun addOverlay(v: View, lp: WindowManager.LayoutParams) {
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            return
        }
        main.post { bringBubbleFront() }
    }

    private fun removeView(v: View, immediate: Boolean = false) {
        try {
            if (immediate) wm.removeViewImmediate(v) else wm.removeView(v)
        } catch (e: Exception) {
        }
    }

    private fun bringBubbleFront() {
        val b = bubble ?: return
        try {
            wm.removeViewImmediate(b)
            wm.addView(b, bubbleLp)
        } catch (e: Exception) {
        }
    }

    private fun ensureBubble() {
        if (bubble != null) return
        bubbleSize = dp(54f)
        val b = BubbleView(this)
        b.alpha = Prefs.bubbleAlpha
        val lp = baseLp(bubbleSize, bubbleSize, true)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        lp.x = if (Prefs.bubbleX >= 0) Prefs.bubbleX else screenW - bubbleSize - dp(6f)
        lp.y = if (Prefs.bubbleY >= 0) Prefs.bubbleY else screenH / 3
        lp.x = lp.x.coerceIn(0, max(0, screenW - bubbleSize))
        lp.y = lp.y.coerceIn(0, max(0, screenH - bubbleSize))
        bubbleLp = lp

        b.onTap = { handleTap() }
        b.onLongPress = { showMenu() }
        b.onDrag = { dx, dy ->
            dismissMenuNow()
            bubbleLp.x = (bubbleLp.x + dx).coerceIn(0, max(0, screenW - bubbleSize))
            bubbleLp.y = (bubbleLp.y + dy).coerceIn(0, max(0, screenH - bubbleSize))
            try {
                wm.updateViewLayout(b, bubbleLp)
            } catch (e: Exception) {
            }
        }
        b.onDragEnd = { snapBubble() }
        try {
            wm.addView(b, lp)
            bubble = b
        } catch (e: Exception) {
            toast("Falta el permiso para mostrar sobre otras apps", true)
            stopSelf()
        }
    }

    private fun snapBubble() {
        val b = bubble ?: return
        val target = if (bubbleLp.x + bubbleSize / 2 < screenW / 2) dp(4f) else screenW - bubbleSize - dp(4f)
        ValueAnimator.ofInt(bubbleLp.x, target).apply {
            duration = 180
            addUpdateListener {
                bubbleLp.x = it.animatedValue as Int
                try {
                    wm.updateViewLayout(b, bubbleLp)
                } catch (e: Exception) {
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    Prefs.bubbleX = bubbleLp.x
                    Prefs.bubbleY = bubbleLp.y
                }
            })
            start()
        }
    }

    private fun refreshBubble() {
        bubble?.active = (autoJob?.isActive == true) || tapOverlay != null
    }

    // ------------------------------------------------------------------ menú

    private fun showMenu() {
        dismissMenuNow()
        val auto = Prefs.mode == "auto"
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(
            Pair(
                if (auto) "💬  Modo: Diálogo automático" else "🖥  Modo: Pantalla (tocar)",
                { toggleMode() }
            )
        )
        if (auto) items.add(Pair("🔲  Ajustar región", { openEditor(false) }))
        val ready = Langs.sources.filter { it.code in Prefs.readySrc }
        items.add(
            Pair(
                "🌐  ${Langs.name(Prefs.src)} → ${Langs.name(Prefs.tgt)}",
                { if (ready.size > 1) cycleSource(ready) else openApp() }
            )
        )
        items.add(Pair("⚙️  Abrir app", { openApp() }))
        items.add(Pair("✖  Cerrar burbuja", { stopSelf() }))

        val v = MenuView(this, items) { dismissMenu() }
        val w = dp(250f)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = v.measuredHeight
        val gap = dp(8f)
        val leftHalf = bubbleLp.x + bubbleSize / 2 < screenW / 2
        var x = if (leftHalf) bubbleLp.x + bubbleSize + gap else bubbleLp.x - w - gap
        x = x.coerceIn(dp(4f), max(dp(4f), screenW - w - dp(4f)))
        val y = (bubbleLp.y - dp(4f)).coerceIn(dp(4f), max(dp(4f), screenH - h - dp(4f)))

        val lp = baseLp(w, WindowManager.LayoutParams.WRAP_CONTENT, true)
        lp.flags = lp.flags or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.x = x
        lp.y = y
        try {
            wm.addView(v, lp)
            menu = v
        } catch (e: Exception) {
        }
    }

    private fun dismissMenu() {
        val m = menu
        menu = null
        if (m != null) main.post { removeView(m, true) }
    }

    private fun dismissMenuNow() {
        val m = menu
        menu = null
        if (m != null) removeView(m, true)
    }

    private fun toggleMode() {
        Prefs.mode = if (Prefs.mode == "auto") "tap" else "auto"
        toast(if (Prefs.mode == "auto") "Modo diálogo automático" else "Modo pantalla (tocar)")
    }

    private fun onModeChanged() {
        stopAuto()
        hideTapOverlay()
        tapJob?.cancel()
        bubble?.busy = false
    }

    private fun cycleSource(ready: List<Lang>) {
        val i = ready.indexOfFirst { it.code == Prefs.src }
        val next = ready[(i + 1) % ready.size]
        Prefs.src = next.code
        toast("Idioma del juego: ${next.name}")
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ toque en la burbuja

    private fun handleTap() {
        dismissMenuNow()
        if (editor != null) return
        if (capturer == null) {
            requestProjection()
            return
        }
        if (Prefs.mode == "auto") toggleAuto() else toggleTap()
    }

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(applicationContext, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private suspend fun ensureModels(src: String, tgt: String): Boolean {
        if (src == tgt) {
            toast("El idioma de origen y el de destino son iguales", true)
            return false
        }
        val key = "$src>$tgt"
        if (key in readyPairs) return true
        val ok = try {
            Engine.isReady(src, tgt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) {
            readyPairs.add(key)
            return true
        }
        toast("Descargando idioma… (necesita internet esta vez)", true)
        return try {
            Engine.download(src, tgt)
            Prefs.addReady(src)
            readyPairs.add(key)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("No pude descargar el idioma. Conéctate a internet o usa «Descargar» en la app.", true)
            false
        }
    }

    // ------------------------------------------------------------------ modo pantalla (tocar)

    private fun toggleTap() {
        if (tapOverlay != null) {
            hideTapOverlay()
            return
        }
        if (tapJob?.isActive == true) {
            tapJob?.cancel()
            bubble?.busy = false
            bubble?.visibility = View.VISIBLE
            return
        }
        tapJob = scope.launch { doTapTranslate() }
    }

    private suspend fun doTapTranslate() {
        val cap = capturer ?: return
        val src = Prefs.src
        val tgt = Prefs.tgt
        bubble?.busy = true
        try {
            if (!ensureModels(src, tgt)) return
            val before = cap.frameCount
            bubble?.visibility = View.INVISIBLE
            cap.awaitNewFrame(before, 350)
            delay(40)
            val shot = withContext(Dispatchers.Default) { cap.grab(null) }
            bubble?.visibility = View.VISIBLE
            if (shot == null) {
                toast("No pude capturar la pantalla")
                return
            }
            try {
                val blocks = Engine.translateScreen(shot, src, tgt)
                if (blocks.isEmpty()) {
                    toast("No encontré texto para traducir")
                } else {
                    showTapOverlay(blocks, shot)
                }
            } finally {
                shot.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("Error: ${e.message}", true)
        } finally {
            bubble?.visibility = View.VISIBLE
            bubble?.busy = false
        }
    }

    private fun showTapOverlay(blocks: List<TBlock>, shot: android.graphics.Bitmap) {
        val v = TranslationOverlayView(this)
        v.onDismiss = { main.post { hideTapOverlay() } }
        v.setBlocks(blocks, shot)
        addOverlay(v, baseLp(screenW, screenH, true))
        tapOverlay = v
        refreshBubble()
    }

    private fun hideTapOverlay() {
        tapOverlay?.let { removeView(it) }
        tapOverlay = null
        refreshBubble()
    }

    // ------------------------------------------------------------------ modo diálogo (automático)

    private fun loadRegion(): Rect? {
        val f = Prefs.region(isLandscape()) ?: return null
        return Rect(
            (f[0] * screenW).roundToInt(), (f[1] * screenH).roundToInt(),
            (f[2] * screenW).roundToInt(), (f[3] * screenH).roundToInt()
        )
    }

    private fun toggleAuto() {
        if (autoJob?.isActive == true) {
            stopAuto()
            toast("Traducción automática detenida")
            return
        }
        if (loadRegion() == null) {
            openEditor(true)
            return
        }
        startAuto()
    }

    private fun startAuto() {
        autoJob?.cancel()
        autoJob = scope.launch { runAuto() }
        refreshBubble()
        toast("Traduciendo diálogo automáticamente")
    }

    private fun stopAuto() {
        autoJob?.cancel()
        autoJob = null
        hideSubtitle()
        bubble?.busy = false
        refreshBubble()
    }

    private suspend fun runAuto() {
        if (!ensureModels(Prefs.src, Prefs.tgt)) {
            stopAuto()
            return
        }
        var prev: IntArray? = null
        var processed: IntArray? = null
        var stable = 0
        var lastKey = ""
        autoReset = false

        while (true) {
            val interval = when (Prefs.speed) {
                0 -> 260L
                2 -> 800L
                else -> 450L
            }
            delay(interval)

            val src = Prefs.src
            val tgt = Prefs.tgt
            if (autoReset) {
                autoReset = false
                prev = null
                processed = null
                stable = 0
                lastKey = ""
                hideSubtitle()
                if (!ensureModels(src, tgt)) {
                    stopAuto()
                    return
                }
            }

            val cap = capturer ?: return
            val reg = loadRegion() ?: return
            val cr = Rect(
                (reg.left * capScale).roundToInt(), (reg.top * capScale).roundToInt(),
                (reg.right * capScale).roundToInt(), (reg.bottom * capScale).roundToInt()
            )
            val bmp = withContext(Dispatchers.Default) { cap.grab(cr) } ?: continue
            try {
                val thumb = withContext(Dispatchers.Default) { Thumb.of(bmp) }
                val p = prev
                val changing = p == null || Thumb.diff(p, thumb) > CHANGE
                prev = thumb
                if (changing) stable = 0 else stable++
                val q = processed
                val isNew = q == null || Thumb.diff(q, thumb) > CHANGE
                if (stable >= 2 && isNew) {
                    processed = thumb
                    bubble?.busy = true
                    try {
                        val res = Engine.translateRegion(bmp, src, tgt)
                        if (res == null) {
                            lastKey = ""
                            hideSubtitle()
                        } else if (res.key != lastKey) {
                            lastKey = res.key
                            showSubtitle(res, reg)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // se reintenta en el próximo cambio
                    } finally {
                        bubble?.busy = false
                    }
                }
            } finally {
                bmp.recycle()
            }
        }
    }

    private fun showSubtitle(res: RegionResult, reg: Rect) {
        val margin = dp(8f)
        val w = reg.width().coerceIn(min(dp(220f), screenW - margin * 2), max(dp(220f), screenW - margin * 2))
            .coerceAtMost(screenW - margin * 2)
        val x = (reg.left + (reg.width() - w) / 2).coerceIn(margin, max(margin, screenW - w - margin))

        val v = subtitle ?: SubtitleView(this).also { subtitle = it }
        v.show(res.name, res.text, Prefs.textSize)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = v.measuredHeight
        val gap = dp(8f)
        val aboveY = reg.top - h - gap
        val belowY = reg.bottom + gap
        val preferAbove = reg.centerY() > screenH / 2
        val fitsAbove = aboveY >= dp(4f)
        val fitsBelow = belowY + h <= screenH - dp(4f)
        val y = when {
            preferAbove && fitsAbove -> aboveY
            !preferAbove && fitsBelow -> belowY
            fitsAbove -> aboveY
            fitsBelow -> belowY
            else -> aboveY.coerceIn(dp(4f), max(dp(4f), screenH - h - dp(4f)))
        }

        val existing = subtitleLp
        if (existing != null && v.parent != null) {
            existing.x = x
            existing.y = y
            existing.width = w
            try {
                wm.updateViewLayout(v, existing)
            } catch (e: Exception) {
            }
        } else {
            val lp = baseLp(w, WindowManager.LayoutParams.WRAP_CONTENT, false)
            lp.alpha = 0.8f // ≤ 0.8 permite que los toques pasen al juego (Android 12+)
            lp.x = x
            lp.y = y
            subtitleLp = lp
            v.alpha = 0f
            addOverlay(v, lp)
            v.animate().alpha(1f).setDuration(160).start()
        }
    }

    private fun hideSubtitle() {
        subtitle?.let { removeView(it) }
        subtitle = null
        subtitleLp = null
    }

    // ------------------------------------------------------------------ editor de región

    private fun openEditor(thenStart: Boolean) {
        if (editor != null) return
        val wasRunning = autoJob?.isActive == true
        stopAuto()
        hideTapOverlay()
        val v = RegionEditorView(this)
        val f = Prefs.region(isLandscape())
            ?: if (isLandscape()) floatArrayOf(0.1f, 0.66f, 0.9f, 0.96f) else floatArrayOf(0.05f, 0.7f, 0.95f, 0.96f)
        v.setFraction(f[0], f[1], f[2], f[3])
        v.onDone = { fr ->
            Prefs.setRegion(isLandscape(), fr.left, fr.top, fr.right, fr.bottom)
            main.post {
                closeEditor()
                toast("Región guardada ✓")
                if (thenStart || wasRunning) startAuto()
            }
        }
        v.onCancel = {
            main.post {
                closeEditor()
                if (wasRunning) startAuto()
            }
        }
        addOverlay(v, baseLp(screenW, screenH, true))
        editor = v
    }

    private fun closeEditor() {
        editor?.let { removeView(it) }
        editor = null
    }
}
