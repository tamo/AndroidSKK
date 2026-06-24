package jp.deadend.noname.skk

import android.content.ContentResolver
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import kotlin.math.max

class CroppedDrawable(
    res: Resources, bitmap: Bitmap, val uri: String,
    private val getFullHeight: () -> Int,
    private val getVarHeight: () -> Int = { 0 }
) : BitmapDrawable(res, bitmap) {
    private val bitmapW = bitmap.width
    private val bitmapH = bitmap.height
    private val mMatrix = Matrix()
    private val mShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    private val lastBounds = Rect()
    private var lastMaxH = -1

    init {
        paint.shader = mShader
    }

    override fun getIntrinsicWidth() = -1
    override fun getIntrinsicHeight() = -1

    override fun draw(canvas: Canvas) {
        val b = bounds
        val boundsW = b.width()
        val boundsH = b.height()
        val maxH = getFullHeight()
        if (bitmapW <= 0 || bitmapH <= 0 || boundsW <= 0 || maxH <= 0) return

        if (b != lastBounds || maxH != lastMaxH) {
            SKKLog.d("background: bounds=$b (last=$lastBounds) maxH=$maxH (last=$lastMaxH)")
            val scale: Float
            val dx: Float
            val dy: Float
            if (bitmapW.toLong() * maxH > boundsW.toLong() * bitmapH) {
                scale = maxH.toFloat() / bitmapH.toFloat()
                dx = (boundsW - bitmapW * scale) * 0.5f; dy = 0f
            } else {
                scale = boundsW.toFloat() / bitmapW.toFloat()
                dy = (maxH - bitmapH * scale) * 0.5f; dx = 0f
            }
            val offset = maxH - boundsH // 底面に合わせる
            mMatrix.setScale(scale, scale)
            mMatrix.postTranslate(dx + b.left, dy + b.top - offset)
            mShader.setLocalMatrix(mMatrix)

            lastBounds.set(b)
            lastMaxH = maxH
        }

        canvas.drawRect(
            b.left.toFloat(), (b.top + getVarHeight()).toFloat(),
            b.right.toFloat(), b.bottom.toFloat(),
            paint
        )
    }

    companion object {
        fun decode(
            resolver: ContentResolver, uri: Uri, targetW: Int, targetH: Int
        ): Bitmap? = runCatching {
            val src = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { _ -> false }
                val (w, h) = info.size.run { width.toFloat() to height.toFloat() }
                val scale = max(targetW / w, targetH / h)
                decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            }
        }.getOrElse { tr ->
            SKKLog.w("Failed to decode image: $uri", tr)
            null
        }
    }
}
