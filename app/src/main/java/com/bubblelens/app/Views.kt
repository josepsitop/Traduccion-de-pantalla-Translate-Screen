package com.bubblelens.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.spf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

private fun clampF(v: Float, lo: Float, hi: Float): Float = if (hi < lo) lo else max(lo, min(hi, v))

object Colors {
    fun luma(c: Int): Float =
        0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    /** Color de fondo aproximado alrededor de un bloque de texto (mediana de varias muestras). */
    fun sample(b: Bitmap, r: Rect): Int {
        val l = r.left.coerceIn(0, b.width - 1)
        val t = r.top.coerceIn(0, b.height - 1)
        val rr = (r.right - 1).coerceIn(0, b.width - 1)
        val bb = (r.bottom - 1).coerceIn(0, b.height - 1)
        val xs = intArrayOf(min(l + 2, rr), (l + rr) / 2, max(rr - 2, l))
        val ys = intArrayOf(min(t + 2, bb), max(bb - 2, t))
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        fun add(x: Int, y: Int) {
            val c = b.getPixel(x, y)
            rs.add(Color.red(c))
            gs.add(Color.green(c))
            bs.add(Color.blue(c))
        }
        for (x in xs) for (y in ys) add(x, y)
        add(min(l + 2, rr), (t + bb) / 2)
        add(max(rr - 2, l), (t + bb) / 2)
        rs.sort(); gs.sort(); bs.sort()
        val m = rs.size / 2
        return Color.rgb(rs[m], gs[m], bs[m])
    }

    /** Igual que [sample] pero para un bloque girado: usa sus 4 esquinas (x0,y0,...,x3,y3). */
    fun sampleQuad(b: Bitmap, c: FloatArray): Int {
        val cx = (c[0] + c[2] + c[4] + c[6]) / 4f
        val cy = (c[1] + c[3] + c[5] + c[7]) / 4f
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        fun addPt(px: Float, py: Float) {
            val dx = cx - px
            val dy = cy - py
            val d = max(1f, hypot(dx, dy))
            val x = (px + dx / d * 3f).roundToInt().coerceIn(0, b.width - 1)
            val y = (py + dy / d * 3f).roundToInt().coerceIn(0, b.height - 1)
            val col = b.getPixel(x, y)
            rs.add(Color.red(col))
            gs.add(Color.green(col))
            bs.add(Color.blue(col))
        }
        for (i in 0 until 4) addPt(c[i * 2], c[i * 2 + 1])
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            addPt((c[i * 2] + c[j * 2]) / 2f, (c[i * 2 + 1] + c[j * 2 + 1]) / 2f)
        }
        rs.sort(); gs.sort(); bs.sort()
        val m = rs.size / 2
        return Color.rgb(rs[m], gs[m], bs[m])
    }
}

/** La burbuja flotante: tocar, mantener pulsado o arrastrar. */
class BubbleView(ctx: Context) : FrameLayout(ctx) {
    private val label = TextView(ctx)
    private val bg = GradientDrawable()
    private val main = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longFired = false
    private var pulse: ObjectAnimator? = null

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onDrag: ((Int, Int) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    var active: Boolean = false
        set(v) {
            field = v
            paint()
        }

    var busy: Boolean = false
        set(v) {
            field = v
            if (v) startPulse() else stopPulse()
        }

    private val longPress = Runnable {
        if (!dragging) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }
    }

