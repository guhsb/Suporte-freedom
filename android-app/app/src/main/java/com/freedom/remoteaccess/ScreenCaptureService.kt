package com.freedom.remoteaccess

import android.app.*
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.getstream.webrtc.android.*
import org.json.JSONObject
import org.webrtc.*

/**
 * Serviço em primeiro plano que:
 *  1. Inicia a captura de tela (MediaProjection) -> vira uma VideoTrack do WebRTC
 *  2. Captura o microfone do aparelho -> vira uma AudioTrack
 *  3. Monta o PeerConnection e troca SDP/ICE via SignalingClient
 *  4. Recebe comandos de controle pelo DataChannel e repassa para o
 *     RemoteControlAccessibilityService executar
 *
 * MOSTRA UM AVISO FIXO (notificação em primeiro plano) enquanto a sessão
 * estiver ativa — isso é obrigatório, mesmo em aparelho da empresa.
 *
 * ATENÇÃO (ponto em aberto da arquitetura):
 * Em um app comum, iniciar a MediaProjection sempre abre um diálogo de
 * consentimento do sistema (Activity.startActivityForResult). Não existe
 * API pública para o Device Owner pular esse diálogo automaticamente em
 * todas as versões/fabricantes — isso precisa ser validado caso a caso
 * (em alguns casos resolve-se tornando o app um "app de política" assinado
 * junto com o Headwind, ou usando MediaProjectionManager de dentro de um
 * componente que já roda com o token de consentimento pré-concedido pelo
 * provisionamento). Veja README.md -> "Limitações do consentimento".
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "remote_session"
        const val NOTIF_ID = 1001
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_SUPABASE_URL = "supabaseUrl"
        const val EXTRA_SUPABASE_KEY = "supabaseKey"
    }

    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var mediaProjection: MediaProjection? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var signalingClient: SignalingClient? = null
    private var micEnabled = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: "desconhecido"
        val supabaseUrl = intent.getStringExtra(EXTRA_SUPABASE_URL) ?: return START_NOT_STICKY
        val supabaseKey = intent.getStringExtra(EXTRA_SUPABASE_KEY) ?: return START_NOT_STICKY

        setupWebRtc()
        startScreenCapture(resultCode, resultData)
        connectSignaling(supabaseUrl, supabaseKey, deviceId)

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        // Importância baixa: fica só o ícone na barra de status, sem som nem
        // pop-up — mas continua visível. Isso não é só uma escolha de
        // produto: a partir do Android 14, o próprio sistema exige uma
        // notificação enquanto um foreground service do tipo mediaProjection
        // está ativo, então não dá (nem deveria) remover isso de vez.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Suporte remoto", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_sessao_ativa))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupWebRtc() {
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun startScreenCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(false)

        videoCapturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        })
        videoCapturer?.initialize(surfaceHelper, applicationContext, videoSource.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)

        val videoTrack = peerConnectionFactory.createVideoTrack("screen0", videoSource)

        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        val audioTrack = peerConnectionFactory.createAudioTrack("mic0", audioSource)
        audioTrack.setEnabled(micEnabled)

        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        )

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient?.sendIceCandidate(JSONObject().apply {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                })
            }

            override fun onDataChannel(dc: DataChannel) {
                // Recebe o data channel criado pela página de controle e passa
                // os comandos para o serviço de acessibilidade executar.
                dc.registerObserver(object : DataChannel.Observer {
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        val json = JSONObject(String(bytes))
                        RemoteControlAccessibilityService.instance?.handleCommand(json)
                    }
                    override fun onStateChange() {}
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                })
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
        })

        peerConnection?.addTrack(videoTrack)
        peerConnection?.addTrack(audioTrack)
    }

    private fun connectSignaling(supabaseUrl: String, supabaseKey: String, deviceId: String) {
        signalingClient = SignalingClient(supabaseUrl, supabaseKey, deviceId, object : SignalingClient.Listener {
            override fun onPeerReady() {
                // O controlador manda a oferta primeiro neste protótipo;
                // o device só espera e responde com a answer.
            }

            override fun onRemoteSdpOffer(sdp: JSONObject) {
                val offer = SessionDescription(
                    SessionDescription.Type.OFFER, sdp.getString("sdp")
                )
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        peerConnection?.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                peerConnection?.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onSetSuccess() {
                                        signalingClient?.sendSdpAnswer(JSONObject().apply {
                                            put("type", "answer")
                                            put("sdp", answer.description)
                                        })
                                    }
                                    override fun onCreateFailure(p0: String?) {}
                                    override fun onSetFailure(p0: String?) {}
                                }, answer)
                            }
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {}
                        }, MediaConstraints())
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, offer)
            }

            override fun onRemoteIceCandidate(candidate: JSONObject) {
                peerConnection?.addIceCandidate(
                    IceCandidate(
                        candidate.getString("sdpMid"),
                        candidate.getInt("sdpMLineIndex"),
                        candidate.getString("candidate"),
                    )
                )
            }

            override fun onControlMode(enabled: Boolean) {
                // Defesa em profundidade: o app TAMBÉM obedece esse sinal,
                // mesmo que o data channel receba um comando de toque.
                RemoteControlAccessibilityService.instance?.controlEnabled = enabled
            }

            override fun onMicMute(muted: Boolean) {
                // (reservado: aqui entraria o mute do ÁUDIO QUE O APARELHO ENVIA,
                // se vocês quiserem dar esse controle ao suporte também)
            }

            override fun onPeerLeft() {
                stopSelf()
            }
        })
        signalingClient?.connect()
    }

    override fun onDestroy() {
        videoCapturer?.stopCapture()
        mediaProjection?.stop()
        peerConnection?.close()
        signalingClient?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
