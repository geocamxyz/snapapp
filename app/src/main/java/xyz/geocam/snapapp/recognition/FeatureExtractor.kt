package xyz.geocam.snapapp.recognition

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs megaloc.tflite to produce a 1024-dim L2-normalised descriptor.
 *
 * Expected model (export from MegaLoc server side):
 *   Input:  float32[1, INPUT_H, INPUT_W, 3]  (channels-last, ImageNet-normalised)
 *   Output: float32[1, DESCRIPTOR_DIM]        (L2-normalised)
 *
 * Place megaloc.tflite in app/src/main/assets/.
 *
 * ImageNet normalisation: mean=[0.485, 0.456, 0.406] std=[0.229, 0.224, 0.225]
 */
class FeatureExtractor(context: Context) : Closeable {

    private val gpuDelegate = GpuDelegate()
    private val interpreter: Interpreter

    init {
        val opts = Interpreter.Options().addDelegate(gpuDelegate)
        val assetFd = context.assets.openFd(MODEL_ASSET)
        val modelBuf = assetFd.createInputStream().use { it.readBytes() }
        assetFd.close()
        val buf = ByteBuffer.allocateDirect(modelBuf.size).apply {
            order(ByteOrder.nativeOrder())
            put(modelBuf)
            rewind()
        }
        interpreter = Interpreter(buf, opts)
    }

    /** Resize [bitmap] to the model input size, normalise, and return the descriptor. */
    fun extract(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_W, INPUT_H, true)
        val inputBuf = bitmapToInputBuffer(scaled)

        val output = Array(1) { FloatArray(DESCRIPTOR_DIM) }
        interpreter.run(inputBuf, output)
        return output[0]
    }

    private fun bitmapToInputBuffer(bmp: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(1 * INPUT_H * INPUT_W * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_H * INPUT_W)
        bmp.getPixels(pixels, 0, INPUT_W, 0, 0, INPUT_W, INPUT_H)
        for (px in pixels) {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8)  and 0xFF) / 255f
            val b = ( px         and 0xFF) / 255f
            buf.putFloat((r - MEAN[0]) / STD[0])
            buf.putFloat((g - MEAN[1]) / STD[1])
            buf.putFloat((b - MEAN[2]) / STD[2])
        }
        buf.rewind()
        return buf
    }

    override fun close() {
        interpreter.close()
        gpuDelegate.close()
    }

    companion object {
        const val MODEL_ASSET     = "megaloc.tflite"
        const val INPUT_H         = 322
        const val INPUT_W         = 322
        const val DESCRIPTOR_DIM  = 1024

        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD  = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