    init {
        bg.shape = GradientDrawable.OVAL
        bg.orientation = GradientDrawable.Orientation.TL_BR
        background = bg
        label.text = "文A"
        label.setTextColor(Color.WHITE)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        label.typeface = Typeface.DEFAULT_BOLD
        label.gravity = Gravity.CENTER
        addView(
            label,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        paint()
    }

    private fun paint() {
        bg.colors = if (active) {
            intArrayOf(0xFF10B981.toInt(), 0xFF22D3EE.toInt())
        } else {
            intArrayOf(0xFF7C6CFF.toInt(), 0xFF22D3EE.toInt())
        }
        bg.setStroke(context.dp(2f), if (active) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
    }

    private fun startPulse() {
        pulse?.cancel()
        pulse = ObjectAnimator.ofFloat(label, View.ALPHA, 1f, 0.3f).apply {
            duration = 520
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        label.alpha = 1f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                lastX = downX
                lastY = downY
                dragging = false
                longFired = false
                main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                    dragging = true
                    main.removeCallbacks(longPress)
                }
                if (dragging) {
                    onDrag?.invoke((e.rawX - lastX).roundToInt(), (e.rawY - lastY).roundToInt())
                    lastX = e.rawX
                    lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                if (dragging) onDragEnd?.invoke() else if (!longFired) onTap?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                if (dragging) onDragEnd?.invoke()
            }
        }
        return true
    }
}

/** Pantalla para colocar y ajustar el rectángulo del diálogo del juego. */
class RegionEditorView(ctx: Context) : View(ctx) {
    var onDone: ((RectF) -> Unit)? = null // fracciones 0..1
    var onCancel: (() -> Unit)? = null

    private val box = RectF()
    private var initFrac: RectF? = null

    private val dim = Paint().apply { color = Color.argb(150, 6, 8, 14) }
    private val fillP = Paint().apply { color = Color.argb(34, 124, 108, 255) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(2.5f)
        color = 0xFF9D93FF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(ctx.dpf(12f), ctx.dpf(7f)), 0f)
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knobEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(3f)
        color = 0xFF7C6CFF.toInt()
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = ctx.spf(14f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE5E7EF.toInt()
        textSize = ctx.spf(13f)
        textAlign = Paint.Align.CENTER
    }
    private val btnFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val saveBtn = RectF()
    private val cancelBtn = RectF()

    private val knobR = ctx.dpf(11f)
    private val hit = ctx.dpf(34f)
    private val minS = ctx.dpf(90f)

    private var mode = 0 // 0 nada, 1 mover, 2 redimensionar
    private var corner = 0 // 1 TL, 2 TR, 3 BL, 4 BR
    private var pressed = 0 // 1 guardar, 2 cancelar
    private var lx = 0f
    private var ly = 0f

    fun setFraction(l: Float, t: Float, r: Float, b: Float) {
        initFrac = RectF(l, t, r, b)
        applyInit()
    }

    private fun applyInit() {
        val f = initFrac ?: return
        if (width <= 0 || height <= 0) return
        box.set(f.left * width, f.top * height, f.right * width, f.bottom * height)
        initFrac = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyInit()
    }

    private fun layoutButtons() {
        val bw = context.dpf(124f)
        val bh = context.dpf(46f)
        val gap = context.dpf(14f)
        val top = if (box.centerY() > height / 2f) context.dpf(54f) else height - context.dpf(54f) - bh
        val left = (width - (bw * 2 + gap)) / 2f
        cancelBtn.set(left, top, left + bw, top + bh)
        saveBtn.set(left + bw + gap, top, left + bw * 2 + gap, top + bh)
    }

    private fun corners(): Array<PointF> = arrayOf(
        PointF(box.left, box.top), PointF(box.right, box.top),
        PointF(box.left, box.bottom), PointF(box.right, box.bottom)
    )

