package com.bubblelens.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Actividad transparente que pide permiso de captura y se la entrega al servicio. */
class ProjectionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_START)
                .putExtra(OverlayService.EXTRA_CODE, r.resultCode)
                .putExtra(OverlayService.EXTRA_DATA, data)
            startForegroundService(i)
        } else {
            Toast.makeText(this, "Sin permiso de captura no se puede traducir", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        launcher.launch(intent)
    }
}
