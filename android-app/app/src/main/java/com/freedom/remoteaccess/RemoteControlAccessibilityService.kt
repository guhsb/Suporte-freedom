package com.freedom.remoteaccess

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/**
 * Executa no aparelho os comandos de toque/gesto que chegam do controlador.
 *
 * Só age quando `controlEnabled == true`. Esse campo é atualizado por DOIS
 * caminhos independentes (defesa em profundidade):
 *   1. mensagem "control-mode" vinda da sinalização (ScreenCaptureService)
 *   2. o próprio comando no data channel poderia, em tese, ser ignorado aqui
 *      de novo se quisermos checar o estado local antes de cada execução
 *
 * Esse serviço precisa estar HABILITADO via política do Device Owner
 * (Headwind) para funcionar sem o usuário ir em Configurações > Acessibilidade.
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteControlAccessibilityService? = null
    }

    var controlEnabled: Boolean = false

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun handleCommand(json: JSONObject) {
        if (!controlEnabled) return // ignora qualquer comando se o controle estiver desligado

        when (json.optString("type")) {
            "tap" -> {
                val (x, y) = normalizedToPixels(json.getDouble("x"), json.getDouble("y"))
                dispatchTap(x, y)
            }
            "swipe" -> {
                val (x1, y1) = normalizedToPixels(json.getDouble("x1"), json.getDouble("y1"))
                val (x2, y2) = normalizedToPixels(json.getDouble("x2"), json.getDouble("y2"))
                dispatchSwipe(x1, y1, x2, y2, json.optLong("durationMs", 200))
            }
            "text" -> {
                // TODO: localizar o nó com foco (AccessibilityNodeInfo) e usar
                // ACTION_SET_TEXT para digitar o valor recebido.
            }
        }
    }

    private fun normalizedToPixels(nx: Double, ny: Double): Pair<Float, Float> {
        val metrics: DisplayMetrics = resources.displayMetrics
        return Pair((nx * metrics.widthPixels).toFloat(), (ny * metrics.heightPixels).toFloat())
    }

    private fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