    private fun drawButton(c: Canvas, r: RectF, color: Int, label: String) {
        btnFill.color = color
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, btnFill)
        val fm = txt.fontMetrics
        c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, txt)
    }

    override fun onDraw(c: Canvas) {
        if (box.isEmpty) return
        c.save()
        c.clipOutRect(box)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        c.restore()
        c.drawRect(box, fillP)
        c.drawRoundRect(box, context.dpf(6f), context.dpf(6f), edge)
        for (p in corners()) {
            c.drawCircle(p.x, p.y, knobR, knob)
            c.drawCircle(p.x, p.y, knobR, knobEdge)
        }
        layoutButtons()
        drawButton(c, cancelBtn, 0xFF2A2F3A.toInt(), "Cancelar")
        drawButton(c, saveBtn, 0xFF7C6CFF.toInt(), "Guardar")
        val hy = if (cancelBtn.top < height / 2f) cancelBtn.bottom + context.dpf(26f) else cancelBtn.top - context.dpf(14f)
        c.drawText("Cubre la caja de texto del juego", width / 2f, hy, hint)
    }

    private fun nearestCorner(x: Float, y: Float): Int {
        var best = 0
        var bestD = hit
        for ((i, p) in corners().withIndex()) {
            val d = hypot(x - p.x, y - p.y)
            if (d <= bestD) {
                bestD = d
                best = i + 1
            }
        }
        return best
    }

    private fun move(dx: Float, dy: Float) {
        val nl = clampF(box.left + dx, 0f, width - box.width())
        val nt = clampF(box.top + dy, 0f, height - box.height())
        box.offsetTo(nl, nt)
    }

    private fun resize(dx: Float, dy: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (corner) {
            1 -> {
                box.left = clampF(box.left + dx, 0f, box.right - minS)
                box.top = clampF(box.top + dy, 0f, box.bottom - minS)
            }
            2 -> {
                box.right = clampF(box.right + dx, box.left + minS, w)
                box.top = clampF(box.top + dy, 0f, box.bottom - minS)
            }
            3 -> {
                box.left = clampF(box.left + dx, 0f, box.right - minS)
                box.bottom = clampF(box.bottom + dy, box.top + minS, h)
            }
            4 -> {
                box.right = clampF(box.right + dx, box.left + minS, w)
                box.bottom = clampF(box.bottom + dy, box.top + minS, h)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                layoutButtons()
                pressed = when {
                    saveBtn.contains(x, y) -> 1
                    cancelBtn.contains(x, y) -> 2
                    else -> 0
                }
                if (pressed == 0) {
                    corner = nearestCorner(x, y)
                    mode = if (corner != 0) 2 else if (box.contains(x, y)) 1 else 0
                }
                lx = x
                ly = y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lx
                val dy = y - ly
                lx = x
                ly = y
                if (pressed == 0) {
                    if (mode == 1) move(dx, dy) else if (mode == 2) resize(dx, dy)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pressed == 1 && saveBtn.contains(x, y)) {
                    onDone?.invoke(
                        RectF(box.left / width, box.top / height, box.right / width, box.bottom / height)
                    )
                } else if (pressed == 2 && cancelBtn.contains(x, y)) {
                    onCancel?.invoke()
                }
                pressed = 0
                mode = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = 0
                mode = 0
            }
        }
        return true
    }
}

/** Barra con la traducción del modo diálogo. */
class SubtitleView(ctx: Context) : LinearLayout(ctx) {
    private val nameTv = TextView(ctx)
    private val bodyTv = TextView(ctx)

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0xFF101319.toInt())
            cornerRadius = ctx.dpf(14f)
            setStroke(ctx.dp(1f), 0x33FFFFFF)
        }
        setPadding(ctx.dp(14f), ctx.dp(10f), ctx.dp(14f), ctx.dp(12f))

        nameTv.setTextColor(0xFF67E8F9.toInt())
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        nameTv.typeface = Typeface.DEFAULT_BOLD

        bodyTv.setTextColor(Color.WHITE)
        bodyTv.setLineSpacing(0f, 1.12f)

        addView(nameTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bodyTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun show(name: String?, text: String, sizeSp: Float) {
        if (name.isNullOrBlank()) {
            nameTv.visibility = View.GONE
        } else {
            nameTv.visibility = View.VISIBLE
            nameTv.text = name
        }
        bodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        bodyTv.text = text
    }
}

/** Menú que aparece al mantener pulsada la burbuja. */
class MenuView(
    ctx: Context,
    items: List<Pair<String, () -> Unit>>,
    private val dismiss: () -> Unit,
) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0xF4161A22.toInt())
            cornerRadius = ctx.dpf(16f)
            setStroke(ctx.dp(1f), 0x33FFFFFF)
        }
        setPadding(0, ctx.dp(6f), 0, ctx.dp(6f))
        for ((label, action) in items) {
            val tv = TextView(ctx)
            tv.text = label
            tv.setTextColor(Color.WHITE)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            tv.setPadding(ctx.dp(18f), ctx.dp(13f), ctx.dp(22f), ctx.dp(13f))
            tv.isClickable = true
            tv.setOnClickListener {
                dismiss()
                action()
            }
            addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismiss()
                true
            } else {
                false
            }
        }
    }
}

