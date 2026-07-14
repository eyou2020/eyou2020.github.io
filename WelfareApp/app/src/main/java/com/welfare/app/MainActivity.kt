package com.welfare.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.welfare.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPointCards()
        setupCategoryGrid()
        setupBanner()
        setupProductList()
        setupBottomNav()
    }

    private fun setupPointCards() {
        var mealExpanded = true
        binding.cardMealPoint.setOnClickListener {
            mealExpanded = !mealExpanded
            binding.mealPointToggle.setImageResource(
                if (mealExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
            )
        }

        var payExpanded = false
        binding.cardDaejangPay.setOnClickListener {
            payExpanded = !payExpanded
            binding.daejangPayToggle.setImageResource(
                if (payExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
            )
        }

        binding.btnEat.setOnClickListener {
            startActivity(Intent(this, RestaurantDetailActivity::class.java))
        }
    }

    private fun setupCategoryGrid() {
        val targets = listOf(
            binding.cardFoodHall to getString(R.string.food_hall_title),
            binding.catEzwel to getString(R.string.cat_ezwel_mall),
            binding.catEcoupon to getString(R.string.cat_ecoupon),
            binding.catLg to getString(R.string.cat_lg),
            binding.catHyundai to getString(R.string.cat_hyundai),
            binding.catGolf to getString(R.string.cat_golf),
            binding.catGroupBuy to getString(R.string.cat_group_buy)
        )
        targets.forEach { (view, label) ->
            view.setOnClickListener { showComingSoon(label) }
        }
    }

    private fun setupBanner() {
        val banners = listOf(
            Banner(
                getString(R.string.banner_title),
                getString(R.string.banner_subtitle)
            ),
            Banner(
                getString(R.string.banner_title),
                getString(R.string.banner_subtitle)
            )
        )
        binding.bannerPager.adapter = BannerAdapter(banners)
        binding.bannerPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.pageIndicator.text = "${position + 1}/${banners.size}"
            }
        })
    }

    private fun setupProductList() {
        val products = listOf(
            Product("KOLON SPORT", "버킷햇 캠핑 모자"),
            Product("KOLON SPORT", "트레킹 백팩"),
            Product("KOLON SPORT", "크로스 슬링백"),
            Product("KOLON SPORT", "경량 아우터")
        )
        binding.productList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.productList.adapter = ProductAdapter(products)
    }

    private fun setupBottomNav() {
        val navItems = listOf(
            binding.navHome to getString(R.string.nav_home),
            binding.navBusiness to getString(R.string.nav_business),
            binding.navMealCard to getString(R.string.nav_meal_card),
            binding.navWelfareMall to getString(R.string.nav_welfare_mall),
            binding.navMore to getString(R.string.nav_more)
        )
        navItems.forEach { (view, label) ->
            view.setOnClickListener {
                if (view.id != binding.navHome.id) {
                    showComingSoon(label)
                }
                selectNavItem(view.id)
            }
        }
        selectNavItem(binding.navHome.id)
    }

    private fun selectNavItem(selectedId: Int) {
        val icon = binding.navHomeIcon
        val label = binding.navHomeLabel
        val activeColor = ContextCompat.getColor(this, R.color.nav_active)
        val inactiveColor = ContextCompat.getColor(this, R.color.nav_inactive)
        val isHomeSelected = selectedId == binding.navHome.id
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(if (isHomeSelected) activeColor else inactiveColor))
        label.setTextColor(if (isHomeSelected) activeColor else inactiveColor)
    }

    private fun showComingSoon(label: String) {
        Toast.makeText(this, "$label 화면은 준비 중입니다", Toast.LENGTH_SHORT).show()
    }
}
