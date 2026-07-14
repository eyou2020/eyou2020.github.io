package com.welfare.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.welfare.app.databinding.ActivityRestaurantDetailBinding

class RestaurantDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRestaurantDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRestaurantDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnManualAmount.setOnClickListener {
            val intent = Intent(this, AmountInputActivity::class.java).apply {
                putExtra(IntentKeys.EXTRA_RESTAURANT_NAME, getString(R.string.restaurant_name))
            }
            startActivity(intent)
        }

        val comingSoonTargets = listOf(
            binding.btnSearch to "검색",
            binding.btnCall to getString(R.string.btn_call),
            binding.btnMap to getString(R.string.btn_map),
            binding.btnFavorite to getString(R.string.btn_favorite)
        )
        comingSoonTargets.forEach { (view, label) ->
            view.setOnClickListener {
                Toast.makeText(this, "$label 기능은 준비 중입니다", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
