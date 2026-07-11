package com.parking.manager.ui.location

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.parking.manager.data.ParkingLocation
import com.parking.manager.data.durationText
import com.parking.manager.data.moveTimeText
import com.parking.manager.databinding.ItemLocationBinding

class LocationAdapter(
    private val onEdit: (ParkingLocation) -> Unit,
    private val onDelete: (ParkingLocation) -> Unit
) : RecyclerView.Adapter<LocationAdapter.ViewHolder>() {

    private var locations = listOf<ParkingLocation>()

    fun submitList(list: List<ParkingLocation>) {
        locations = list
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemLocationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(location: ParkingLocation) {
            binding.tvLocationName.text = location.name
            val dur = location.durationText()
            val move = location.moveTimeText()
            val parts = mutableListOf<String>()
            if (dur != null) parts.add("주차 가능: $dur") else parts.add("주차 시간 미설정")
            if (move != null) parts.add("이동시간: $move")
            binding.tvLocationMemo.text = parts.joinToString("  |  ")
            binding.btnEdit.setOnClickListener { onEdit(location) }
            binding.btnDelete.setOnClickListener { onDelete(location) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLocationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(locations[position])

    override fun getItemCount() = locations.size
}
