package com.freedom.remoteaccess

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.presenceDataFlow
import io.github.jan.supabase.realtime.broadcastFlow
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.json.JSONObject

/**
 * Faz o papel que antes era do WebSocket próprio, só que usando o canal
 * Realtime do Supabase (broadcast + presence) — não precisa mais manter
 * nenhum servidor de sinalização seu rodando.
 *
 * NOTA: a API exata do pacote `realtime-kt` muda entre versões da biblioteca
 * supabase-kt; confira a versão instalada em build.gradle contra a
 * documentação oficial (https://supabase.com/docs/reference/kotlin/realtime)
 * se algum nome de método não bater.
 */
class SignalingClient(
    supabaseUrl: String,
    supabaseKey: String,
    private val deviceId: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onPeerReady()
        fun onRemoteSdpOffer(sdp: JSONObject)
        fun onRemoteIceCandidate(candidate: JSONObject)
        fun onControlMode(enabled: Boolean)
        fun onMicMute(muted: Boolean)
        fun onPeerLeft()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = createSupabaseClient(supabaseUrl, supabaseKey) {
        install(Realtime)
    }

    private val channel = client.channel("device-$deviceId")

    fun connect() {
        scope.launch {
            // Ouve os sinais que a página de controle manda
            channel.broadcastFlow<JsonObject>("signal").collect { payload ->
                handleIncoming(JSONObject(payload.toString()))
            }
        }

        scope.launch {
            // Sabe quando o controlador entrou no canal (presence)
            channel.presenceDataFlow<JsonObject>().collect { presences ->
                val hasController = presences.any {
                    it["role"]?.jsonPrimitive?.contentOrNull == "controller"
                }
                if (hasController) listener.onPeerReady()
            }
        }

        scope.launch {
            channel.subscribe()
            channel.track(buildJsonObject { put("role", "device") })
        }
    }

    private fun handleIncoming(msg: JSONObject) {
        when (msg.optString("type")) {
            "sdp-offer" -> listener.onRemoteSdpOffer(msg.getJSONObject("sdp"))
            "ice-candidate" -> listener.onRemoteIceCandidate(msg.getJSONObject("candidate"))
            "control-mode" -> listener.onControlMode(msg.optBoolean("enabled", false))
            "mic-mute" -> listener.onMicMute(msg.optBoolean("muted", true))
        }
    }

    fun sendSdpAnswer(sdp: JSONObject) = sendSignal("sdp-answer", mapOf("sdp" to sdp))
    fun sendIceCandidate(candidate: JSONObject) = sendSignal("ice-candidate", mapOf("candidate" to candidate))

    private fun sendSignal(type: String, extra: Map<String, Any>) {
        scope.launch {
            val obj = JSONObject(extra).apply { put("type", type) }
            channel.broadcast("signal", Json.parseToJsonElement(obj.toString()).jsonObject)
        }
    }

    fun close() {
        scope.launch { channel.unsubscribe() }
        scope.cancel()
    }
}
