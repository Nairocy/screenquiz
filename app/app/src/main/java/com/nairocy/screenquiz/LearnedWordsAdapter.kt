package com.nairocy.screenquiz

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「已学习词条」抽屉列表：单词 + 来源徽标 + 加入日期 + 释义；长按回调删除 */
class LearnedWordsAdapter(
    private val onLongPress: (UserDict.Row, Int) -> Unit
) : RecyclerView.Adapter<LearnedWordsAdapter.Holder>() {

    private val rows = ArrayList<UserDict.Row>()

    fun submit(list: List<UserDict.Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    fun removeAt(position: Int): UserDict.Row? {
        if (position !in rows.indices) return null
        val row = rows.removeAt(position)
        notifyItemRemoved(position)
        return row
    }

    fun insertSorted(row: UserDict.Row): Int {
        val index = rows.indexOfFirst { it.updatedAt < row.updatedAt }
        val at = if (index < 0) rows.size else index
        rows.add(at, row)
        notifyItemInserted(at)
        return at
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_learned_word, parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val word: TextView = itemView.findViewById(R.id.word_row_word)
        private val source: TextView = itemView.findViewById(R.id.word_row_source)
        private val date: TextView = itemView.findViewById(R.id.word_row_date)
        private val translation: TextView = itemView.findViewById(R.id.word_row_translation)

        fun bind(row: UserDict.Row) {
            val ctx = itemView.context
            word.text = row.word
            translation.text = row.translation
            date.text = DATE_FMT.format(Date(row.updatedAt))
            val bgRes: Int
            val colorRes: Int
            when (row.source) {
                "manual" -> {
                    // 方正主义：手动=砖红底纸白字（唯一主动作色，只给小徽标）
                    bgRes = R.drawable.bg_chip_brick
                    colorRes = R.color.on_accent
                }
                "settle" -> {
                    // 结算=内嵌面 + 墨字，靠 1dp 墨边成形，不抢主动作色
                    bgRes = R.drawable.bg_chip_neutral
                    colorRes = R.color.text_primary
                }
                else -> {
                    bgRes = R.drawable.bg_chip_neutral
                    colorRes = R.color.text_secondary
                }
            }
            val label = when (row.source) {
                "manual" -> "手动"
                "settle" -> "结算"
                "wrongbook" -> "错词本"
                else -> row.source
            }
            source.text = label
            source.setBackgroundResource(bgRes)
            source.setTextColor(ContextCompat.getColor(ctx, colorRes))
            itemView.setOnLongClickListener {
                @Suppress("DEPRECATION")
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) onLongPress(row, position)
                true
            }
        }
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("MM-dd", Locale.getDefault())
    }
}
