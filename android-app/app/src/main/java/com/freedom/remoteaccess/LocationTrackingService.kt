package com.freedom.remoteaccess

import android.app.*
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Calendar

/**
 * Pega a localização a cada 5 minutos (o WorkManager não permite intervalos
 * abaixo de 15 min, por isso é um serviço contínuo em vez de trabalho
 * periódico). Só GRAVA pontos dentro do expediente configurado abaixo —
 * fora desse horário, o serviço continua rodando mas não registra nada.
 *
 * Isso exige uma notificação de "localização em uso" por tempo indeterminado
 * — é o próprio Android que mostra o ícone de localização na barra de status
 * periodicamente enquanto um app acessa localização em segundo plano; não é
 * algo que dá (ou que deveria) ser escondido.
 */
class LocationTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "location_tracking"
        const val NOTIF_ID = 1002
        const val EXPEDIENTE_INICIO_HORA = 7
        const val EXPEDIENTE_INICIO_MIN = 30
        const val EXPEDIENTE_FIM_HORA = 22
        const val EXPEDIENTE_FIM_MIN = 0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedClient: FusedLocationProviderClient
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        startLocationUpdates()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Localização da frota", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Freedom — localização de frota ativa")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun dentroDoExpediente(): Boolean {
        val agora = Calendar.getInstance()

        // Só grava de segunda a quinta — sexta, sábado e domingo o serviço
        // continua rodando, mas não manda nenhum ponto.
        val diaSemana = agora.get(Calendar.DAY_OF_WEEK)
        val diaValido = diaSemana in Calendar.MONDAY..Calendar.THURSDAY
        if (!diaValido) return false

        val minutosAgora = agora.get(Calendar.HOUR_OF_DAY) * 60 + agora.get(Calendar.MINUTE)
        val inicio = EXPEDIENTE_INICIO_HORA * 60 + EXPEDIENTE_INICIO_MIN
        val fim = EXPEDIENTE_FIM_HORA * 60 + EXPEDIENTE_FIM_MIN
        return minutosAgora in inicio..fim
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5 * 60 * 1000L)
            .setMinUpdateIntervalMillis(4 * 60 * 1000L)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                if (dentroDoExpediente()) {
                    scope.launch { enviarLocalizacao(location) }
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Permissão de localização ainda não concedida — nada a fazer
            // aqui; MainActivity.ensureLocationPermissionAndSchedule() trata.
        }
    }

    private suspend fun enviarLocalizacao(location: Location) {
        if (!DeviceConfig.isPaired(applicationContext)) return

        try {
            val client = createSupabaseClient(
                DeviceConfig.supabaseUrl(applicationContext),
                DeviceConfig.supabaseKey(applicationContext)
            ) { install(Postgrest) }

            val velocidadeKmh = if (location.hasSpeed()) location.speed * 3.6 else null
            val deviceId = DeviceConfig.deviceId(applicationContext)
            val agora = Instant.now().toString()

            // Histórico (para trajeto/velocidade)
            client.from("localizacoes").insert(
                buildJsonObject {
                    put("device_id", deviceId)
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    velocidadeKmh?.let { put("velocidade_kmh", it) }
                    put("capturado_em", agora)
                }
            )

            // Último ponto conhecido (para o pin no mapa da frota)
            client.from("devices").update(
                buildJsonObject {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("localizacao_atualizada_em", agora)
                }
            ) { filter { eq("id", deviceId) } }
        } catch (e: Exception) {
            // próxima captura tenta de novo
        }
    }

    override fun onDestroy() {
        callback?.let { fusedClient.removeLocationUpdates(it) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
