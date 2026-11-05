package it.mbs.piiktv.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/** FrameLayout che mantiene le proporzioni del video (letterbox/pillarbox) centrandolo. */
class AspectFrame(ctx: Context) : FrameLayout(ctx) {
    private var aspect = 16f / 9f

    fun setAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val a = width.toFloat() / height
        if (kotlin.math.abs(a - aspect) > 0.001f) {
            aspect = a
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (w == 0 || h == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        var cw = w
        var ch = (w / aspect).toInt()
        if (ch > h) {
            ch = h
            cw = (h * aspect).toInt()
        }
        val cws = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
        val chs = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cws, chs)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        for (i in 0 until childCount) {
            val c: View = getChildAt(i)
            val l = (w - c.measuredWidth) / 2
            val t = (h - c.measuredHeight) / 2
            c.layout(l, t, l + c.measuredWidth, t + c.measuredHeight)
        }
    }
}
