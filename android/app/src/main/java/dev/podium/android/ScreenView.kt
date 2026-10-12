package dev.podium.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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

    private class Renderer(private val holder: SurfaceHolder) : Thread("podium-render") {
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
                        canvas.drawColor(Color.BLACK)
                        if (drew) canvas.drawBitmap(bitmap, source, Rect(0, 0, canvas.width, canvas.height), paint)
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
