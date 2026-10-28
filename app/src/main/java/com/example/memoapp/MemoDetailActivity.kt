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
import android.os.PowerManager
import android.text.InputType
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.memoapp.data.MemoDatabase
import com.example.memoapp.data.MemoEntity
import com.example.memoapp.databinding.ActivityMemoDetailBinding
import com.example.memoapp.util.TextStats
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

    /** 循环次数，0 表示无限循环。 */
    private var loopCount: Int = 0

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

    /** 电池优化豁免设置页返回后回调，用于提示当前状态。 */
    private val batterySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (!isBatteryOptimizationIgnored()) {
            showStatus(getString(R.string.battery_opt_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = MemoDatabase.getDatabase(this)
        memoId = intent.getLongExtra(EXTRA_MEMO_ID, NEW_MEMO)

        binding.toolbar.setNavigationOnClickListener { finish() }
        setupSliders()
        setupCharCount()
        setupLoopCount()
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
        loopCount = 0
        updateLoopButton()
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

    /** 应用是否已豁免电池优化。 */
    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * 首次朗读时引导用户豁免电池优化，否则锁屏一段时间后
     * 系统可能终止服务导致朗读中断。仅在未豁免时提示一次。
     */
    private fun ensureBatteryOptimizationExempt() {
        if (isBatteryOptimizationIgnored()) return
        if (getPreferences(MODE_PRIVATE).getBoolean(KEY_BATTERY_PROMPTED, false)) return

        getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()

        // 部分 ROM 对直接跳转有限制，失败时静默跳过，用户可自行在系统设置开启
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            }
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

    /** 编辑过程中实时更新字数，空内容也显示「共 0 字」。 */
    private fun setupCharCount() {
        updateCharCount()
        binding.etContent.doAfterTextChanged { updateCharCount() }
    }

    private fun updateCharCount() {
        binding.tvCharCount.text = getString(
            R.string.char_count_detail,
            TextStats.countChars(binding.etContent.text)
        )
    }

    private fun setupLoopCount() {
        updateLoopButton()
        binding.btnLoopCount.setOnClickListener { showLoopCountDialog() }
    }

    private fun updateLoopButton() {
        binding.btnLoopCount.text = if (loopCount == 0) {
            getString(R.string.loop_infinite)
        } else {
            getString(R.string.loop_times, loopCount)
        }
    }

    /** 循环次数：无限 / 10 / 20 / 50 / 80 / 100 遍，或自定义。 */
    private fun showLoopCountDialog() {
        val presets = intArrayOf(0, 10, 20, 50, 80, 100)
        val options = presets.toMutableList()

        // 当前是自定义值时也列出来并选中，否则打开对话框看不到当前设置
        if (loopCount > 0 && loopCount !in presets) options.add(loopCount)
        options.add(CUSTOM_ENTRY)

        val labels = options.map {
            when (it) {
                0 -> getString(R.string.loop_infinite_full)
                CUSTOM_ENTRY -> getString(R.string.loop_custom_option)
                else -> getString(R.string.loop_times, it)
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.loop_dialog_title)
            .setSingleChoiceItems(labels, options.indexOf(loopCount)) { dialog, which ->
                dialog.dismiss()
                val value = options[which]
                if (value == CUSTOM_ENTRY) {
                    showCustomLoopCountDialog()
                } else {
                    loopCount = value
                    updateLoopButton()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 自定义次数：1 ~ [MAX_LOOP_COUNT]，非数字/越界时留在对话框继续改。 */
    private fun showCustomLoopCountDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.loop_custom_hint, MAX_LOOP_COUNT)
            if (loopCount > 0) {
                setText(loopCount.toString())
                setSelection(text.length)
            }
            val horizontal = (20 * resources.displayMetrics.density).toInt()
            setPadding(horizontal, horizontal / 2, horizontal, 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.loop_custom_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // 用 OnShowListener 接管确定按钮，校验不通过时不关闭对话框
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().toIntOrNull()
                if (value == null || value < 1 || value > MAX_LOOP_COUNT) {
                    input.error = getString(R.string.loop_custom_error, MAX_LOOP_COUNT)
                    return@setOnClickListener
                }
                loopCount = value
                updateLoopButton()
                dialog.dismiss()
            }
        }
        dialog.show()
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
                    // 引导豁免电池优化，提升锁屏持续朗读的存活率
                    ensureBatteryOptimizationExempt()
                    // 先拉起前台服务，保证离开本页后仍能继续朗读
                    TtsService.start(this@MemoDetailActivity)
                    service.speak(
                        text,
                        binding.sliderSpeed.value,
                        binding.sliderPitch.value,
                        savedId,
                        loopCount
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
            showStatus(getString(R.string.status_playing), keep = true)
        } else {
            showStatus(getString(R.string.status_idle))
        }
    }

    /**
     * @param keep true 表示状态在播放期间常驻，不安排自动复位
     *        （例如"正在朗读"和循环进度），否则 2.5 秒后恢复"就绪"。
     */
    private fun showStatus(message: String, keep: Boolean = false) {
        binding.tvStatus.text = message
        // 必须先取消待执行的复位任务，否则播放开始后，
        // 上一条提示信息排的 2.5 秒复位仍会触发，把"正在循环朗读"改回"就绪"
        binding.tvStatus.removeCallbacks(statusResetter)
        if (!keep) {
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
                loopCount = memo.loopCount
                updateLoopButton()
                binding.toolbar.setTitle(R.string.title_edit_memo)

                if (switchPlayback) {
                    val service = ttsService
                    if (service != null && service.isPlaying()) {
                        service.speak(
                            memo.content,
                            memo.speechRate,
                            memo.pitch,
                            memo.id,
                            memo.loopCount
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
        val loop = loopCount
        val current = existingMemo

        if (current == null && content.isEmpty()) {
            onSaved?.invoke(NEW_MEMO)
            return
        }
        if (current != null &&
            current.content == content &&
            current.speechRate == rate &&
            current.pitch == pitch &&
            current.loopCount == loop
        ) {
            onSaved?.invoke(current.id)
            return
        }

        lifecycleScope.launch {
            if (current == null) {
                val newId = database.memoDao().insert(
                    MemoEntity(
                        content = content,
                        speechRate = rate,
                        pitch = pitch,
                        loopCount = loop
                    )
                )
                // 回填 id，使后续保存走更新分支、朗读能关联到这条备忘录
                memoId = newId
                existingMemo = MemoEntity(
                    id = newId,
                    content = content,
                    speechRate = rate,
                    pitch = pitch,
                    loopCount = loop
                )
                onSaved?.invoke(newId)
            } else {
                database.memoDao().update(
                    current.copy(
                        content = content,
                        speechRate = rate,
                        pitch = pitch,
                        loopCount = loop,
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

    override fun onLoopProgress(played: Int, total: Int) {
        showStatus(getString(R.string.status_loop_progress, played, total), keep = true)
    }

    override fun onPlaybackFinished(total: Int) {
        updatePlayButton(false)
        showStatus(getString(R.string.loop_finished, total))
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
        private const val KEY_BATTERY_PROMPTED = "battery_opt_prompted"

        /** 自定义循环次数在选项列表中的占位值。 */
        private const val CUSTOM_ENTRY = -1

        /** 自定义循环次数上限，避免误输入导致无限期朗读。 */
        private const val MAX_LOOP_COUNT = 9999
    }
}
