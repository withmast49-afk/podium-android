package dev.podium.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * The virtual iPod touch's 640x960 screen: draws the core's frames at ~30 fps and
 * turns finger touches into the device's own pixel coordinates.
 */
class ScreenView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var renderer: Renderer? = null

    // What to show while the iPod is off or still starting, i.e. whenever iOS hasn't drawn a frame yet.
    @Volatile private var overlayTitle = "Powered off"
    @Volatile private var overlayFraction = -1f
    @Volatile private var overlayLit = false
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Sets the placeholder text; [fraction] < 0 hides the progress bar, [lit] brightens the logo while starting. */
    fun setOverlay(title: String, fraction: Float, lit: Boolean) {
        overlayTitle = title
        overlayFraction = fraction
        overlayLit = lit
    }

    private fun drawPlaceholder(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val size = minOf(width, height) * 0.30f
        val centerX = width / 2
        val centerY = height * 0.42f
        val lit = overlayLit
        drawAppleLogo(canvas, centerX, centerY, size, if (lit) 235 else 70)

        textPaint.color = Color.WHITE
        textPaint.alpha = if (lit) 210 else 120
        textPaint.textSize = width * 0.045f
        canvas.drawText(overlayTitle, centerX, centerY + size * 0.95f, textPaint)

        val fraction = overlayFraction
        if (fraction >= 0f) {
            val barWidth = width * 0.45f
            val barHeight = maxOf(4f, height * 0.006f)
            val left = centerX - barWidth / 2
            val top = centerY + size * 1.15f
            logoPaint.color = Color.WHITE
            logoPaint.alpha = 60
            canvas.drawRoundRect(RectF(left, top, left + barWidth, top + barHeight), barHeight, barHeight, logoPaint)
            logoPaint.alpha = 225
            canvas.drawRoundRect(RectF(left, top, left + barWidth * fraction.coerceIn(0f, 1f), top + barHeight), barHeight, barHeight, logoPaint)
        }
    }

    /** A stylized apple (two lobes, a leaf, a bite) drawn with plain shapes, white on black. */
    private fun drawAppleLogo(canvas: Canvas, centerX: Float, centerY: Float, size: Float, alpha: Int) {
        val r = size / 2
        logoPaint.color = Color.WHITE
        logoPaint.alpha = alpha
        canvas.drawCircle(centerX - r * 0.32f, centerY + r * 0.15f, r * 0.64f, logoPaint)
        canvas.drawCircle(centerX + r * 0.32f, centerY + r * 0.15f, r * 0.64f, logoPaint)
        canvas.drawOval(RectF(centerX - r * 0.62f, centerY + r * 0.35f, centerX + r * 0.62f, centerY + r * 0.98f), logoPaint)
        canvas.save()
        canvas.rotate(-38f, centerX + r * 0.18f, centerY - r * 0.62f)
        canvas.drawOval(RectF(centerX + r * 0.02f, centerY - r * 1.05f, centerX + r * 0.62f, centerY - r * 0.62f), logoPaint)
        canvas.restore()
        // The bite, cut out of the right side with the background colour.
        logoPaint.color = Color.BLACK
        logoPaint.alpha = 255
        canvas.drawCircle(centerX + r * 0.95f, centerY - r * 0.05f, r * 0.30f, logoPaint)
    }

    init {
        // No setBackgroundColor here: a SurfaceView's own background is drawn over the surface
        // and would hide every frame. The renderer paints black itself.
        holder.addCallback(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        var width = maxWidth
        var height = width * 3 / 2
        if (maxHeight > 0 && height > maxHeight) {
            height = maxHeight
            width = height * 2 / 3
        }
        setMeasuredDimension(width, height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderer = Renderer(holder).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer?.let {
            it.running = false
            it.join()
        }
        renderer = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return true
        val scaleX = PodiumNative.frameWidth().toDouble() / width
        val scaleY = PodiumNative.frameHeight().toDouble() / height
        fun send(phase: Int, index: Int) {
            PodiumNative.touch(phase, event.getPointerId(index), event.getX(index) * scaleX, event.getY(index) * scaleY)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(0, event.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) send(1, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(2, event.actionIndex)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until event.pointerCount) send(2, i)
        }
        return true
    }

    private inner class Renderer(private val holder: SurfaceHolder) : Thread("podium-render") {
        @Volatile var running = true

        override fun run() {
            val frameWidth = PodiumNative.frameWidth()
            val frameHeight = PodiumNative.frameHeight()
            val bitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
            val source = Rect(0, 0, frameWidth, frameHeight)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            var lastBlank = 0L
            while (running) {
                val started = SystemClock.uptimeMillis()
                val drew = PodiumNative.drawFrame(bitmap)
                if (drew || started - lastBlank > 250) {
                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        if (drew) {
                            canvas.drawColor(Color.BLACK)
                            canvas.drawBitmap(bitmap, source, Rect(0, 0, canvas.width, canvas.height), paint)
                        } else {
                            drawPlaceholder(canvas)
                        }
                        holder.unlockCanvasAndPost(canvas)
                    }
                    if (!drew) lastBlank = started
                }
                val spent = SystemClock.uptimeMillis() - started
                try {
                    sleep(maxOf(1L, 33L - spent))
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }
}
