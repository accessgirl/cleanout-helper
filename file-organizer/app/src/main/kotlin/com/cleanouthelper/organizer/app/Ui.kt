package com.cleanouthelper.organizer.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Small helpers for building the screens in code, with light and dark colours. */
class Ui(val context: Context) {
    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val text = if (dark) Color.parseColor("#E8EAED") else Color.parseColor("#1F2328")
    val muted = if (dark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6673")
    val card = if (dark) Color.parseColor("#1A1D23") else Color.WHITE
    val border = if (dark) Color.parseColor("#2C313A") else Color.parseColor("#E3E7EE")
    val brand = if (dark) Color.parseColor("#60A5FA") else Color.parseColor("#2563EB")
    val onBrand = if (dark) Color.parseColor("#0B1220") else Color.WHITE
    val good = if (dark) Color.parseColor("#34D399") else Color.parseColor("#047857")
    val warn = if (dark) Color.parseColor("#FBBF24") else Color.parseColor("#B45309")

    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun column(padding: Int = 0, vararg children: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        children.forEach { addView(it) }
    }

    fun label(s: CharSequence, size: Float = 15f, bold: Boolean = false, color: Int = text, top: Int = 0) = TextView(context).apply {
        this.text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    }

    fun title(s: CharSequence) = label(s, 24f, bold = true)
    fun heading(s: CharSequence) = label(s, 17f, bold = true, top = 2)
    fun note(s: CharSequence, top: Int = 4) = label(s, 13.5f, color = muted, top = top)

    fun card(vararg children: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(card)
            setStroke(dp(1), border)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        children.forEach { addView(it) }
    }

    fun button(s: String, primary: Boolean = true, onClick: () -> Unit) = Button(context).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) onBrand else brand)
        stateListAnimator = null
        minHeight = dp(52)
        val shape = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(if (primary) brand else Color.TRANSPARENT)
            setStroke(dp(if (primary) 0 else 2), brand)
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 128, 128, 128)), shape, null)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    fun check(s: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit = {}) = CheckBox(context).apply {
        text = if (hint == null) s else "$s\n$hint"
        isChecked = checked
        textSize = 15f
        setTextColor(this@Ui.text)
        buttonTintList = ColorStateList.valueOf(brand)
        setPadding(dp(6), dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    fun progressBar() = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressTintList = ColorStateList.valueOf(brand)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(16) }
    }

    fun row(vararg children: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        children.forEach { addView(it) }
    }
}
