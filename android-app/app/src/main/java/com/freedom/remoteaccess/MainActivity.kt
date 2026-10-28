package com.freedom.remoteaccess

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Na primeira abertura (sem pareamento salvo), manda para o QRScanActivity.
 * Depois de pareado, pede o compartilhamento de tela (diálogo padrão do
 * Android — ver README sobre o que falta para isso ser silencioso) e sobe
 * o ScreenCaptureService com a config lida do QR.
 */
class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_CAPTURE = 42
    private val REQUEST_CODE_PAIR = 43
    private val REQUEST_CODE_LOCATION = 44
    private val REQUEST_CODE_AUDIO = 45

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!DeviceConfig.isPaired(this)) {
            startActivityForResult(Intent(this, QRScanActivity::class.java), REQUEST_CODE_PAIR)
            return
        }

        ensureAudioPermission()
        ensureLocationPermissionAndSchedule()
        requestScreenCapture()
    }

    private fun ensureAudioPermission() {
        val granted = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        if (!granted) {
            // Só pede UMA vez — depois de concedida, o Android nunca mais
            // pergunta de novo (diferente da tela, que pede a cada sessão).
            // Em aparelho gerenciado pelo Headwind, o Device Owner pode
            // conceder isso sem diálogo nenhum via setPermissionGrantState.
            //
            // Enquanto o microfone estiver em uso, o PRÓPRIO Android (a
            // partir da versão 12) mostra um indicador verde na barra de
            // status — isso não é algo que o app controla, nem consegue
            // desligar, é o sistema operacional avisando por conta própria.
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_AUDIO
            )
        }
    }

    private fun ensureLocationPermissionAndSchedule() {
        val granted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        if (!granted) {
            // Em app comum, abre o diálogo normal do Android aqui.
            // Em aparelho gerenciado pelo Headwind, o Device Owner pode
            // conceder isso sem diálogo via setPermissionGrantState — nesse
            // caso esta chamada já volta como "concedida" e não aparece nada.
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQUEST_CODE_LOCATION
            )
        }
        startForegroundService(Intent(this, LocationTrackingService::class.java))
    }

    private fun requestScreenCapture() {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_CODE_CAPTURE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_PAIR) {
            if (DeviceConfig.isPaired(this)) {
                requestScreenCapture()
            } else {
                finish() // usuário saiu sem parear
            }
            return
        }

        if (requestCode == REQUEST_CODE_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_DEVICE_ID, DeviceConfig.deviceId(this@MainActivity))
                putExtra(ScreenCaptureService.EXTRA_SUPABASE_URL, DeviceConfig.supabaseUrl(this@MainActivity))
                putExtra(ScreenCaptureService.EXTRA_SUPABASE_KEY, DeviceConfig.supabaseKey(this@MainActivity))
            }
            startForegroundService(serviceIntent)
        }
        finish()
    }
}
