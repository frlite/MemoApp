package com.example.memoapp.adapter

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.memoapp.data.MemoEntity
import com.example.memoapp.databinding.ItemMemoBinding
import com.example.memoapp.util.TextStats

class MemoAdapter(
    private val onClick: (MemoEntity) -> Unit,
    private val onDelete: (MemoEntity) -> Unit
) : ListAdapter<MemoEntity, MemoAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemMemoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MemoEntity) {
            // 首行作为标题：正文的第一行，或显示占位符
            val firstLine = item.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            binding.tvTitle.text = firstLine.ifBlank {
                binding.root.context.getString(com.example.memoapp.R.string.untitled)
            }

            val rest = item.content.lines().drop(1).joinToString("\n").trim()
            binding.tvPreview.text = rest.ifBlank {
                binding.root.context.getString(com.example.memoapp.R.string.no_content)
            }

            binding.tvTime.text = DateUtils.getRelativeTimeSpanString(
                item.updatedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )

            binding.tvCount.text = binding.root.context.getString(
                com.example.memoapp.R.string.char_count,
                TextStats.countChars(item.content)
            )

            binding.root.setOnClickListener { onClick(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemMemoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<MemoEntity>() {
            override fun areItemsTheSame(old: MemoEntity, new: MemoEntity) = old.id == new.id
            override fun areContentsTheSame(old: MemoEntity, new: MemoEntity) = old == new
        }
    }
}
