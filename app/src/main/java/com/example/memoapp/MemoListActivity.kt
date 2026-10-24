package com.example.memoapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.memoapp.adapter.MemoAdapter
import com.example.memoapp.data.MemoDatabase
import com.example.memoapp.databinding.ActivityMemoListBinding
import com.example.memoapp.util.TextStats
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 备忘录列表页。展示全部备忘录，支持新建、打开、删除。
 */
class MemoListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMemoListBinding
    private lateinit var database: MemoDatabase
    private lateinit var memoAdapter: MemoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = MemoDatabase.getDatabase(this)

        setupToolbar()
        setupList()
        observeMemos()

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, MemoDetailActivity::class.java))
        }
    }

    private fun setupToolbar() {
        // 主题为 NoActionBar，菜单需挂在 Toolbar 上才会显示
        binding.toolbar.inflateMenu(R.menu.menu_list)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete_all) {
                confirmDeleteAll()
                true
            } else {
                false
            }
        }
    }

    private fun setupList() {
        memoAdapter = MemoAdapter(
            onClick = { memo ->
                startActivity(
                    Intent(this, MemoDetailActivity::class.java)
                        .putExtra(MemoDetailActivity.EXTRA_MEMO_ID, memo.id)
                )
            },
            onDelete = { memo -> confirmDelete(memo.id, memo.content) }
        )

        binding.rvMemos.apply {
            layoutManager = LinearLayoutManager(this@MemoListActivity)
            adapter = memoAdapter
            setHasFixedSize(true)
        }
    }

    private fun observeMemos() {
        lifecycleScope.launch {
            database.memoDao().getAllMemos().collectLatest { memos ->
                memoAdapter.submitList(memos)
                binding.tvEmpty.visibility = if (memos.isEmpty()) View.VISIBLE else View.GONE

                // 顶部统计：条目数 + 总字数
                binding.toolbar.subtitle = getString(
                    R.string.stats_summary,
                    memos.size,
                    memos.sumOf { TextStats.countChars(it.content) }
                )
            }
        }
    }

    private fun confirmDelete(id: Long, content: String) {
        val preview = content.lineSequence().firstOrNull { it.isNotBlank() }
            ?.take(20)?.ifBlank { null }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(preview?.let { getString(R.string.dialog_delete_msg, it) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.dialog_delete_confirm) { _, _ ->
                lifecycleScope.launch { database.memoDao().deleteById(id) }
            }
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_all_title)
            .setMessage(R.string.dialog_delete_all_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.dialog_delete_confirm) { _, _ ->
                lifecycleScope.launch { database.memoDao().clearAll() }
            }
            .show()
    }
}
