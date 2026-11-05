package it.mbs.piiktv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Piccoli helper per un'interfaccia da TV navigabile con il telecomando (D-pad). */
object Ui {
    const val ACCENT = 0xFF7A5CFF.toInt()
    const val BG = 0xFF100C1E.toInt()
    const val CARD = 0xFF1E1836.toInt()
    const val CARD_FOCUS = 0xFF2E2556.toInt()
    const val TEXT = 0xFFF2F0FA.toInt()
    const val TEXT_DIM = 0xFFA9A3C2.toInt()
    const val OK = 0xFF5CD69A.toInt()
    const val WARN = 0xFFFFC857.toInt()

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    fun cardBackground(ctx: Context): StateListDrawable {
        val r = dp(ctx, 14f).toFloat()
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(CARD_FOCUS, r, ACCENT, dp(ctx, 3f)))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(CARD_FOCUS, r, ACCENT, dp(ctx, 3f)))
            addState(intArrayOf(), rounded(CARD, r))
        }
    }

    fun text(ctx: Context, s: CharSequence, sizeSp: Float, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun button(ctx: Context, label: CharSequence, sizeSp: Float = 20f, onClick: (View) -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        background = cardBackground(ctx)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        val ph = dp(ctx, 22f)
        val pv = dp(ctx, 14f)
        setPadding(ph, pv, ph, pv)
        isFocusable = true
        isFocusableInTouchMode = false
        setOnClickListener(onClick)
        stateListAnimator = null
    }

    fun vertical(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun horizontal(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, end: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top; marginEnd = end }

    fun input(ctx: Context, hint: String, type: Int = InputType.TYPE_CLASS_TEXT, value: String? = null) = EditText(ctx).apply {
        this.hint = hint
        inputType = type
        setText(value ?: "")
        setTextColor(Color.WHITE)
        setHintTextColor(TEXT_DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        isSingleLine = true
        background = cardBackground(ctx)
        val p = dp(ctx, 16f)
        setPadding(p, p, p, p)
    }
}
