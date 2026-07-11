package com.carmanager.ui.mileage

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.carmanager.data.MileageEntry
import com.carmanager.databinding.ItemFuelInMileageBinding
import com.carmanager.databinding.ItemMileageBinding
import java.text.SimpleDateFormat
import java.util.*

class MileageAdapter(
    private val onMileageLongClick: (MileageEntry) -> Unit
) : ListAdapter<DriveLogItem, RecyclerView.ViewHolder>(DiffCallback()) {

    private val sdf = SimpleDateFormat("yyyy년 MM월 dd일 HH:mm", Locale.KOREAN)

    companion object {
        private const val TYPE_MILEAGE = 0
        private const val TYPE_FUEL = 1
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is DriveLogItem.MileageItem -> TYPE_MILEAGE
        is DriveLogItem.FuelItem    -> TYPE_FUEL
    }

    inner class MileageViewHolder(private val binding: ItemMileageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DriveLogItem.MileageItem) {
            val entry = item.entry
            binding.textDate.text = sdf.format(Date(entry.date))
            binding.textOdometer.text = "%,d km".format(entry.odometer)
            if (item.delta > 0) {
                binding.textDistance.text = "+%,d km".format(item.delta)
                binding.textDistance.visibility = View.VISIBLE
            } else {
                binding.textDistance.visibility = View.GONE
            }
            // 출발 → 목적 표시
            val routeText = buildRouteText(entry.origin, entry.destination)
            if (routeText.isNotEmpty()) {
                binding.textDestination.text = routeText
                binding.textDestination.visibility = View.VISIBLE
            } else {
                binding.textDestination.visibility = View.GONE
            }
            binding.root.setOnLongClickListener {
                onMileageLongClick(entry)
                true
            }
        }
    }

    inner class FuelViewHolder(private val binding: ItemFuelInMileageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DriveLogItem.FuelItem) {
            val entry = item.entry
            binding.textDate.text = sdf.format(Date(entry.date))
            binding.textAmount.text = "%,d 원".format(entry.amount)
            if (entry.liters > 0f) {
                binding.textLiters.text = "%.2f L".format(entry.liters)
                binding.textLiters.visibility = View.VISIBLE
            } else {
                binding.textLiters.visibility = View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_MILEAGE -> MileageViewHolder(
                ItemMileageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            else -> FuelViewHolder(
                ItemFuelInMileageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DriveLogItem.MileageItem -> (holder as MileageViewHolder).bind(item)
            is DriveLogItem.FuelItem    -> (holder as FuelViewHolder).bind(item)
        }
    }

    private fun buildRouteText(origin: String, destination: String): String {
        val o = origin.trim()
        val d = destination.trim()
        return when {
            o.isNotEmpty() && d.isNotEmpty() -> "$o → $d"
            o.isNotEmpty()                   -> "출발: $o"
            d.isNotEmpty()                   -> "목적: $d"
            else                             -> ""
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DriveLogItem>() {
        override fun areItemsTheSame(a: DriveLogItem, b: DriveLogItem): Boolean = when {
            a is DriveLogItem.MileageItem && b is DriveLogItem.MileageItem -> a.entry.id == b.entry.id
            a is DriveLogItem.FuelItem    && b is DriveLogItem.FuelItem    -> a.entry.id == b.entry.id
            else -> false
        }
        override fun areContentsTheSame(a: DriveLogItem, b: DriveLogItem) = a == b
    }
}