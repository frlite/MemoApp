package com.example.memoapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * 文字转语音前台服务。
 *
 * 通过 startForegroundService 启动，播放不依赖 Activity 生命周期：
 * 旋转屏幕、切后台、返回列表都不会中断朗读。
 * 播完自动从头循环，直到用户主动停止。
 */
class TtsService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isPlaying = false
    private var isForeground = false
    private var isForegroundStartedByService = false

    private var currentText: String = ""
    private var currentRate: Float = 1.0f
    private var currentPitch: Float = 1.0f

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    interface TtsCallback {
        fun onReadyChanged(isReady: Boolean)
        fun onPlayingChanged(isPlaying: Boolean)
        fun onSpeakFailed(reason: String)
    }

    var callback: TtsCallback? = null

    companion object {
        private const val CHANNEL_ID = "memo_tts_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UTTERANCE_ID = "memo_tts_utterance"
        private const val ACTION_STOP = "com.example.memoapp.action.TTS_STOP"

        fun start(context: Context) {
            val intent = Intent(context, TtsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏停止按钮
        if (intent?.action == ACTION_STOP) {
            stopSpeaking()
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService 要求 5 秒内进入前台状态
        if (!isForeground) enterForeground()
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                // 朗读完毕立即复读，实现无限循环
                override fun onDone(utteranceId: String?) {
                    if (isPlaying && utteranceId == UTTERANCE_ID) {
                        speakCurrent()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    stopSpeaking()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
            })

            if (tts?.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) != TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
            }

            callback?.onReadyChanged(true)
        } else {
            isTtsReady = false
            callback?.onReadyChanged(false)
        }
    }

    /**
     * 开始朗读。引擎不可用或内容为空时通过回调返回原因，不静默失败。
     */
    fun speak(text: String, rate: Float, pitch: Float) {
        if (!isTtsReady) {
            callback?.onSpeakFailed(getString(R.string.tts_error_not_ready))
            return
        }
        val content = text.trim()
        if (content.isEmpty()) {
            callback?.onSpeakFailed(getString(R.string.tts_error_empty))
            return
        }

        currentText = content
        currentRate = rate
        currentPitch = pitch

        enterForeground()
        isPlaying = true

        if (speakCurrent()) {
            callback?.onPlayingChanged(true)
        } else {
            isPlaying = false
            exitForeground()
            callback?.onSpeakFailed(getString(R.string.tts_error_speak))
        }
    }

    fun stopSpeaking() {
        val wasPlaying = isPlaying
        isPlaying = false
        tts?.stop()
        exitForeground()
        if (isForegroundStartedByService) stopSelf()
        if (wasPlaying) callback?.onPlayingChanged(false)
    }

    fun isPlaying(): Boolean = isPlaying

    fun isReady(): Boolean = isTtsReady

    /** 当前朗读参数，供 Activity 重新绑定时恢复 UI 状态。 */
    fun currentParams(): Pair<Float, Float> = currentRate to currentPitch

    /** 发起一次朗读，返回引擎是否接受。 */
    private fun speakCurrent(): Boolean {
        val engine = tts ?: return false
        if (!isTtsReady || currentText.isBlank()) return false

        engine.setSpeechRate(currentRate)
        engine.setPitch(currentPitch)
        return engine.speak(currentText, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) ==
            TextToSpeech.SUCCESS
    }

    private fun enterForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
        isForegroundStartedByService = true
    }

    private fun exitForeground() {
        if (!isForeground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tts_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MemoDetailActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tts_notification_title))
            .setContentText(getString(R.string.tts_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.btn_stop),
                stopIntent
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isPlaying = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        super.onDestroy()
    }
}
