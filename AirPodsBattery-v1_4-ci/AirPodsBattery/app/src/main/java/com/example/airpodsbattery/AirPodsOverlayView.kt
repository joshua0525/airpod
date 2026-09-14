package com.example.airpodsbattery

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** A small native View so the popup can be hosted by WindowManager from a Service. */
class AirPodsOverlayView(
    context: Context,
    private val onClose: () -> Unit
) : FrameLayout(context) {
    private val title = text(18f, Color.rgb(23, 25, 28), true)
    private val subtitle = text(12f, Color.rgb(104, 113, 125), false)
    private val product = ImageView(context)
    private val left = BatteryPart("왼쪽")
    private val right = BatteryPart("오른쪽")
    private val case = BatteryPart("케이스")

    init {
        setBackgroundColor(Color.TRANSPARENT)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
            background = rounded(Color.WHITE, 30f)
            elevation = dp(8).toFloat()
        }
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(10), dp(8), dp(10), dp(10))
        })

        val header = FrameLayout(context)
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        heading.addView(title)
        heading.addView(subtitle, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })
        header.addView(heading, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val close = TextView(context).apply {
            text = "×"
            textSize = 26f
            setTextColor(Color.rgb(104, 113, 125))
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onClose() }
            contentDescription = "배터리 카드 닫기"
        }
        header.addView(close, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END or Gravity.TOP))
        card.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        product.setImageResource(R.drawable.airpods_pro)
        product.scaleType = ImageView.ScaleType.CENTER_CROP
        product.background = rounded(Color.BLACK, 22f)
        card.addView(product, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(178)).apply {
            topMargin = dp(9)
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(left.view, weightParams())
        row.addView(Space(context), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(right.view, weightParams())
        row.addView(Space(context), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(case.view, weightParams())
        card.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        update(AirPodsStatus("AirPods", null, null, null, false, false, false))
    }

    fun update(status: AirPodsStatus) {
        title.text = status.deviceName ?: status.model
        subtitle.text = if (!status.deviceName.isNullOrBlank() && status.deviceName != status.model) {
            "${status.model} · 주변에서 감지됨"
        } else {
            "주변에서 감지됨"
        }
        left.update(status.leftBattery, status.leftCharging)
        right.update(status.rightBattery, status.rightCharging)
        case.update(status.caseBattery, status.caseCharging)
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)

    private inner class BatteryPart(label: String) {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(7), dp(12), dp(7), dp(10))
            background = rounded(Color.rgb(243, 245, 247), 17f)
        }
        private val name = text(12f, Color.rgb(104, 113, 125), false).apply { text = label }
        private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        private val value = text(17f, Color.rgb(23, 25, 28), true)
        private val charging = text(10f, Color.rgb(33, 140, 81), false)

        init {
            view.addView(name)
            view.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(7)).apply {
                topMargin = dp(10)
            })
            view.addView(value, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(7)
            })
            view.addView(charging, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(15)).apply {
                topMargin = dp(2)
            })
        }

        fun update(percent: Int?, isCharging: Boolean) {
            bar.progress = percent ?: 0
            value.text = percent?.let { "$it%" } ?: "—"
            charging.text = when {
                percent == null -> "정보 없음"
                isCharging -> "충전 중 ⚡"
                else -> ""
            }
            val color = if (percent != null && percent <= 20) Color.rgb(217, 66, 66) else Color.rgb(33, 140, 81)
            charging.setTextColor(color)
            bar.progressDrawable?.let { drawable ->
                drawable.setTint(color)
            }
        }
    }

    private fun text(size: Float, color: Int, bold: Boolean) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()
}
