package com.bubblelens.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * Traductor con cámara: traduce en vivo cuando te quedas quieto, y permite "congelar"
 * una foto temporal (solo en memoria, nunca se guarda).
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        private const val GW = 16 // rejilla (celdas) para detectar movimiento
        private const val GH = 12
        private const val MOVE = 3.5f // movimiento entre dos fotogramas
        private const val SAME = 4.5f // misma escena que la ya procesada
        private const val CHANGE = 5.5f // la escena cambió: quitar la traducción
        private const val HINT = "Apunta al texto y mantén quieto el teléfono"
    }

    private lateinit var previewView: PreviewView
    private lateinit var live: TranslationOverlayView
    private lateinit var frozen: TranslationOverlayView
    private lateinit var status: TextView
    private lateinit var srcChip: TextView
    private lateinit var tgtChip: TextView
    private lateinit var shutter: TextView
    private lateinit var torchBtn: TextView

    private var camera: Camera? = null
    private var analysisExecutor: ExecutorService? = null
    private val ready = HashSet<String>()
    private var freezeJob: Job? = null
    private var torchOn = false

    @Volatile private var frozenMode = false
    @Volatile private var busy = false
    @Volatile private var overlayShown = false
    @Volatile private var prevThumb: IntArray? = null
    @Volatile private var processedThumb: IntArray? = null
    @Volatile private var lastScript: Script? = null
    private var lastMs = 0L
    private var stable = 0

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) {
                startCamera()
            } else {
                Toast.makeText(this, "Sin permiso de cámara no se puede traducir", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        buildUi()
        analysisExecutor = Executors.newSingleThreadExecutor()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        freezeJob?.cancel()
        analysisExecutor?.shutdown()
        camera = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interfaz

    private fun pill(color: Int, radius: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radius
        return g
    }

    private fun circleButton(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        t.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0x99000000.toInt())
        t.background = bg
        t.setOnClickListener { onClick() }
        return t
    }

    private fun chip(onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
        t.background = pill(0x99000000.toInt(), dpf(22f))
        t.setOnClickListener { onClick() }
        return t
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        previewView = PreviewView(this)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        root.addView(previewView, FrameLayout.LayoutParams(match, match))

        live = TranslationOverlayView(this)
        live.consumeTouch = false
        root.addView(live, FrameLayout.LayoutParams(match, match))

        frozen = TranslationOverlayView(this)
        frozen.setBackgroundColor(Color.BLACK)
        frozen.visibility = View.GONE
        root.addView(frozen, FrameLayout.LayoutParams(match, match))

        // barra superior: volver | origen → destino | linterna
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val back = circleButton("←") { finish() }
        srcChip = chip { pickSource() }
        tgtChip = chip { pickTarget() }
        val arrow = TextView(this)
        arrow.text = "→"
        arrow.setTextColor(Color.WHITE)
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        arrow.setPadding(dp(8f), 0, dp(8f), 0)
        torchBtn = circleButton("🔦") { toggleTorch() }
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.gravity = Gravity.CENTER
        chips.addView(srcChip)
        chips.addView(arrow)
        chips.addView(tgtChip)
        top.addView(back, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        top.addView(chips, LinearLayout.LayoutParams(0, wrap, 1f))
        top.addView(torchBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        val topLp = FrameLayout.LayoutParams(match, wrap, Gravity.TOP)
        topLp.setMargins(dp(12f), dp(10f), dp(12f), 0)
        root.addView(top, topLp)

        // mensaje de estado
        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        status.gravity = Gravity.CENTER
        status.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        status.background = pill(0xB3000000.toInt(), dpf(18f))
        status.text = HINT
        val stLp = FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        stLp.bottomMargin = dp(132f)
        root.addView(status, stLp)

        // botón de foto
        shutter = TextView(this)
        shutter.gravity = Gravity.CENTER
        shutter.setTextColor(0xFF16181D.toInt())
        shutter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        val sbg = GradientDrawable()
        sbg.shape = GradientDrawable.OVAL
        sbg.setColor(Color.WHITE)
        sbg.setStroke(dp(6f), 0x66FFFFFF)
        shutter.background = sbg
        shutter.setOnClickListener { toggleFreeze() }
        val shLp = FrameLayout.LayoutParams(dp(76f), dp(76f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        shLp.bottomMargin = dp(36f)
        root.addView(shutter, shLp)

        // pellizcar = zoom, tocar = enfocar
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val zs = cam.cameraInfo.zoomState.value ?: return true
                val r = (zs.zoomRatio * detector.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                cam.cameraControl.setZoomRatio(r)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        previewView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) tap.onTouchEvent(ev)
            true
        }

        setContentView(root)
        refreshChips()
    }

    private fun refreshChips() {
        srcChip.text = (if (Prefs.camSrc == "auto") "Auto" else Langs.name(Prefs.camSrc)) + " ▾"
        tgtChip.text = Langs.name(Prefs.tgt) + " ▾"
    }

    private fun setStatus(t: String) {
        status.text = t
        status.visibility = View.VISIBLE
    }

    private fun resetScene() {
        lastScript = null
        processedThumb = null
        prevThumb = null
        overlayShown = false
        stable = 0
        if (!frozenMode) live.clear()
        refreshChips()
        setStatus(HINT)
    }

    private fun pickSource() {
        val names = ArrayList<String>()
        names.add("Automático (detectar idioma)")
        for (l in Langs.sources) names.add(l.name)
        AlertDialog.Builder(this)
            .setTitle("Idioma del texto")
            .setItems(names.toTypedArray()) { _, which ->
                Prefs.camSrc = if (which == 0) "auto" else Langs.sources[which - 1].code
                resetScene()
            }
            .show()
    }

    private fun pickTarget() {
        val names = Langs.targets.map { it.name }
        AlertDialog.Builder(this)
            .setTitle("Traducir a")
            .setItems(names.toTypedArray()) { _, which ->
                Prefs.tgt = Langs.targets[which].code
                resetScene()
            }
            .show()
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        torchBtn.alpha = if (torchOn) 1f else 0.7f
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    // ------------------------------------------------------------------ cámara

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val previewRs = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(previewRs).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val analysisRs = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisRs)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val exec = analysisExecutor
            if (exec != null) analysis.setAnalyzer(exec) { proxy -> onFrame(proxy) }

            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                torchBtn.visibility = if (cam.cameraInfo.hasFlashUnit()) View.VISIBLE else View.GONE
                torchBtn.alpha = 0.7f
            } catch (e: Exception) {
                setStatus("No se pudo abrir la cámara")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Resumen de la imagen: promedio de brillo en una rejilla de 16x12 celdas.
     * Al promediar celdas grandes, el temblor de la mano casi no cambia el resultado,
     * pero mover el teléfono de verdad sí.
     */
    private fun lumaThumb(proxy: ImageProxy): IntArray {
        val plane = proxy.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val w = proxy.width
        val h = proxy.height
        val sums = IntArray(GW * GH)
        val counts = IntArray(GW * GH)
        val colIdx = IntArray((w + 1) / 2) { (it * 2 * GW) / w }
        var y = 0
        while (y < h) {
            val j = min(GH - 1, (y * GH) / h)
            val base = y * rs
            var x = 0
            var k = 0
            while (x < w) {
                val idx = j * GW + colIdx[k]
                sums[idx] += buf.get(base + x * ps).toInt() and 0xFF
                counts[idx]++
                x += 2
                k++
            }
            y += 2
        }
        for (n in sums.indices) sums[n] = if (counts[n] > 0) sums[n] / counts[n] else 0
        return sums
    }

    private fun meanDiff(a: IntArray, b: IntArray): Float {
        var s = 0
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toFloat() / a.size
    }

    /** Se ejecuta en el hilo de análisis. Mira un fotograma cada ~0,25 s. */
    @SuppressLint("UnsafeOptInUsageError")
    private fun onFrame(proxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (frozenMode || busy || now - lastMs < 250L) {
            proxy.close()
            return
        }
        lastMs = now
        val thumb: IntArray = try {
            lumaThumb(proxy)
        } catch (e: Exception) {
            proxy.close()
            return
        }
        val prev = prevThumb
        prevThumb = thumb
        val done = processedThumb

        // 1) Con una traducción en pantalla: solo se quita si la escena cambió de verdad.
        if (overlayShown && done != null) {
            if (meanDiff(done, thumb) > CHANGE) {
                overlayShown = false
                processedThumb = null
                stable = 0
                runOnUiThread {
                    live.clear()
                    setStatus(HINT)
                }
            }
            proxy.close()
            return
        }

        // 2) Si te estás moviendo, esperar.
        if (prev != null && meanDiff(prev, thumb) > MOVE) {
            stable = 0
            proxy.close()
            return
        }

        // 3) Quieto durante 2 miradas seguidas: traducir.
        stable++
        if (stable < 2) {
            proxy.close()
            return
        }
        if (done != null && meanDiff(done, thumb) < SAME) {
            proxy.close() // misma escena ya revisada (sin texto)
            return
        }
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val rot = proxy.imageInfo.rotationDegrees
        val upright = rot % 180 == 0
        val uw = if (upright) proxy.width else proxy.height
        val uh = if (upright) proxy.height else proxy.width
        val input = InputImage.fromMediaImage(media, rot)
        busy = true
        lifecycleScope.launch {
            try {
                val pref = Prefs.camSrc
                val tgt = Prefs.tgt
                setStatus("Traduciendo…")
                val read = Engine.read(input, pref, lastScript)
                processedThumb = thumb
                prevThumb = null
                stable = 0
                if (read == null) {
                    overlayShown = false
                    live.clear()
                    setStatus(HINT)
                    return@launch
                }
                lastScript = read.script
                if (pref == "auto") srcChip.text = "Auto · " + Langs.name(read.lang) + " ▾"
                if (!ensureReady(read.lang, tgt)) return@launch
                val blocks = Engine.translateRead(read, tgt, 24)
                if (frozenMode) return@launch
                if (blocks.isEmpty()) {
                    overlayShown = false
                    live.clear()
                    setStatus(if (read.lang == tgt) "Ya está en tu idioma" else "Nada que traducir aquí")
                    return@launch
                }
                live.setLive(blocks, uw, uh)
                overlayShown = true
                setStatus(Langs.name(read.lang) + " → " + Langs.name(tgt))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                overlayShown = false
                setStatus("No pude leer el texto, prueba de nuevo")
            } finally {
                proxy.close()
                busy = false
            }
        }
    }

    /** Asegura que el idioma esté descargado (si no, lo descarga con internet). */
    private suspend fun ensureReady(lang: String, tgt: String): Boolean {
        if (lang == tgt) return true
        val key = "$lang>$tgt"
        if (key in ready) return true
        val ok = try {
            Engine.isReady(lang, tgt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) {
            ready.add(key)
            return true
        }
        setStatus("Descargando " + Langs.name(lang) + "… (necesita internet)")
        return try {
            Engine.download(lang, tgt)
            Prefs.addReady(lang)
            ready.add(key)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus("Falta el idioma " + Langs.name(lang) + ". Descárgalo con internet.")
            false
        }
    }

    // ------------------------------------------------------------------ foto temporal

    private fun toggleFreeze() {
        if (frozenMode) {
            unfreeze()
            return
        }
        val bmp = previewView.bitmap
        if (bmp == null) {
            Toast.makeText(this, "La cámara aún no está lista", Toast.LENGTH_SHORT).show()
            return
        }
        frozenMode = true
        live.clear()
        frozen.visibility = View.VISIBLE
        frozen.setPhoto(bmp, emptyList())
        shutter.text = "✕"
        setStatus("Traduciendo foto…")
        freezeJob = lifecycleScope.launch {
            try {
                val pref = Prefs.camSrc
                val tgt = Prefs.tgt
                val read = Engine.read(InputImage.fromBitmap(bmp, 0), pref, lastScript)
                if (read == null) {
                    setStatus("No encontré texto en la foto")
                    return@launch
                }
                lastScript = read.script
                if (pref == "auto") srcChip.text = "Auto · " + Langs.name(read.lang) + " ▾"
                if (!ensureReady(read.lang, tgt)) return@launch
                val blocks = Engine.translateRead(read, tgt)
                if (blocks.isEmpty()) {
                    setStatus(if (read.lang == tgt) "Ya está en tu idioma" else "Nada que traducir en la foto")
                    return@launch
                }
                frozen.setPhoto(bmp, blocks)
                setStatus(Langs.name(read.lang) + " → " + Langs.name(tgt) + "  ·  la foto no se guarda")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("No pude traducir la foto")
            }
        }
    }

    private fun unfreeze() {
        freezeJob?.cancel()
        freezeJob = null
        frozen.clear()
        frozen.visibility = View.GONE
        frozenMode = false
        shutter.text = ""
        processedThumb = null
        prevThumb = null
        overlayShown = false
        stable = 0
        setStatus(HINT)
    }
}
