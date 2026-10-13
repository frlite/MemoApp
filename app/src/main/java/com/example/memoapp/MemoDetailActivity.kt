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

    /**
     * 通知栏点击进入时，若已有实例则走这里而不是 onCreate。
     * 需要加载目标备忘录，并把内容同步到界面。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val targetId = intent.getLongExtra(EXTRA_MEMO_ID, NEW_MEMO)
        if (targetId == NEW_MEMO || targetId == memoId) return

        memoId = targetId
        existingMemo = null
        binding.etContent.setText("")
        binding.toolbar.setTitle(R.string.title_new_memo)
        loadMemo(switchPlayback = true)
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
                // 先落库拿到真实 id，通知栏点击才能回到这条备忘录
                save { savedId ->
                    if (savedId == NEW_MEMO) return@save
                    binding.toolbar.setTitle(R.string.title_edit_memo)
                    // 先拉起前台服务，保证离开本页后仍能继续朗读
                    TtsService.start(this@MemoDetailActivity)
                    service.speak(
                        text,
                        binding.sliderSpeed.value,
                        binding.sliderPitch.value,
                        savedId
                    )
                }
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

    /**
     * @param switchPlayback true 表示用户主动切换到这条备忘录（如点通知），
     *        此时若正在朗读其他内容则切过来；false 表示仅恢复界面
     *        （如屏幕旋转重建），不能打断正在进行的播放。
     */
    private fun loadMemo(switchPlayback: Boolean = false) {
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

                if (switchPlayback) {
                    val service = ttsService
                    if (service != null && service.isPlaying()) {
                        service.speak(
                            memo.content,
                            memo.speechRate,
                            memo.pitch,
                            memo.id
                        )
                    }
                }
            }
            isLoaded = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (isLoaded) save()
    }

    private fun save(onSaved: ((Long) -> Unit)? = null) {
        val content = binding.etContent.text.toString().trim()
        val rate = binding.sliderSpeed.value
        val pitch = binding.sliderPitch.value
        val current = existingMemo

        if (current == null && content.isEmpty()) {
            onSaved?.invoke(NEW_MEMO)
            return
        }
        if (current != null &&
            current.content == content &&
            current.speechRate == rate &&
            current.pitch == pitch
        ) {
            onSaved?.invoke(current.id)
            return
        }

        lifecycleScope.launch {
            if (current == null) {
                val newId = database.memoDao().insert(
                    MemoEntity(content = content, speechRate = rate, pitch = pitch)
                )
                // 回填 id，使后续保存走更新分支、朗读能关联到这条备忘录
                memoId = newId
                existingMemo = MemoEntity(
                    id = newId,
                    content = content,
                    speechRate = rate,
                    pitch = pitch
                )
                onSaved?.invoke(newId)
            } else {
                database.memoDao().update(
                    current.copy(
                        content = content,
                        speechRate = rate,
                        pitch = pitch,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                onSaved?.invoke(current.id)
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
