package com.blanke.mdwechat.util

import android.content.Context
import android.os.Environment
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeProbe {
    private const val DIR_NAME = "mdwechat"
    private const val FILE_NAME = "runtime_probe.txt"

    private fun getProbeFile(context: Context): File {
        val baseDir = context.filesDir
        val probeDir = File(baseDir, DIR_NAME)
        if (!probeDir.exists()) {
            probeDir.mkdirs()
        }
        return File(probeDir, FILE_NAME)
    }

    fun append(context: Context?, message: String) {
        append(context, FILE_NAME, message)
    }

    fun append(context: Context?, fileName: String, message: String) {
        context ?: return
        try {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
            val line = "$time $message\n"
            val file = if (fileName == FILE_NAME) {
                getProbeFile(context)
            } else {
                val probeDir = File(context.filesDir, DIR_NAME)
                if (!probeDir.exists()) {
                    probeDir.mkdirs()
                }
                File(probeDir, fileName)
            }
            FileUtils.write(file.absolutePath, line, true)
            writeExternal(fileName, line)
            writeAppExternal(context, fileName, line)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun writeExternal(fileName: String, line: String) {
        try {
            val logDir = File(
                Environment.getExternalStorageDirectory(),
                DIR_NAME + File.separator + "logs"
            )
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            FileUtils.write(File(logDir, fileName).absolutePath, line, true)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun writeAppExternal(context: Context, fileName: String, line: String) {
        try {
            val baseDir = context.getExternalFilesDir(null) ?: return
            val logDir = File(baseDir, DIR_NAME + File.separator + "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            FileUtils.write(File(logDir, fileName).absolutePath, line, true)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    fun clear(context: Context?) {
        context ?: return
        try {
            clearProbeDir(File(context.filesDir, DIR_NAME))
            clearProbeDir(File(Environment.getExternalStorageDirectory(), DIR_NAME + File.separator + "logs"))
            context.getExternalFilesDir(null)?.let { baseDir ->
                clearProbeDir(File(baseDir, DIR_NAME + File.separator + "logs"))
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun clearProbeDir(dir: File) {
        if (!dir.exists()) {
            return
        }
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension == "txt" && !file.name.startsWith("MDWechat_log_")) {
                file.delete()
            }
        }
    }
}
