package com.bubblelens.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

data class OcrBlock(val text: String, val box: Rect, val lines: Int, val corners: FloatArray? = null)

/** Un bloque de texto de la pantalla ya traducido (modo "tocar"). */
data class TBlock(
    val original: String,
    val translated: String,
    val box: Rect,
    val lines: Int,
    val corners: FloatArray? = null,
)

/** Texto leído de una imagen, con el idioma (elegido o detectado). */
data class ReadResult(val lang: String, val script: Script, val blocks: List<OcrBlock>)

/** Resultado del modo diálogo. [key] sirve para no repetir la misma traducción. */
data class RegionResult(val name: String?, val text: String, val key: String)

object Engine {
    private val recognizers = HashMap<Script, TextRecognizer>()
    private val translators = HashMap<String, Translator>()
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 250
    }
    private val langId by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.7f).build()
        )
    }

    // ---------- OCR ----------

    private fun recognizer(s: Script): TextRecognizer = recognizers.getOrPut(s) {
        when (s) {
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
    }

    private suspend fun ocr(bmp: Bitmap, script: Script): List<OcrBlock> =
        ocr(InputImage.fromBitmap(bmp, 0), script)

    private suspend fun ocr(input: InputImage, script: Script): List<OcrBlock> {
        val result = recognizer(script).process(input).await()
        val cjk = script != Script.LATIN
        val out = ArrayList<OcrBlock>()
        for (b in result.textBlocks) {
            val box = b.boundingBox ?: continue
            val text = Cleaner.joinLines(b.lines.map { it.text }, cjk)
            if (text.isBlank()) continue
            val cp = b.cornerPoints
            val corners: FloatArray? = if (cp != null && cp.size == 4) {
                floatArrayOf(
                    cp[0].x.toFloat(), cp[0].y.toFloat(), cp[1].x.toFloat(), cp[1].y.toFloat(),
                    cp[2].x.toFloat(), cp[2].y.toFloat(), cp[3].x.toFloat(), cp[3].y.toFloat()
                )
            } else {
                null
            }
            out.add(OcrBlock(text, box, b.lines.size, corners))
        }
        return out
    }

    // ---------- Modelos de idioma ----------

    private fun translator(src: String, tgt: String): Translator =
        translators.getOrPut("$src>$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
            )
        }

    suspend fun download(src: String, tgt: String) {
        translator(src, tgt).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    suspend fun isReady(src: String, tgt: String): Boolean {
        val mm = RemoteModelManager.getInstance()
        val need = if (src == "en" || tgt == "en") listOf(src, tgt) else listOf(src, tgt, "en")
        for (code in need) {
            val ok = mm.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await()
            if (!ok) return false
        }
        return true
    }

    private suspend fun detect(text: String): String =
        try {
            langId.identifyLanguage(text).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "und"
        }

    // ---------- Traducción ----------

    private suspend fun translate(text: String, src: String, tgt: String): String {
        val key = "$src>$tgt|$text"
        synchronized(cache) { cache[key] }?.let { return it }
        val raw = translator(src, tgt).translate(text).await()
        val polished = Cleaner.polish(raw, tgt)
        synchronized(cache) { cache[key] = polished }
        return polished
    }

    /** Párrafos largos se parten por oraciones para no perder calidad. */
    private suspend fun translateLong(text: String, src: String, tgt: String): String {
        if (text.length <= 480) return translate(text, src, tgt)
        val sentences = text.split(Regex("(?<=[.!?。！？…])\\s*")).filter { it.isNotBlank() }
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length > 420) {
                chunks.add(sb.toString().trim())
                sb.setLength(0)
            }
            sb.append(s).append(' ')
        }
        if (sb.isNotBlank()) chunks.add(sb.toString().trim())
        val glue = if (tgt == "ja" || tgt == "zh") "" else " "
        val translated = ArrayList<String>()
        for (chunk in chunks) translated.add(translate(chunk, src, tgt))
        return translated.joinToString(glue)
    }

    // ---------- Lectura con idioma fijo o automático ----------

    private fun baseLang(tag: String): String? {
        if (tag == "und" || tag.contains("Latn", ignoreCase = true)) return null
        val base = tag.substringBefore('-')
        return if (TranslateLanguage.fromLanguageTag(base) != null) base else null
    }

    private fun defaultLang(script: Script): String = when (script) {
        Script.LATIN -> "en"
        Script.JAPANESE -> "ja"
        Script.KOREAN -> "ko"
        Script.CHINESE -> "zh"
    }

    private suspend fun readScript(input: InputImage, script: Script): List<OcrBlock> {
        val cjk = script != Script.LATIN
        return ocr(input, script).filter { Cleaner.worthTranslating(it.text, cjk) }
    }

    /** Lee el texto de una imagen. [srcPref] = código de idioma o "auto" para detectarlo. */
    suspend fun read(input: InputImage, srcPref: String, hint: Script?): ReadResult? {
        if (srcPref != "auto") {
            val l = Langs.byCode(srcPref)
            val good = readScript(input, l.script)
            return if (good.isEmpty()) null else ReadResult(l.code, l.script, good)
        }
        val order = ArrayList<Script>()
        if (hint != null) order.add(hint)
        for (s in listOf(Script.LATIN, Script.JAPANESE, Script.KOREAN, Script.CHINESE)) {
            if (s !in order) order.add(s)
        }
        var fallback: ReadResult? = null
        for (script in order) {
            val good = readScript(input, script)
            if (good.isEmpty()) continue
            val sample = good.joinToString(" ") { it.text }
            val lang = baseLang(detect(sample))
            if (lang != null) {
                val expected = when (lang) {
                    "ja" -> Script.JAPANESE
                    "ko" -> Script.KOREAN
                    "zh" -> Script.CHINESE
                    else -> null
                }
                if (expected == null && script == Script.LATIN) return ReadResult(lang, script, good)
                if (expected == null) return ReadResult(lang, script, good)
                if (expected == script) return ReadResult(lang, script, good)
                if (script == Script.LATIN) {
                    // texto latino "leído" de algo que parece asiático: seguir probando
                    continue
                }
                val again = readScript(input, expected)
                return ReadResult(lang, expected, if (again.isEmpty()) good else again)
            }
            if (fallback == null) fallback = ReadResult(defaultLang(script), script, good)
        }
        return fallback
    }

    /** Traduce los bloques ya leídos. */
    suspend fun translateRead(r: ReadResult, tgt: String, maxBlocks: Int = Int.MAX_VALUE): List<TBlock> {
        if (r.lang == tgt) return emptyList()
        val out = ArrayList<TBlock>()
        val blocks = if (r.blocks.size > maxBlocks) {
            r.blocks.sortedByDescending { it.box.width() * it.box.height() }.take(maxBlocks)
        } else {
            r.blocks
        }
        for (b in blocks) {
            if (detect(b.text) == tgt) continue // ya está en tu idioma
            val t = try {
                translateLong(b.text, r.lang, tgt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (t.isBlank() || t.equals(b.text, ignoreCase = true)) continue
            out.add(TBlock(b.text, t, b.box, b.lines, b.corners))
        }
        return out
    }

    /** Modo "tocar": traduce todos los bloques de texto de la captura. */
    suspend fun translateScreen(bmp: Bitmap, src: String, tgt: String): List<TBlock> {
        val r = read(InputImage.fromBitmap(bmp, 0), src, null) ?: return emptyList()
        return translateRead(r, tgt)
    }

    /** Modo diálogo: une el texto de la región en una sola frase con contexto y la traduce. */
    suspend fun translateRegion(bmp: Bitmap, src: String, tgt: String): RegionResult? {
        val script = Langs.byCode(src).script
        val cjk = script != Script.LATIN
        val blocks = ocr(bmp, script).sortedBy { it.box.top }
        if (blocks.isEmpty()) return null

        var name: String? = null
        val body = ArrayList<String>()
        for ((i, b) in blocks.withIndex()) {
            if (i == 0 && blocks.size > 1 && Cleaner.looksLikeName(b.text, cjk)) {
                name = b.text.trim().trimEnd(':', '：')
            } else {
                body.add(b.text)
            }
        }
        val joined = body.joinToString(if (cjk) "" else " ").trim()
        if (!Cleaner.worthTranslating(joined, cjk)) return null
        if (src != tgt && detect(joined) == tgt) return null

        val tr = translateLong(joined, src, tgt)
        if (tr.isBlank()) return null
        return RegionResult(name, tr, (name ?: "") + "|" + joined)
    }
}

object Cleaner {
    private fun isAsciiWord(c: Char) = c.code < 128 && c.isLetterOrDigit()

    /** Une las líneas del OCR en un párrafo (arregla guiones de fin de línea). */
    fun joinLines(lines: List<String>, cjk: Boolean): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(line)
                continue
            }
            val last = sb[sb.length - 1]
            when {
                cjk -> {
                    if (isAsciiWord(last) && isAsciiWord(line[0])) sb.append(' ')
                    sb.append(line)
                }
                last == '-' && sb.length >= 2 && sb[sb.length - 2].isLetter() && line[0].isLowerCase() -> {
                    sb.setLength(sb.length - 1)
                    sb.append(line)
                }
                else -> sb.append(' ').append(line)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Descarta números, iconos y basura del OCR. */
    fun worthTranslating(t: String, cjk: Boolean): Boolean {
        val s = t.trim()
        if (s.isEmpty()) return false
        val letters = s.count { it.isLetter() }
        if (letters < (if (cjk) 1 else 2)) return false
        return letters.toFloat() / s.length >= 0.4f
    }

    /** Detecta el nombre del personaje que suele ir encima del diálogo. */
    fun looksLikeName(t: String, cjk: Boolean): Boolean {
        val s = t.trim()
        if (s.isEmpty()) return false
        if (s.last() in ".!?…。！？,，、") return false
        return if (cjk) {
            s.length <= 8 && s.none { it in "。！？「」『』…" }
        } else {
            val words = s.split(' ').filter { it.isNotEmpty() }
            words.size <= 2 && s.length <= 20 && s.first().isUpperCase()
        }
    }

    /** Ajustes finos del texto traducido (espacios, ¿ ¡ del español). */
    fun polish(text: String, tgt: String): String {
        var s = text.trim().replace(Regex("\\s+"), " ")
        s = s.replace(Regex("\\s+([,.;:!?])"), "$1")
        if (tgt == "es") s = fixSpanishMarks(s)
        return s
    }

    private val sentenceSplit = Regex("(?<=[.!?…])\\s+")

    private fun fixSpanishMarks(s: String): String =
        s.split(sentenceSplit).joinToString(" ") { sent ->
            var x = sent
            val core = x.trimEnd('"', '”', '»', ')', '\'', '’')
            val lead = x.indexOfFirst { it.isLetterOrDigit() }.coerceAtLeast(0)
            if (core.endsWith("?") && !x.contains('¿')) {
                x = x.substring(0, lead) + "¿" + x.substring(lead)
            } else if (core.endsWith("!") && !x.contains('¡')) {
                x = x.substring(0, lead) + "¡" + x.substring(lead)
            }
            x
        }
}

/** Miniatura en grises para detectar si el texto de la región cambió (muy barato). */
object Thumb {
    private const val W = 80
    private const val H = 40

    fun of(src: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(src, W, H, true)
        val px = IntArray(W * H)
        s.getPixels(px, 0, W, 0, 0, W, H)
        if (s !== src) s.recycle()
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            px[i] = (r * 77 + g * 150 + b * 29) shr 8
        }
        return px
    }

    /** Fracción (0..1) de píxeles que cambiaron de forma notable. */
    fun diff(a: IntArray, b: IntArray): Float {
        var n = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 20) n++
        return n.toFloat() / a.size
    }
}
