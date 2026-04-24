package xyz.geocam.snapapp

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class MatchOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var matchScore: Float? = null
    private var alpha_ = 0f  // 0=invisible 1=full

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00DD44.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 12f
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.OUTER)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF55.toInt()
        textSize = 42f
        isFakeBoldText = true
    }
    private val rect = RectF()

    /** Call from main thread with the current best score, or null to fade out. */
    fun setMatch(score: Float?) {
        matchScore = score
        val target = if (score != null) 1f else 0f
        animate()
            .alpha(target)
            .setDuration(300)
            .withStartAction { if (target > 0f) visibility = VISIBLE }
            .withEndAction { if (target == 0f) visibility = INVISIBLE }
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        val score = matchScore ?: return
        val pad = 16f
        rect.set(pad, pad, width - pad, height - pad)
        borderPaint.alpha = (alpha_ * 255).toInt()
        canvas.drawRoundRect(rect, 24f, 24f, borderPaint)

        textPaint.alpha = (alpha_ * 255).toInt()
        val label = "%.0f%%".format(score * 100)
        canvas.drawText(label, pad + 20f, height - pad - 20f, textPaint)
    }

    // Keep our own alpha_ in sync for draw calls since View.getAlpha() is animated.
    override fun setAlpha(alpha: Float) {
        alpha_ = alpha
        super.setAlpha(alpha)
        invalidate()
    }

    init { visibility = INVISIBLE }
}
