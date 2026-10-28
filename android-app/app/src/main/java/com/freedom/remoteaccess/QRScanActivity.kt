package com.freedom.remoteaccess

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tela exibida só na primeira abertura do app (enquanto DeviceConfig não
 * tiver nada salvo). Aponta a câmera para o QR mostrado na tela de controle
 * do admin (botão "Parear novo aparelho") e, ao ler
 * {supabaseUrl, supabaseKey, deviceId}, salva e segue para o MainActivity.
 */
class QRScanActivity : AppCompatActivity() {

    private val REQUEST_CAMERA = 10
    private lateinit var cameraExecutor: ExecutorService
    private var jaProcessado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val previewView = PreviewView(this)
        val label = TextView(this).apply {
            text = "Aponte para o QR de pareamento gerado pelo admin"
            setPadding(24, 24, 24, 24)
        }
        val root = FrameLayout(this).apply {
            addView(previewView)
            addView(label)
        }
        setContentView(root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            startCamera(previewView)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera(findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as PreviewView)
        }
    }

    private fun startCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val scanner = BarcodeScanning.getClient()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy, scanner)
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImage(imageProxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !jaProcessado) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes -> handleBarcodes(barcodes) }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun handleBarcodes(barcodes: List<Barcode>) {
        val raw = barcodes.firstOrNull()?.rawValue ?: return
        if (jaProcessado) return

        try {
            val json = JSONObject(raw)
            val supabaseUrl = json.getString("supabaseUrl")
            val supabaseKey = json.getString("supabaseKey")
            val deviceId = json.getString("deviceId")

            jaProcessado = true
            DeviceConfig.save(this, supabaseUrl, supabaseKey, deviceId)
            finish() // volta para o MainActivity, que agora acha a config salva
        } catch (e: Exception) {
            // QR inválido/ilegível — continua tentando ler o próximo frame
        }
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
