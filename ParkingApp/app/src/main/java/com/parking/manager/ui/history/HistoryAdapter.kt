package com.parking.manager.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.parking.manager.data.ParkingRecord
import com.parking.manager.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

class HistoryAdapter(
    private val onLongPress: (ParkingRecord) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var records = listOf<ParkingRecord>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submitList(list: List<ParkingRecord>) {
        records = list
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: ParkingRecord) {
            binding.tvItemParkLocation.text = "📍 주차 위치: ${record.parkLocation}"
            binding.tvItemParkTime.text = "🕐 주차 시간: ${timeFmt.format(Date(record.parkTime))}"

            binding.tvItemScheduledTime.text = record.scheduledMoveTime?.let {
                val durMin = (it - record.parkTime) / 60000
                "⏰ 이동 예정: ${timeFmt.format(Date(it))} (주차 후 ${formatDuration(durMin)})"
            } ?: "⏰ 이동 예정: 미설정"

            binding.tvItemMoveStart.text = record.moveStartTime
                ?.let { "🚗 이동 시작: ${timeFmt.format(Date(it))}" } ?: "🚗 이동 시작: -"
            binding.tvItemMoveEnd.text = record.moveEndTime
                ?.let { "✅ 이동 완료: ${timeFmt.format(Date(it))}" } ?: "✅ 이동 완료: -"
            binding.tvItemNewLocation.text = record.newLocation
                ?.let { "📍 이동 후 위치: $it" } ?: "📍 이동 후 위치: -"

            if (record.moveStartTime != null && record.moveEndTime != null) {
                val durationMin = (record.moveEndTime - record.moveStartTime) / 60000
                binding.tvItemDuration.text = "⏱ 이동 소요: ${durationMin}분"
                binding.tvItemDuration.visibility = android.view.View.VISIBLE
            } else {
                binding.tvItemDuration.visibility = android.view.View.GONE
            }

            itemView.setOnLongClickListener {
                onLongPress(record)
                true
            }
        }

        private fun formatDuration(minutes: Long): String = when {
            minutes < 60 -> "${minutes}분"
            minutes % 60 == 0L -> "${minutes / 60}시간"
            else -> "${minutes / 60}시간 ${minutes % 60}분"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(records[position])
    override fun getItemCount() = records.size
}
