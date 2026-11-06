package com.bubblelens.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("bubblelens", Context.MODE_PRIVATE)
        }
    }

    fun register(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregister(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    var src: String
        get() = sp.getString("src", "en") ?: "en"
        set(v) = sp.edit().putString("src", v).apply()

    var tgt: String
        get() = sp.getString("tgt", "es") ?: "es"
        set(v) = sp.edit().putString("tgt", v).apply()

    /** Idioma del texto en la cámara: "auto" = detectarlo solo */
    var camSrc: String
        get() = sp.getString("camSrc", "auto") ?: "auto"
        set(v) = sp.edit().putString("camSrc", v).apply()

    /** "tap" = traducir pantalla al tocar, "auto" = diálogo automático */
    var mode: String
        get() = sp.getString("mode", "tap") ?: "tap"
        set(v) = sp.edit().putString("mode", v).apply()

    /** 0 = rápida, 1 = normal, 2 = ahorro */
    var speed: Int
        get() = sp.getInt("speed", 1)
        set(v) = sp.edit().putInt("speed", v).apply()

    var textSize: Float
        get() = sp.getFloat("textSize", 18f)
        set(v) = sp.edit().putFloat("textSize", v).apply()

    var bubbleAlpha: Float
        get() = sp.getFloat("bubbleAlpha", 0.9f)
        set(v) = sp.edit().putFloat("bubbleAlpha", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bubbleX", -1)
        set(v) = sp.edit().putInt("bubbleX", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) = sp.edit().putInt("bubbleY", v).apply()

    /** Idiomas de origen cuyo paquete ya se descargó (para cambiar rápido desde la burbuja). */
    val readySrc: Set<String>
        get() = (sp.getStringSet("ready", emptySet()) ?: emptySet()).toSet()

    fun addReady(code: String) {
        val s = readySrc.toMutableSet()
        s.add(code)
        sp.edit().putStringSet("ready", s).apply()
    }

    fun region(landscape: Boolean): FloatArray? {
        val raw = sp.getString(if (landscape) "reg_l" else "reg_p", null) ?: return null
        val parts = raw.split(",").mapNotNull { it.toFloatOrNull() }
        return if (parts.size == 4) parts.toFloatArray() else null
    }

    fun setRegion(landscape: Boolean, l: Float, t: Float, r: Float, b: Float) {
        sp.edit().putString(if (landscape) "reg_l" else "reg_p", "$l,$t,$r,$b").apply()
    }
}
