package com.krishiradar.app.utils

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.StatFs
import com.krishiradar.app.data.model.DeviceCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun detect(): DeviceCapability {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val internalStat = StatFs(context.filesDir.absolutePath)
        val freeStorage = internalStat.availableBlocksLong * internalStat.blockSizeLong
        val totalStorage = internalStat.blockCountLong * internalStat.blockSizeLong

        val abis = Build.SUPPORTED_ABIS.toList()

        return DeviceCapability(
            totalRamBytes = memInfo.totalMem,
            availableRamBytes = memInfo.availMem,
            freeStorageBytes = freeStorage,
            totalStorageBytes = totalStorage,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            hasNpu = detectNpu(),
            hasGpu = true,
            gpuVendor = detectGpuVendor(),
            androidVersion = Build.VERSION.SDK_INT,
            abi = abis
        )
    }

    private fun detectNpu(): Boolean {
        // Heuristic: Snapdragon 8 Gen 2+, Exynos 2200+, Dimensity 9000+ have dedicated NPUs
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        return hardware.contains("qcom") || hardware.contains("exynos") ||
               board.contains("lahaina") || board.contains("kalama") ||
               board.contains("pineapple") || board.contains("sun")
    }

    private fun detectGpuVendor(): String {
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(display, null, 0, null, 0)
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_NONE), 0, configs, 0, 1, numConfigs, 0)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            val glContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surface, surface, glContext)
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unknown"
            EGL14.eglDestroyContext(display, glContext)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglTerminate(display)
            vendor
        } catch (e: Exception) {
            Build.HARDWARE
        }
    }
}
