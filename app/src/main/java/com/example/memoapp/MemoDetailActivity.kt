package com.example.memoapp

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.memoapp.data.MemoDatabase
import com.example.memoapp.data.MemoEntity
import com.example.memoapp.databinding.ActivityMemoDetailBinding
import kotlinx.coroutines.launch

/**
 * 备忘录编辑页。
 * - 不带 id 进入：新建，离开时若内容非空则保存
 * - 带 id 进入：加载已有内容，保存时执行更新
 */
class MemoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMemoDetailBinding
    private lateinit var database: MemoDatabase

    private var memoId: Long = NEW_MEMO
    private var existingMemo: MemoEntity? = null
    private var isLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = MemoDatabase.getDatabase(this)
        memoId = intent.getLongExtra(EXTRA_MEMO_ID, NEW_MEMO)

        binding.toolbar.setNavigationOnClickListener { finish() }

        if (memoId != NEW_MEMO) {
            loadMemo()
        } else {
            isLoaded = true
        }
    }

    private fun loadMemo() {
        lifecycleScope.launch {
            val memo = database.memoDao().getMemoById(memoId)
            if (memo == null) {
                // 记录已被删除，按新建处理
                memoId = NEW_MEMO
            } else {
                existingMemo = memo
                binding.etContent.setText(memo.content)
                binding.toolbar.title = getString(R.string.title_edit_memo)
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
        val current = existingMemo

        // 新建且内容为空：视为放弃，不落库
        if (current == null && content.isEmpty()) return

        // 内容无变化：避免无谓写入和列表刷新
        if (current != null && current.content == content) return

        lifecycleScope.launch {
            if (current == null) {
                database.memoDao().insert(MemoEntity(content = content))
            } else {
                database.memoDao().update(
                    current.copy(content = content, updatedAt = System.currentTimeMillis())
                )
            }
        }
    }

    companion object {
        const val EXTRA_MEMO_ID = "extra_memo_id"
        private const val NEW_MEMO = -1L
    }
}
