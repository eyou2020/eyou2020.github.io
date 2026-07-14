package com.welfare.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.welfare.app.databinding.ItemBannerBinding

data class Banner(val title: String, val subtitle: String)

class BannerAdapter(private val items: List<Banner>) :
    RecyclerView.Adapter<BannerAdapter.BannerViewHolder>() {

    inner class BannerViewHolder(val binding: ItemBannerBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerViewHolder {
        val binding = ItemBannerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BannerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BannerViewHolder, position: Int) {
        val banner = items[position]
        holder.binding.bannerTitle.text = banner.title
        holder.binding.bannerSubtitle.text = banner.subtitle
    }

    override fun getItemCount(): Int = items.size
}