/** Traducción dibujada sobre el texto original (modo "tocar", cámara en vivo y foto). */
class TranslationOverlayView(ctx: Context) : View(ctx) {
    private class Pre(
        val box: Rect,
        val corners: FloatArray?,
        val text: String,
        val lines: Int,
        val bg: Int,
        val fg: Int,
    )

    private class Item(
        val x: Float,
        val y: Float,
        val angle: Float,
        val w: Float,
        val h: Float,
        val layout: StaticLayout,
        val bg: Int,
        val dx: Float,
        val dy: Float,
    )

    private var pre: List<Pre> = emptyList()
    private var shotW = 1
    private var shotH = 1
    private var fit = 0 // 0 = estirar a la vista, 1 = cubrir (cámara en vivo), 2 = encajar (foto)
    private var photo: Bitmap? = null
    private val dest = RectF()
    private val tmp = RectF()
    private var items: List<Item> = emptyList()
    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad = ctx.dpf(3f)

    var onDismiss: (() -> Unit)? = null
    var consumeTouch = true

    private fun fgFor(bg: Int): Int = if (Colors.luma(bg) > 150f) 0xFF16181D.toInt() else Color.WHITE

    /** ¿El texto está girado respecto a la pantalla? (más de 4 grados) */
    private fun isRotated(c: FloatArray?): Boolean {
        if (c == null || c.size < 8) return false
        val a = Math.toDegrees(atan2((c[3] - c[1]).toDouble(), (c[2] - c[0]).toDouble()))
        return abs(a) > 4.0
    }

    private fun bgFor(bmp: Bitmap, b: TBlock): Int {
        val c = b.corners
        return if (c != null && isRotated(c)) Colors.sampleQuad(bmp, c) else Colors.sample(bmp, b.box)
    }

    /** Captura de pantalla (la imagen ocupa toda la vista). */
    fun setBlocks(blocks: List<TBlock>, shot: Bitmap) {
        shotW = max(1, shot.width)
        shotH = max(1, shot.height)
        fit = 0
        photo = null
        pre = blocks.map { b ->
            val bg = bgFor(shot, b)
            Pre(b.box, b.corners, b.translated, b.lines, bg, fgFor(bg))
        }
        build()
        invalidate()
    }

    /** Cámara en vivo: las cajas vienen en coordenadas de la imagen de análisis. */
    fun setLive(blocks: List<TBlock>, imgW: Int, imgH: Int) {
        shotW = max(1, imgW)
        shotH = max(1, imgH)
        fit = 1
        photo = null
        pre = blocks.map { b ->
            Pre(b.box, b.corners, b.translated, b.lines, 0xE60F1115.toInt(), Color.WHITE)
        }
        build()
        invalidate()
    }

    /** Foto temporal (solo en memoria) con la traducción encima. */
    fun setPhoto(bmp: Bitmap, blocks: List<TBlock>) {
        photo = bmp
        shotW = max(1, bmp.width)
        shotH = max(1, bmp.height)
        fit = 2
        pre = blocks.map { b ->
            val bg = bgFor(bmp, b)
            Pre(b.box, b.corners, b.translated, b.lines, bg, fgFor(bg))
        }
        build()
        invalidate()
    }

    fun clear() {
        pre = emptyList()
        items = emptyList()
        photo = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        build()
    }

