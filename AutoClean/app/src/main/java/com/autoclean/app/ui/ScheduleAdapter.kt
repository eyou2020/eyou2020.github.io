package com.autoclean.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.autoclean.app.R
import com.autoclean.app.databinding.ItemScheduleBinding
import com.autoclean.app.model.CleanSchedule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleAdapter(
    private val onToggle: (CleanSchedule, Boolean) -> Unit,
    private val onEdit: (CleanSchedule) -> Unit,
    private val onDelete: (CleanSchedule) -> Unit,
    private val onRunNow: (CleanSchedule) -> Unit
) : ListAdapter<CleanSchedule, ScheduleAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScheduleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemScheduleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(schedule: CleanSchedule) {
            val ctx = binding.root.context

            binding.tvFolderName.text = schedule.folderName
            binding.tvTime.text = String.format("%02d:%02d", schedule.scheduledHour, schedule.scheduledMinute)
            binding.tvRepeat.text = formatRepeatDays(schedule.repeatDays, ctx)
            binding.tvDeleteMode.text = if (schedule.deleteMode == CleanSchedule.DELETE_MODE_PERMANENT)
                ctx.getString(R.string.delete_mode_permanent) else ctx.getString(R.string.delete_mode_trash)
            binding.tvDeleteCount.text = ctx.getString(R.string.deleted_count, schedule.totalDeletedCount)

            if (schedule.lastRunAt > 0) {
                val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())
                binding.tvLastRun.text = ctx.getString(R.string.last_run, sdf.format(Date(schedule.lastRunAt)))
            } else {
                binding.tvLastRun.text = ctx.getString(R.string.no_run_yet)
            }

            binding.switchEnable.setOnCheckedChangeListener(null)
            binding.switchEnable.isChecked = schedule.isEnabled
            binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
                onToggle(schedule, isChecked)
            }

            binding.btnEdit.setOnClickListener { onEdit(schedule) }
            binding.btnDelete.setOnClickListener { onDelete(schedule) }
            binding.btnRunNow.setOnClickListener { onRunNow(schedule) }

            binding.root.alpha = if (schedule.isEnabled) 1.0f else 0.5f
        }

        private fun formatRepeatDays(repeatDays: String, ctx: android.content.Context): String {
            return when (repeatDays) {
                CleanSchedule.REPEAT_DAILY -> ctx.getString(R.string.repeat_daily)
                CleanSchedule.REPEAT_WEEKDAY -> ctx.getString(R.string.repeat_weekday)
                CleanSchedule.REPEAT_WEEKEND -> ctx.getString(R.string.repeat_weekend)
                CleanSchedule.REPEAT_ONCE -> ctx.getString(R.string.repeat_once)
                else -> repeatDays
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CleanSchedule>() {
        override fun areItemsTheSame(oldItem: CleanSchedule, newItem: CleanSchedule) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: CleanSchedule, newItem: CleanSchedule) =
            oldItem == newItem
    }
}
