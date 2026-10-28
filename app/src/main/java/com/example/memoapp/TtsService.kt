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
import android.os.PowerManager
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

    /** 设定循环次数，0 表示无限循环。 */
    private var loopCount: Int = 0

    /** 当前正在播第几遍，从 1 开始。 */
    private var currentLoop: Int = 1

    /** 正在朗读的备忘录 id，通知栏点击后据此回到对应详情页。 */
    private var currentMemoId: Long = NO_MEMO

    /**
     * 朗读期间持有部分唤醒锁，避免设备深度休眠导致
     * TTS 回调延迟、循环间隔被拉长。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    interface TtsCallback {
        fun onReadyChanged(isReady: Boolean)
        fun onPlayingChanged(isPlaying: Boolean)
        fun onSpeakFailed(reason: String)

        /** 有限循环时的进度，played 从 1 开始；无限循环不回调。 */
        fun onLoopProgress(played: Int, total: Int) = Unit

        /** 达到设定次数后自动停止。 */
        fun onPlaybackFinished(total: Int) = Unit
    }

    var callback: TtsCallback? = null

    companion object {
        private const val CHANNEL_ID = "memo_tts_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UTTERANCE_ID = "memo_tts_utterance"
        private const val ACTION_STOP = "com.example.memoapp.action.TTS_STOP"
        private const val WAKE_LOCK_TAG = "MemoApp:TtsPlayback"
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L

        /** 表示当前没有关联的备忘录（例如尚未保存的新建内容）。 */
        const val NO_MEMO = -1L

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

                // 一遍读完：未达设定次数则继续，达到则停止
                override fun onDone(utteranceId: String?) {
                    if (!isPlaying || utteranceId != UTTERANCE_ID) return

                    val hasMore = loopCount == 0 || currentLoop < loopCount
                    if (!hasMore) {
                        finishPlayback()
                        return
                    }

                    currentLoop++
                    if (speakCurrent()) {
                        // 循环次数有限时，通知栏同步显示第几遍
                        enterForeground()
                        if (loopCount > 0) callback?.onLoopProgress(currentLoop, loopCount)
                    } else {
                        finishPlayback()
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
     * memoId 用于让通知栏点击后回到正在朗读的备忘录。
     * loopCount 为循环次数，0 表示无限循环。
     */
    fun speak(text: String, rate: Float, pitch: Float, memoId: Long, loopCount: Int) {
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
        currentMemoId = memoId
        this.loopCount = if (loopCount <= 0) 0 else loopCount
        currentLoop = 1

        enterForeground()
        acquireWakeLock()
        isPlaying = true

        if (speakCurrent()) {
            callback?.onPlayingChanged(true)
            if (this.loopCount > 0) callback?.onLoopProgress(currentLoop, this.loopCount)
        } else {
            isPlaying = false
            releaseWakeLock()
            exitForeground()
            callback?.onSpeakFailed(getString(R.string.tts_error_speak))
        }
    }

    fun stopSpeaking() {
        val wasPlaying = isPlaying
        isPlaying = false
        tts?.stop()
        releaseWakeLock()
        exitForeground()
        if (isForegroundStartedByService) stopSelf()
        if (wasPlaying) callback?.onPlayingChanged(false)
    }

    /**
     * 达到设定循环次数后自然结束。
     * 与用户主动停止区分：会额外回调 onPlaybackFinished，供界面提示。
     */
    private fun finishPlayback() {
        if (!isPlaying) return
        val played = currentLoop
        isPlaying = false
        tts?.stop()
        releaseWakeLock()
        exitForeground()
        callback?.onPlayingChanged(false)
        callback?.onPlaybackFinished(played)
        if (isForegroundStartedByService) stopSelf()
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

    /**
     * 获取唤醒锁，保持 CPU 在锁屏休眠期间继续处理 TTS 回调。
     * 带超时获取，异常情况下不会永久持有。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
        // 点击通知回到正在朗读的备忘录。
        // requestCode 随 memoId 变化，否则 PendingIntent 会被复用导致 id 不更新。
        val contentIntent = PendingIntent.getActivity(
            this,
            currentMemoId.toInt() + 1,
            Intent(this, MemoDetailActivity::class.java).apply {
                if (currentMemoId != NO_MEMO) {
                    putExtra(MemoDetailActivity.EXTRA_MEMO_ID, currentMemoId)
                }
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (loopCount > 0) {
            getString(R.string.status_loop_progress, currentLoop, loopCount)
        } else {
            getString(R.string.tts_notification_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tts_notification_title))
            .setContentText(text)
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
        releaseWakeLock()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        super.onDestroy()
    }
}