    private fun layoutFor(text: String, sizePx: Float, w: Float, color: Int): StaticLayout {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.color = color
        tp.textSize = sizePx
        tp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, max(1, w.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    /** Elige el tamaño de letra más grande que cabe en la caja. */
    private fun fitText(p: Pre, availW: Float, bh: Float, minPx: Float, maxPx: Float): StaticLayout {
        val lineH = bh / max(1, p.lines)
        var size = (lineH * 0.78f).coerceIn(minPx, maxPx)
        var layout = layoutFor(p.text, size, availW - pad * 2, p.fg)
        var guard = 0
        while (layout.height + pad * 2 > bh * 1.1f && size > minPx && guard < 60) {
            size = max(minPx, size - 1f)
            layout = layoutFor(p.text, size, availW - pad * 2, p.fg)
            guard++
        }
        return layout
    }

    private fun build() {
        if (width <= 0 || height <= 0) {
            items = emptyList()
            return
        }
        val vw = width.toFloat()
        val vh = height.toFloat()
        val sx: Float
        val sy: Float
        val offX: Float
        val offY: Float
        when (fit) {
            1 -> {
                val k = max(vw / shotW, vh / shotH)
                sx = k
                sy = k
                offX = (vw - shotW * k) / 2f
                offY = (vh - shotH * k) / 2f
            }
            2 -> {
                val k = min(vw / shotW, vh / shotH)
                sx = k
                sy = k
                offX = (vw - shotW * k) / 2f
                offY = (vh - shotH * k) / 2f
            }
            else -> {
                sx = vw / shotW
                sy = vh / shotH
                offX = 0f
                offY = 0f
            }
        }
        dest.set(offX, offY, offX + shotW * sx, offY + shotH * sy)
        if (pre.isEmpty()) {
            items = emptyList()
            return
        }

        val minPx = context.spf(9f)
        val maxPx = context.spf(26f)
        val out = ArrayList<Item>(pre.size)
        for (p in pre) {
            val bl = offX + p.box.left * sx
            val bt = offY + p.box.top * sy
            val br = offX + p.box.right * sx
            val bb = offY + p.box.bottom * sy
            if (br < 0f || bl > vw || bb < 0f || bt > vh) continue

            val c = p.corners
            if (c != null && isRotated(c)) {
                // Texto girado: se dibuja en el mismo eje que el original.
                val x0 = offX + c[0] * sx
                val y0 = offY + c[1] * sy
                val x1 = offX + c[2] * sx
                val y1 = offY + c[3] * sy
                val x3 = offX + c[6] * sx
                val y3 = offY + c[7] * sy
                val len = hypot(x1 - x0, y1 - y0)
                val hei = hypot(x3 - x0, y3 - y0)
                val ang = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
                val bw = len + pad * 2
                val bh = hei + pad * 2
                val layout = fitText(p, bw, bh, minPx, maxPx)
                val rh = max(bh, layout.height + pad * 2)
                val rad = Math.toRadians(ang.toDouble())
                val cs = cos(rad).toFloat()
                val sn = sin(rad).toFloat()
                val ox = x0 - (pad * cs - pad * sn)
                val oy = y0 - (pad * sn + pad * cs)
                val dy = (rh - pad * 2 - layout.height) / 2f
                out.add(Item(ox, oy, ang, bw, rh, layout, p.bg, 0f, dy))
            } else {
                val left = bl - pad
                val top = bt - pad
                val bw = p.box.width() * sx + pad * 2
                val bh = p.box.height() * sy + pad * 2
                val roomRight = vw - left - context.dpf(4f)
                val availW = if (p.lines <= 1) max(bw, min(bw * 1.6f, roomRight)) else bw
                val layout = fitText(p, availW, bh, minPx, maxPx)
                var textW = 0f
                for (i in 0 until layout.lineCount) textW = max(textW, layout.getLineWidth(i))
                val rw = min(max(bw, textW + pad * 2 + context.dpf(4f)), max(bw, vw - left))
                val rh = max(bh, layout.height + pad * 2)
                val dx = if (p.lines <= 1 && textW + pad * 2 < bw) (bw - pad * 2 - textW) / 2f else 0f
                val dy = (rh - pad * 2 - layout.height) / 2f
                out.add(Item(left, top, 0f, rw, rh, layout, p.bg, dx, dy))
            }
        }
        items = out
    }

    override fun onDraw(c: Canvas) {
        val ph = photo
        if (ph != null) c.drawBitmap(ph, null, dest, null)
        val rad = context.dpf(6f)
        for (item in items) {
            paintBg.color = item.bg
            c.save()
            c.translate(item.x, item.y)
            if (item.angle != 0f) c.rotate(item.angle)
            tmp.set(0f, 0f, item.w, item.h)
            c.drawRoundRect(tmp, rad, rad, paintBg)
            c.translate(pad + item.dx, pad + item.dy)
            item.layout.draw(c)
            c.restore()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!consumeTouch) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) onDismiss?.invoke()
        return true
    }
}
