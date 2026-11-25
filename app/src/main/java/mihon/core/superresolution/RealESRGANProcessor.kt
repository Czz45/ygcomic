package mihon.core.superresolution

import android.graphics.Bitmap
import android.util.Log

/**
 * 超分 native 桥接（源自 mihon-super-resolution，MIT）。
 * 包名保持 mihon.core.superresolution 以匹配已编译 so 的 JNI 符号
 * Java_mihon_core_superresolution_RealESRGANProcessor_*,勿改包名/类名/方法名。
 */
class RealESRGANProcessor {
    private var handle: Long = 0L

    val isReady: Boolean
        get() = handle != 0L && nativeLibraryLoaded

    /** modelDir=模型目录名(同时是 param/bin 文件名)，modelType=realcugan|realesrgan */
    fun initialize(modelDir: String, modelType: String, scale: Int, gpuid: Int) {
        if (!nativeLibraryLoaded) {
            Log.w(TAG, "native library unavailable")
            return
        }
        if (handle != 0L) release()
        System.setProperty("KMP_AFFINITY", "disabled")
        System.setProperty("OMP_NUM_THREADS", "1")
        try {
            // fp32（useFp16=false）：Adreno 等 GPU 上 fp16 pipeline 易编译失败返回 null，
            // ncnn 仍 bind 空 VkPipeline → vulkan.adreno.so!vkCmdBindPipeline SIGSEGV，
            // native 层崩溃 Kotlin catch 不住。fp32 稳定优先，牺牲少量速度。
            handle = nativeInit("$modelDir.param", "$modelDir.bin", gpuid, modelType, false, scale)
            Log.i(TAG, "init model=$modelDir handle=$handle fp16=false")
        } catch (e: Throwable) {
            Log.e(TAG, "nativeInit failed: ${e.message}")
            handle = 0L
        }
    }

    fun process(
        input: Bitmap,
        scale: Int,
        denoiseStrength: Float = 0f,
        grayLevels: Int = 0,
        densityCorrection: Boolean = false,
    ): Bitmap {
        if (!isReady) return input
        return try {
            nativeProcess(handle, input, scale, denoiseStrength, grayLevels, densityCorrection) ?: input
        } catch (e: Throwable) {
            Log.e(TAG, "nativeProcess failed: ${e.message}")
            input
        }
    }

    fun release() {
        if (handle != 0L && nativeLibraryLoaded) {
            try { nativeRelease(handle) } catch (_: Throwable) {}
        }
        handle = 0L
    }

    private external fun nativeInit(
        paramPath: String, binPath: String, gpuid: Int,
        modelType: String, useFp16: Boolean, scale: Int,
    ): Long

    private external fun nativeProcess(
        handle: Long, input: Bitmap, scale: Int,
        denoiseStrength: Float, grayLevels: Int, densityCorrection: Boolean,
    ): Bitmap?

    private external fun nativeRelease(handle: Long)

    companion object {
        private const val TAG = "RealESRGAN"
        val nativeLibraryLoaded: Boolean by lazy {
            try {
                System.loadLibrary("realesrgan-ncnn-vulkan")
                Log.i(TAG, "so loaded")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "so load failed: ${e.message}")
                false
            }
        }
    }
}
