package com.welfare.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.welfare.app.databinding.ActivityAmountInputBinding
import java.util.Locale

class AmountInputActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAmountInputBinding

    private var total: Long = 0
    private var currentInput: String = ""
    private var restaurantName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAmountInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restaurantName = intent.getStringExtra(IntentKeys.EXTRA_RESTAURANT_NAME)
            ?: getString(R.string.restaurant_name)

        setupClicks()
        updateDisplay()
    }

    private fun setupClicks() {
        binding.btnClose.setOnClickListener { finish() }

        val digitButtons = listOf(
            binding.num1 to "1", binding.num2 to "2", binding.num3 to "3",
            binding.num4 to "4", binding.num5 to "5", binding.num6 to "6",
            binding.num7 to "7", binding.num8 to "8", binding.num9 to "9",
            binding.num0 to "0", binding.num00 to "00", binding.num000 to "000"
        )
        digitButtons.forEach { (view, digits) ->
            view.setOnClickListener { appendDigits(digits) }
        }

        binding.btnClear.setOnClickListener {
            total = 0
            currentInput = ""
            updateDisplay()
        }

        binding.btnBackspace.setOnClickListener { backspace() }

        binding.btnPlus.setOnClickListener { commit(1) }
        binding.btnMinus.setOnClickListener { commit(-1) }

        binding.quickPlus100.setOnClickListener { applyQuick(100) }
        binding.quickPlus1000.setOnClickListener { applyQuick(1000) }
        binding.quickMinus100.setOnClickListener { applyQuick(-100) }
        binding.quickMinus1000.setOnClickListener { applyQuick(-1000) }

        binding.btnComplete.setOnClickListener {
            if (currentInput.isNotEmpty()) {
                total += currentInput.toLong()
                currentInput = ""
            }
            val intent = Intent(this, PaymentActivity::class.java).apply {
                putExtra(IntentKeys.EXTRA_RESTAURANT_NAME, restaurantName)
                putExtra(IntentKeys.EXTRA_AMOUNT, total)
            }
            startActivity(intent)
        }
    }

    private fun appendDigits(digits: String) {
        if (currentInput.length + digits.length > 9) return
        currentInput += digits
        updateDisplay()
    }

    private fun backspace() {
        if (currentInput.isNotEmpty()) {
            currentInput = currentInput.dropLast(1)
        } else if (total > 0) {
            total /= 10
        }
        updateDisplay()
    }

    private fun commit(sign: Int) {
        val amount = currentInput.toLongOrNull() ?: 0L
        total = (total + sign * amount).coerceAtLeast(0)
        currentInput = ""
        updateDisplay()
    }

    private fun applyQuick(delta: Int) {
        if (currentInput.isNotEmpty()) {
            total += currentInput.toLong()
            currentInput = ""
        }
        total = (total + delta).coerceAtLeast(0)
        updateDisplay()
    }

    private fun currentValue(): Long = currentInput.toLongOrNull() ?: total

    private fun updateDisplay() {
        binding.amountDisplay.text = "${formatAmount(currentValue())}원"
    }

    private fun formatAmount(value: Long): String = String.format(Locale.KOREA, "%,d", value)
}
