package com.bubblelens.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bubblelens.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupLanguages()
        setupModes()
        setupSliders()

        b.btnStart.setOnClickListener { onStartClicked() }
        b.permOverlayBtn.setOnClickListener { openOverlaySettings() }
        b.permNotifBtn.setOnClickListener { askNotifications() }
        b.btnDownload.setOnClickListener { downloadModels() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                OverlayService.running.collect { refresh() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // El modo puede haber cambiado desde la burbuja.
        b.modeGroup.check(if (Prefs.mode == "auto") R.id.modeAuto else R.id.modeTap)
        updateModeDesc()
        refresh()
    }

    // ---------------------------------------------------------------- estado

    private fun refresh() {
        val running = OverlayService.running.value
        b.statusText.text = if (running) "Burbuja activa ✓" else "Burbuja apagada"
        b.btnStart.text = if (running) "Detener burbuja" else "Iniciar burbuja"

        val overlayOk = Settings.canDrawOverlays(this)
        b.permOverlayText.text =
            if (overlayOk) "✓  Mostrar sobre otras apps" else "Mostrar sobre otras apps (necesario)"
        b.permOverlayBtn.visibility = if (overlayOk) View.GONE else View.VISIBLE

        val notifOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        b.permNotifText.text = if (notifOk) "✓  Notificaciones" else "Notificaciones (recomendado)"
        b.permNotifBtn.visibility = if (notifOk) View.GONE else View.VISIBLE

        refreshLangStatus()
    }

    private fun onStartClicked() {
        if (OverlayService.running.value) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Primero concede «Mostrar sobre otras apps»", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        startActivity(Intent(this, ProjectionRequestActivity::class.java))
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---------------------------------------------------------------- idiomas

    private fun setupLanguages() {
        b.srcDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, Langs.sources.map { it.name })
        )
        b.srcDropdown.setText(Langs.name(Prefs.src), false)
        b.srcDropdown.setOnItemClickListener { _, _, pos, _ ->
            Prefs.src = Langs.sources[pos].code
            refreshLangStatus()
        }

        b.tgtDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, Langs.targets.map { it.name })
        )
        b.tgtDropdown.setText(Langs.name(Prefs.tgt), false)
        b.tgtDropdown.setOnItemClickListener { _, _, pos, _ ->
            Prefs.tgt = Langs.targets[pos].code
            refreshLangStatus()
        }
    }

    private fun refreshLangStatus() {
        val src = Prefs.src
        val tgt = Prefs.tgt
        if (src == tgt) {
            b.langStatus.text = "Elige idiomas distintos."
            return
        }
        lifecycleScope.launch {
            val ok = try {
                Engine.isReady(src, tgt)
            } catch (e: Exception) {
                false
            }
            if (ok) Prefs.addReady(src)
            b.langStatus.text = if (ok) {
                "✓ Listo para usar sin internet"
            } else {
                "Falta descargar (≈30–60 MB por idioma, solo la primera vez)"
            }
        }
    }

    private fun downloadModels() {
        val src = Prefs.src
        val tgt = Prefs.tgt
        if (src == tgt) {
            Toast.makeText(this, "Elige idiomas distintos", Toast.LENGTH_SHORT).show()
            return
        }
        b.langProgress.visibility = View.VISIBLE
        b.btnDownload.isEnabled = false
        b.langStatus.text = "Descargando…"
        lifecycleScope.launch {
            try {
                Engine.download(src, tgt)
                Prefs.addReady(src)
                Toast.makeText(this@MainActivity, "Listo ✓", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "No se pudo descargar. ¿Tienes internet?", Toast.LENGTH_LONG).show()
            } finally {
                b.langProgress.visibility = View.GONE
                b.btnDownload.isEnabled = true
                refreshLangStatus()
            }
        }
    }

    // ---------------------------------------------------------------- modo

    private fun updateModeDesc() {
        b.modeDesc.text = if (Prefs.mode == "auto") {
            "Eliges la región del diálogo y la app traduce sola cada vez que el texto cambia. Ideal para juegos."
        } else {
            "Tocas la burbuja y se traduce todo lo que ves, dibujado sobre el texto original. Tócala otra vez para quitarlo."
        }
    }

    private fun setupModes() {
        b.modeGroup.check(if (Prefs.mode == "auto") R.id.modeAuto else R.id.modeTap)
        updateModeDesc()
        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                Prefs.mode = if (id == R.id.modeAuto) "auto" else "tap"
                updateModeDesc()
            }
        }

        b.speedGroup.check(
            when (Prefs.speed) {
                0 -> R.id.speedFast
                2 -> R.id.speedSave
                else -> R.id.speedNormal
            }
        )
        b.speedGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                Prefs.speed = when (id) {
                    R.id.speedFast -> 0
                    R.id.speedSave -> 2
                    else -> 1
                }
            }
        }
    }

    // ---------------------------------------------------------------- apariencia

    private fun setupSliders() {
        b.sliderText.value = Prefs.textSize.coerceIn(12f, 30f).roundToInt().toFloat()
        b.textSizeLabel.text = "Tamaño del texto traducido: ${b.sliderText.value.roundToInt()}"
        b.sliderText.addOnChangeListener { _, value, fromUser ->
            b.textSizeLabel.text = "Tamaño del texto traducido: ${value.roundToInt()}"
            if (fromUser) Prefs.textSize = value
        }

        val pct = (((Prefs.bubbleAlpha * 100f) / 5f).roundToInt() * 5).coerceIn(40, 100)
        b.sliderAlpha.value = pct.toFloat()
        b.alphaLabel.text = "Opacidad de la burbuja: $pct%"
        b.sliderAlpha.addOnChangeListener { _, value, fromUser ->
            b.alphaLabel.text = "Opacidad de la burbuja: ${value.roundToInt()}%"
            if (fromUser) Prefs.bubbleAlpha = value / 100f
        }
    }
}
