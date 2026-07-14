package com.welfare.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.welfare.app.databinding.ActivityPaymentBinding
import java.util.Locale

class PaymentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaymentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val restaurantName = intent.getStringExtra(IntentKeys.EXTRA_RESTAURANT_NAME)
            ?: getString(R.string.restaurant_name)
        val amount = intent.getLongExtra(IntentKeys.EXTRA_AMOUNT, 0L)
        val formattedAmount = formatAmount(amount)

        binding.restaurantNameText.text = restaurantName
        binding.manualAmountValue.text = formattedAmount
        binding.orderAmountValue.text = formattedAmount
        binding.pointAvailableText.text = getString(R.string.point_available_format, "0")
        binding.remainAmountText.text = getString(R.string.remain_amount_format, formattedAmount)
        binding.shortageText.text = getString(R.string.shortage_format, formattedAmount)

        binding.btnBack.setOnClickListener { finish() }

        val comingSoonTargets = listOf(
            binding.couponChip to "쿠폰 선택",
            binding.btnUseAll to "포인트 사용",
            binding.cardRow to "결제 카드 변경"
        )
        comingSoonTargets.forEach { (view, label) ->
            view.setOnClickListener {
                Toast.makeText(this, "$label 기능은 준비 중입니다", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPay.setOnClickListener {
            Toast.makeText(this, "${formattedAmount}원 결제가 완료되었습니다", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    private fun formatAmount(value: Long): String = String.format(Locale.KOREA, "%,d", value)
}
