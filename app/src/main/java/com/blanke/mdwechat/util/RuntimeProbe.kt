package com.blanke.mdwechat.util

import android.content.Context
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeProbe {
    private const val DIR_NAME = "mdwechat"
    private const val FILE_NAME = "runtime_probe.txt"

    private fun getProbeFile(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val probeDir = File(baseDir, DIR_NAME)
        if (!probeDir.exists()) {
            probeDir.mkdirs()
        }
        return File(probeDir, FILE_NAME)
    }

    fun append(context: Context?, message: String) {
        context ?: return
        try {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
            FileUtils.write(getProbeFile(context).absolutePath, "$time $message\n", true)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    fun clear(context: Context?) {
        context ?: return
        try {
            val file = getProbeFile(context)
            if (file.exists()) {
                file.delete()
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}
