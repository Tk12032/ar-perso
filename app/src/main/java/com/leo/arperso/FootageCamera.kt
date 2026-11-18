package com.leo.arperso

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * Vue caméra à deux visages :
 * - mode normal : l'aperçu caméra classique (PreviewView en TextureView, dessiné dans la fenêtre,
 *   il cache donc tout ce qui est derrière, y compris la scène 3D) ;
 * - mode found footage : l'aperçu est masqué et on voit une [FootageSurface] qui affiche
 *   l'image en basse définition, verdâtre, bruitée, avec des défauts de bande VHS.
 */
class FootageCameraView(context: Context, lifecycleOwner: LifecycleOwner) : FrameLayout(context) {

    private val footage = FootageSurface(context)
    private val preview = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    var footageMode: Boolean = false
        set(value) {
            field = value
            footage.active = value
            preview.visibility = if (value) View.GONE else View.VISIBLE
        }

    /** Force du glitch (0 = aucun, 1 = image complètement détruite). */
    var glitch: Float
        get() = footage.glitch
        set(value) { footage.glitch = value }

    init {
        addView(footage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image -> footage.onFrame(image) }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        analysisExecutor.shutdown()
    }
}

/** Surface qui dessine l'image « caméscope pourri ». */
private class FootageSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    @Volatile var active = false
    @Volatile var glitch = 0f
    @Volatile private var surfaceReady = false

    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private var seed = 0x2545F491
    private var frameCount = 0
    private var rollOffset = 0
    private var lastDraw = 0L
    private val paint = Paint().apply { isFilterBitmap = false } // gros pixels bien visibles
    private val src = Rect()
    private val dst = RectF()

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    private fun rnd(): Int {
        var x = seed
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        seed = x
        return x
    }

    fun onFrame(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            // ~15 images/s : la saccade fait partie du charme
            if (!active || !surfaceReady || now - lastDraw < 66) return
            lastDraw = now
            draw(image)
        } finally {
            image.close()
        }
    }

    private fun draw(image: ImageProxy) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val iw = image.width
        val ih = image.height
        val rotation = image.imageInfo.rotationDegrees
        val portrait = rotation == 90 || rotation == 270
        val ow = if (portrait) ih else iw
        val oh = if (portrait) iw else ih

        if (bitmap?.width != ow || bitmap?.height != oh) {
            bitmap = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            pixels = IntArray(ow * oh)
        }
        val bmp = bitmap!!
        frameCount++
        val g = glitch

        // Défauts de bande : une zone qui défile et décale les lignes, parfois un « roulement » vertical.
        val bandCenter = (frameCount * 7) % (oh + 60) - 30
        if (rollOffset == 0 && (rnd() and 0x7fffffff) % 120 == 0) rollOffset = oh / 3
        if (g > 0.5f && (rnd() and 3) == 0) rollOffset = (rnd() and 0x7fffffff) % oh
        val roll = rollOffset
        rollOffset = (rollOffset * 0.6f).toInt()

        for (y in 0 until oh) {
            val sy = (y + roll) % oh
            val inBand = kotlin.math.abs(y - bandCenter) < 6
            var shift = if (inBand) ((rnd() and 0x7fffffff) % 9) - 2 else 0
            if (g > 0f && (rnd() and 0x7fffffff) % 100 < (g * 40).toInt()) shift += ((rnd() and 0x7fffffff) % 40) - 20
            val bright = if (inBand) 40 else 0
            for (x in 0 until ow) {
                val sx = ((x + shift) % ow + ow) % ow
                // Rotation de l'image capteur vers le portrait
                val ix: Int
                val iy: Int
                when (rotation) {
                    90 -> { ix = sy; iy = ih - 1 - sx }
                    270 -> { ix = iw - 1 - sy; iy = sx }
                    180 -> { ix = iw - 1 - sx; iy = ih - 1 - sy }
                    else -> { ix = sx; iy = sy }
                }
                var v = buffer.get(iy * rowStride + ix * pixStride).toInt() and 0xff
                v = ((v - 20) * 1.3f).toInt() + bright + ((rnd() shr 26)) // contraste + grain
                if (g > 0.7f && (rnd() and 7) == 0) v = 255 - v
                v = v.coerceIn(0, 255)
                val r = (v * 0.78f).toInt()
                val gr = (v * 0.95f).toInt()
                val b = (v * 0.72f).toInt()
                pixels[y * ow + x] = (0xff shl 24) or (r shl 16) or (gr shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, ow, 0, 0, ow, oh)

        val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
        try {
            // Recadrage centré comme PreviewView FILL_CENTER (même champ de vision que la scène 3D).
            val scale = maxOf(canvas.width / ow.toFloat(), canvas.height / oh.toFloat())
            val dw = ow * scale
            val dh = oh * scale
            src.set(0, 0, ow, oh)
            dst.set((canvas.width - dw) / 2f, (canvas.height - dh) / 2f, (canvas.width + dw) / 2f, (canvas.height + dh) / 2f)
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(bmp, src, dst, paint)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }
}
