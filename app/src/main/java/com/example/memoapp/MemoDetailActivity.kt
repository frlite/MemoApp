package com.example.memoapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.memoapp.data.MemoDatabase
import com.example.memoapp.data.MemoEntity
import com.example.memoapp.databinding.ActivityMemoDetailBinding
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 备忘录详情页：编辑内容 + 朗读。
 *
 * 朗读由 TtsService 承担，退出本页后仍可在后台继续循环播放，
 * 需回到本页点停止，或从通知栏停止。
 */
class MemoDetailActivity : AppCompatActivity(), TtsService.TtsCallback {

    private lateinit var binding: ActivityMemoDetailBinding
    private lateinit var database: MemoDatabase

    private var memoId: Long = NEW_MEMO
    private var existingMemo: MemoEntity? = null
    private var isLoaded = false

    private var ttsService: TtsService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            ttsService = (service as TtsService.LocalBinder).getService()
            ttsService?.callback = this@MemoDetailActivity
            isBound = true

            // 旋转屏幕后重新绑定，恢复播放状态
            updatePlayButton(ttsService?.isPlaying() == true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService = null
            isBound = false
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = MemoDatabase.getDatabase(this)
        memoId = intent.getLongExtra(EXTRA_MEMO_ID, NEW_MEMO)

        binding.toolbar.setNavigationOnClickListener { finish() }
        setupSliders()
        setupPlayButton()
        askNotificationPermission()

        bindTtsService()

        if (memoId != NEW_MEMO) loadMemo() else isLoaded = true
    }

    override fun onStart() {
        super.onStart()
        // 回到前台时同步真实播放状态
        if (isBound) updatePlayButton(ttsService?.isPlaying() == true)
    }

    private fun bindTtsService() {
        bindService(
            Intent(this, TtsService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupSliders() {
        updateSpeedLabel(binding.sliderSpeed.value)
        updatePitchLabel(binding.sliderPitch.value)

        binding.sliderSpeed.addOnChangeListener { _, value, _ -> updateSpeedLabel(value) }
        binding.sliderPitch.addOnChangeListener { _, value, _ -> updatePitchLabel(value) }
    }

    private fun updateSpeedLabel(value: Float) {
        binding.tvSpeedValue.text = String.format(Locale.getDefault(), "%.1fx", value)
    }

    private fun updatePitchLabel(value: Float) {
        binding.tvPitchValue.text = String.format(Locale.getDefault(), "%.1fx", value)
    }

    private fun setupPlayButton() {
        binding.btnPlayPause.setOnClickListener {
            val service = ttsService
            if (service == null) {
                showStatus(getString(R.string.tts_error_not_ready))
                return@setOnClickListener
            }

            if (service.isPlaying()) {
                service.stopSpeaking()
            } else {
                val text = binding.etContent.text.toString().trim()
                if (text.isEmpty()) {
                    binding.etContent.error = getString(R.string.hint_content)
                    return@setOnClickListener
                }
                // 先拉起前台服务，保证离开本页后仍能继续朗读
                TtsService.start(this)
                service.speak(text, binding.sliderSpeed.value, binding.sliderPitch.value)
            }
        }
    }

    private fun updatePlayButton(isPlaying: Boolean) {
        binding.btnPlayPause.setText(
            if (isPlaying) R.string.btn_stop else R.string.btn_play
        )
        if (isPlaying) {
            showStatus(getString(R.string.status_playing))
        } else {
            showStatus(getString(R.string.status_idle))
        }
    }

    private fun showStatus(message: String) {
        binding.tvStatus.text = message
        if (message != getString(R.string.status_playing)) {
            binding.tvStatus.removeCallbacks(statusResetter)
            binding.tvStatus.postDelayed(statusResetter, STATUS_RESET_MS)
        }
    }

    private val statusResetter = Runnable { binding.tvStatus.setText(R.string.status_idle) }

    private fun loadMemo() {
        lifecycleScope.launch {
            val memo = database.memoDao().getMemoById(memoId)
            if (memo == null) {
                memoId = NEW_MEMO
            } else {
                existingMemo = memo
                binding.etContent.setText(memo.content)
                binding.sliderSpeed.value = memo.speechRate
                binding.sliderPitch.value = memo.pitch
                binding.toolbar.setTitle(R.string.title_edit_memo)
            }
            isLoaded = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (isLoaded) save()
    }

    private fun save() {
        val content = binding.etContent.text.toString().trim()
        val rate = binding.sliderSpeed.value
        val pitch = binding.sliderPitch.value
        val current = existingMemo

        if (current == null && content.isEmpty()) return
        if (current != null &&
            current.content == content &&
            current.speechRate == rate &&
            current.pitch == pitch
        ) return

        lifecycleScope.launch {
            if (current == null) {
                database.memoDao().insert(
                    MemoEntity(content = content, speechRate = rate, pitch = pitch)
                )
            } else {
                database.memoDao().update(
                    current.copy(
                        content = content,
                        speechRate = rate,
                        pitch = pitch,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    // TtsCallback 实现。TTS 回调运行在主线程，无需切线程。
    override fun onReadyChanged(isReady: Boolean) {
        if (!isReady) showStatus(getString(R.string.tts_error_not_ready))
    }

    override fun onPlayingChanged(isPlaying: Boolean) {
        updatePlayButton(isPlaying)
    }

    override fun onSpeakFailed(reason: String) {
        updatePlayButton(false)
        showStatus(reason)
    }

    override fun onDestroy() {
        binding.tvStatus.removeCallbacks(statusResetter)
        if (isBound) {
            ttsService?.callback = null
            runCatching { unbindService(serviceConnection) }
            isBound = false
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MEMO_ID = "extra_memo_id"
        private const val NEW_MEMO = -1L
        private const val STATUS_RESET_MS = 2500L
    }
}
