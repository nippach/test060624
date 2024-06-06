package agency.galt.ml_passport_detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.plus
import androidx.core.graphics.times
import androidx.core.graphics.withSave

class FrameView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    var frameColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    var preview: Pair<Bitmap, Int>? = null

    private var halfRect = RectF(-.5f, -.5f, .5f, .5f)

    private val previewRect: RectF
        get() = RectF(-225f, -225f, -25f, -25f).times(resources.displayMetrics.density)
            .plus(PointF(width.toFloat(), height.toFloat()))

    private var maskPath: Path = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        addRect(RectF(-1e4f, -1e4f, 1e4f, 1e4f), Path.Direction.CW)
        addRoundRect(RectF(-169.5f, -234f, 169.5f, 234f), 10.0f, 10.0f, Path.Direction.CW)
    }

    private var maskPaint: Paint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#7A1D1D1D")
    }

    private var framePath: Path = Path().apply {
        addRoundRect(RectF(-169.5f, -234f, 169.5f, 234f), 10.0f, 10.0f, Path.Direction.CW)
        addRect(RectF(-169.5f, 0f, 169.5f, 0f), Path.Direction.CW)
        addRoundRect(RectF(-151.5f, 29f, -46.5f, 158f), 6f, 6f, Path.Direction.CW)
        addRoundRect(RectF(126.5f, 43f, 155.5f, 151.5f), 6f, 6f, Path.Direction.CW)
        addRoundRect(RectF(126.5f, -179f, 155.5f, -52f), 6f, 6f, Path.Direction.CW)
    }

    private val previewRectPaint: Paint
        get() = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.WHITE
        }

    private val framePaint: Paint
        get() = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = frameColor
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        preview?.also {
            canvas.withSave {
                canvas.drawRect(previewRect, previewRectPaint)

                canvas.translate(previewRect.centerX(), previewRect.centerY())
                canvas.scale(previewRect.width(), previewRect.height())

                canvas.rotate(it.second.toFloat())
                canvas.drawBitmap(it.first, null, halfRect, null)
            }
        }

        canvas.withSave {
            canvas.translate(width * .5f, height * .5f)

            val scale = minOf(
                (width - 32f * resources.displayMetrics.density) / 343f,
                (height - (32f + 120f /* margin for text */) * resources.displayMetrics.density) / 472f,
            )
            canvas.scale(scale, scale)

            canvas.drawPath(maskPath, maskPaint)
            canvas.drawPath(framePath, framePaint)
        }
    }
}