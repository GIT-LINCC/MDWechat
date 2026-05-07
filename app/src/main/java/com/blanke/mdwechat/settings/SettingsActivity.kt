package com.blanke.mdwechat.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.blanke.mdwechat.Common
import com.blanke.mdwechat.config.AppCustomConfig
import com.blanke.mdwechat.settings.api.APIManager
import com.blanke.mdwechat.settings.bean.NewestVersionConfig
import com.blanke.mdwechat.util.FileUtils
import com.blanke.mdwechat.util.LogUtil
import com.blanke.mdwechat.util.SharedPreferencesFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lincc.mdwechat.R
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread


/**
 * Created by blanke on 2017/6/8.
 */

class SettingsActivity : Activity() {
    private lateinit var fab: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Common.APP_DIR_PATH
        prepareSharedPreferencesForHooks()
        verifyStoragePermissions(this)
        fab = findViewById(R.id.fab)
        fab.setOnClickListener {
            copyConfig()
            _clearLogs()
            goToWechatSettingPage()
        }
        GetNewestVersion(this, getVersionCode())
    }

    private fun prepareSharedPreferencesForHooks() {
        val sharedPrefsDir = File(filesDir, "../shared_prefs")
        val sharedPrefsFile = File(sharedPrefsDir, Common.MOD_PREFS + ".xml")
        val sdSPFile = File(AppCustomConfig.getConfigFile(Common.MOD_PREFS + ".xml"))
        SettingsFragment.STATIC.sharedPrefsFile = sharedPrefsFile
        SettingsFragment.STATIC.sdSPFile = sdSPFile
        try {
            val shouldImportExternal = sdSPFile.exists() &&
                    (!sharedPrefsFile.exists() || sdSPFile.lastModified() >= sharedPrefsFile.lastModified())
            if (shouldImportExternal) {
                sharedPrefsFile.parentFile?.mkdirs()
                FileInputStream(sdSPFile).use { input ->
                    FileOutputStream(sharedPrefsFile, false).use { output ->
                        FileUtils.copyFile(input, output)
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        applyDefaultHookPreferences()
        val pref = openHookSharedPreferences()
        try {
            val editor = pref.edit()
            if (!pref.contains("hookSwitch")) editor.putBoolean("hookSwitch", true)
            if (!pref.contains("key_hide_tab")) editor.putBoolean("key_hide_tab", false)
            if (!pref.contains("key_hook_tab")) editor.putBoolean("key_hook_tab", true)
            if (!pref.contains("key_hook_tab_bg")) editor.putBoolean("key_hook_tab_bg", false)
            if (!pref.contains("key_hook_bg_immersion")) editor.putBoolean("key_hook_bg_immersion", false)
            if (!pref.contains("key_hook_bubble_tint")) editor.putBoolean("key_hook_bubble_tint", true)
            if (!pref.contains("key_hook_bubble_tint_left")) editor.putInt("key_hook_bubble_tint_left", -1)
            if (!pref.contains("key_hook_bubble_tint_right")) editor.putInt("key_hook_bubble_tint_right", -16537100)
            if (!pref.contains("key_hook_log")) editor.putBoolean("key_hook_log", false)
            if (!pref.contains("key_hook_log_xposed")) editor.putBoolean("key_hook_log_xposed", false)
            editor.commit()
        } catch (ignored: Exception) {
        }
        exportSharedPreferences(pref, sdSPFile)
    }

    private fun applyDefaultHookPreferences() {
        try {
            PreferenceManager.setDefaultValues(
                    this,
                    Common.MOD_PREFS,
                    Context.MODE_WORLD_READABLE,
                    R.xml.pref_settings,
                    false
            )
            return
        } catch (ignored: SecurityException) {
        } catch (ignored: IllegalArgumentException) {
        }
        try {
            PreferenceManager.setDefaultValues(
                    this,
                    Common.MOD_PREFS,
                    Context.MODE_PRIVATE,
                    R.xml.pref_settings,
                    false
            )
        } catch (ignored: Exception) {
        }
    }

    private fun openHookSharedPreferences(): SharedPreferences {
        return try {
            getSharedPreferences(Common.MOD_PREFS, Context.MODE_WORLD_READABLE)
        } catch (ignored: SecurityException) {
            getSharedPreferences(Common.MOD_PREFS, Context.MODE_PRIVATE)
        } catch (ignored: IllegalArgumentException) {
            getSharedPreferences(Common.MOD_PREFS, Context.MODE_PRIVATE)
        }
    }

    fun GetNewestVersion(context: Activity, versionCode: Int) {
        APIManager().getNewestVersion(
                object : Callback {
                    override fun onFailure(call: Call?, e: IOException?) {
                        LogUtil.log("获取最新版本失败," + e?.message)
                    }

                    override fun onResponse(call: Call?, response: Response) {
                        try {
                            if (!response.isSuccessful) {
                                LogUtil.log("获取最新版本失败, HTTP ${response.code()}")
                                return
                            }
                            val body = response.body()?.string()
                            if (body.isNullOrBlank()) {
                                LogUtil.log("获取最新版本失败, empty body")
                                return
                            }
                            val data = Gson().fromJson<NewestVersionConfig>(body, object : TypeToken<NewestVersionConfig>() {}.type)
                                    ?: return
                            val remoteVersionCode = data.versionCode.toIntOrNull() ?: return
                            val ignoreVersion = getSharedPreferences("newestVersion", Context.MODE_PRIVATE).getInt("ignoredVersion", 0)
                            if (remoteVersionCode > versionCode && ignoreVersion < versionCode) {
                                context.runOnUiThread {
                                    showNewestVersion(context, data)
                                }
                            }
                        } catch (e: Exception) {
                            LogUtil.log(e)
                        }
                    }
                }
        )
    }


    @SuppressLint("SetTextI18n")
    private fun showNewestVersion(activity: Activity, data: NewestVersionConfig) {
        val generateWechatLogScrollView = ScrollView(activity)

        val generateWechatLogView = TextView(activity)
        generateWechatLogView.setPadding(72, 15, 72, 0)
        generateWechatLogScrollView.addView(generateWechatLogView)

        generateWechatLogView.text = "版本: ${data.version}\n更新内容: ${data.info}"

        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                    .setTitle("发现新版本")
                    .setView(generateWechatLogScrollView)
                    .setCancelable(true)
                    .setNeutralButton(R.string.text_cancel, null)
                    .setNegativeButton(R.string.text_ignore_this_version) { _, which ->
                        getSharedPreferences("newestVersion", Context.MODE_PRIVATE).edit().putInt("ignoredVersion", data.versionCode.toInt()).apply()
                    }
                    .setPositiveButton(R.string.text_update) { _, which ->
                        ContextCompat.startActivity(activity, Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/GIT-LINCC/MDWechat/releases/tag/${data.version}")), null)
                    }
                    .show()
        }
    }


    private fun _clearLogs() {
        LogUtil.clearFileLogs(SettingsFragment.STATIC.isLogFile)
        Toast.makeText(this, getString(R.string.msg_clear_ok), Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsFragment() {
        findViewById<View>(R.id.pb_loading).visibility = View.GONE
        fab.visibility = View.VISIBLE
        fragmentManager.beginTransaction().replace(R.id.setting_fl_container,
                SettingsFragment()).commit()
    }

    private fun copySharedPrefences() {
        prepareSharedPreferencesForHooks()
    }

    private fun exportSharedPreferences(
            prefs: SharedPreferences,
            sdSPFile: File
    ) {
        try {
            SharedPreferencesFile.write(prefs, sdSPFile)
        } catch (e: Exception) {
            LogUtil.log(e)
        }
    }

    private fun goToWechatSettingPage() {
        Toast.makeText(this, R.string.msg_kill_wechat, Toast.LENGTH_SHORT).show()
        val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        intent.data = Uri.fromParts("package", Common.WECHAT_PACKAGENAME, null)
        startActivity(intent)
    }

    private fun copyConfig() {
        thread {
            try {
                FileUtils.copyAssets(this, Common.APP_DIR_PATH, Common.CONFIG_WECHAT_DIR)
                FileUtils.copyAssets(this, Common.APP_DIR_PATH, Common.CONFIG_VIEW_DIR)
                FileUtils.copyAssets(this, Common.APP_DIR_PATH, Common.HELP_DIR)
                FileUtils.copyAssets(this, Common.APP_DIR_PATH, Common.ICON_DIR)
            } catch (e: Exception) {
                LogUtil.log(e)
            }
            copySharedPrefences()
            Handler(Looper.getMainLooper()).post {
                showSettingsFragment()
            }
        }
    }

    private val REQUEST_EXTERNAL_STORAGE = 1
    private val PERMISSIONS_STORAGE = arrayOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE")

    fun verifyStoragePermissions(activity: Activity) {
        try {
            val permission = ActivityCompat.checkSelfPermission(activity,
                    "android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED
//                    && ActivityCompat.checkSelfPermission(activity,
//                    "android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED
            if (!permission) {
                ActivityCompat.requestPermissions(activity, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE)
            } else {
                copyConfig()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_EXTERNAL_STORAGE) {
            if (grantResults.isNotEmpty()
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                copyConfig()
            } else {
                Toast.makeText(this, R.string.msg_permission_fail, Toast.LENGTH_LONG).show()
                copyConfig()
            }
        }
    }

    private fun getVersionCode(): Int {
        // 包管理器 可以获取清单文件信息
        try {
            // 获取包信息
            // 参1 包名 参2 获取额外信息的flag 不需要的话 写0
            val packageInfo: PackageInfo = packageManager.getPackageInfo(
                    packageName, 0)
            return packageInfo.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        return 0
    }
}
