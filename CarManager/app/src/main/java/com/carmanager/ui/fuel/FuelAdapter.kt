package com.carmanager.ui.fuel

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.carmanager.data.FuelEntry
import com.carmanager.databinding.ItemFuelBinding
import java.text.SimpleDateFormat
import java.util.*

class FuelAdapter(private val onLongClick: (FuelEntry) -> Unit) :
    ListAdapter<FuelEntry, FuelAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemFuelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: FuelEntry) {
            val sdf = SimpleDateFormat("yyyy년 MM월 dd일 HH:mm", Locale.KOREAN)
            binding.textDate.text = sdf.format(Date(entry.date))
            binding.textAmount.text = "%,d 원".format(entry.amount)
            if (entry.liters > 0f) {
                binding.textLiters.text = "%.2f L".format(entry.liters)
                binding.textLiters.visibility = android.view.View.VISIBLE
            } else {
                binding.textLiters.visibility = android.view.View.GONE
            }
            binding.root.setOnLongClickListener {
                onLongClick(entry)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFuelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class DiffCallback : DiffUtil.ItemCallback<FuelEntry>() {
        override fun areItemsTheSame(a: FuelEntry, b: FuelEntry) = a.id == b.id
        override fun areContentsTheSame(a: FuelEntry, b: FuelEntry) = a == b
    }
}
