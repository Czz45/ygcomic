package mihon.core.superresolution

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Vulkan 能力探测（源自 mihon-super-resolution，MIT）。
 * 包名/类名/方法名必须保持以匹配 JNI 符号 Java_mihon_core_superresolution_VulkanHelper_*，勿改。
 */
object VulkanHelper {
    private const val TAG = "VulkanHelper"

    fun isVulkanSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) ||
                pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
    }

    val gpuCount: Int by lazy {
        try {
            if (RealESRGANProcessor.nativeLibraryLoaded) nativeGetGpuCount() else 0
        } catch (e: Throwable) {
            Log.w(TAG, "gpuCount failed: ${e.message}")
            0
        }
    }

    fun getDeviceInfo(gpuid: Int): String = try {
        if (RealESRGANProcessor.nativeLibraryLoaded) nativeGetDeviceInfo(gpuid) else "Unknown"
    } catch (e: Throwable) {
        Log.w(TAG, "getDeviceInfo failed: ${e.message}")
        "Unknown"
    }

    private external fun nativeGetGpuCount(): Int
    private external fun nativeGetDeviceInfo(gpuid: Int): String
}
