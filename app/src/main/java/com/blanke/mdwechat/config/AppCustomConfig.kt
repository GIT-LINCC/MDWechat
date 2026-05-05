package com.blanke.mdwechat.config

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.blanke.mdwechat.Common
import com.blanke.mdwechat.bean.FLoatButtonConfigItem
import com.blanke.mdwechat.bean.FloatButtonConfig
import com.blanke.mdwechat.bean.PicPosition
import com.blanke.mdwechat.bean.PicPositionConfig
import com.blanke.mdwechat.util.BitmapUtil
import com.blanke.mdwechat.util.LogUtil
import com.blanke.mdwechat.util.MaterialTabCustomIconPolicy
import com.blankj.utilcode.util.CloseUtils
import com.blankj.utilcode.util.FileIOUtils
import com.google.gson.Gson
import com.google.gson.JsonObject
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * Created by blanke on 2017/10/13.
 */

object AppCustomConfig {
    var bitmapScale = 1F
    private val gson by lazy { Gson() }

    fun getWxVersionConfig(version: String): WxVersionConfig {
        val configName = version + ".config"
        return tryLoadWxVersionConfig(configName)
    }

    private fun tryLoadWxVersionConfig(configName: String): WxVersionConfig {
        try {
            return readWxVersionConfig(FileInputStream(getWxConfigFile(configName)))
        } catch (fileError: Exception) {
            try {
                BundledWxVersionConfigs.open(configName)?.let {
                    return readWxVersionConfig(it)
                }
                return readWxVersionConfig(openBundledWxConfigFromApk(configName))
            } catch (codeError: Exception) {
                try {
                    return readWxVersionConfig(openBundledWxConfig(configName))
                } catch (assetError: Exception) {
                    codeError.addSuppressed(assetError)
                    fileError.addSuppressed(codeError)
                    throw fileError
                }
            }
        }
    }

    private fun openBundledWxConfig(configName: String): InputStream {
        return getModuleContext().assets.open("${Common.CONFIG_WECHAT_DIR}/$configName")
    }

    private fun openBundledWxConfigFromApk(configName: String): InputStream {
        val appInfo = getModuleApplicationInfo()
        ZipFile(appInfo.sourceDir).use { zipFile ->
            val entry = zipFile.getEntry("assets/${Common.CONFIG_WECHAT_DIR}/$configName")
                    ?: throw java.io.FileNotFoundException("assets/${Common.CONFIG_WECHAT_DIR}/$configName")
            val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
            return ByteArrayInputStream(bytes)
        }
    }

    private fun readWxVersionConfig(inputStream: InputStream): WxVersionConfig {
        inputStream.use { stream ->
            InputStreamReader(stream).use { reader ->
                return gson.fromJson(reader, WxVersionConfig::class.java)
            }
        }
    }

    private fun getModuleContext(): Context {
        val systemContext = getSystemContext()
        return systemContext.createPackageContext(Common.MY_APPLICATION_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
    }

    private fun getSystemContext(): Context {
        val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread")
        return XposedHelpers.callMethod(activityThread, "getSystemContext") as Context
    }

    private fun getModuleApplicationInfo(): ApplicationInfo {
        val systemContext = getSystemContext()
        try {
            @Suppress("DEPRECATION")
            return systemContext.packageManager.getApplicationInfo(Common.MY_APPLICATION_PACKAGE, 0)
        } catch (packageManagerError: Throwable) {
            val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val iPackageManager = XposedHelpers.callStaticMethod(activityThreadClass, "getPackageManager")
            val userHandleClass = XposedHelpers.findClass("android.os.UserHandle", null)
            val userId = XposedHelpers.callStaticMethod(userHandleClass, "myUserId") as Int
            val appInfo = try {
                XposedHelpers.callMethod(
                        iPackageManager,
                        "getApplicationInfo",
                        Common.MY_APPLICATION_PACKAGE,
                        0L,
                        userId
                ) as? ApplicationInfo
            } catch (_: Throwable) {
                XposedHelpers.callMethod(
                        iPackageManager,
                        "getApplicationInfo",
                        Common.MY_APPLICATION_PACKAGE,
                        0,
                        userId
                ) as? ApplicationInfo
            }
            return appInfo ?: throw packageManagerError
        }
    }

    fun getWxConfigFile(fileName: String): String {
        return Common.APP_DIR_PATH + Common.CONFIG_WECHAT_DIR + File.separator + fileName
    }

    fun getConfigFile(fileName: String): String {
        return Common.APP_DIR_PATH + Common.CONFIG_DIR + File.separator + fileName
    }

    fun getViewConfigFile(fileName: String): String {
        return Common.APP_DIR_PATH + Common.CONFIG_VIEW_DIR + File.separator + fileName
    }

    fun getLogFile(date: String): String {
        return Common.APP_DIR_PATH + Common.LOGS_DIR + File.separator + "MDWechat_log_$date.txt"
    }

    fun getTabIcon(index: Int): Bitmap? {
        return getScaleBitmap(getIcon(Common.FILE_NAME_TAB_PREFIX + "$index.png"))
    }

    fun getMaterialTabIcon(index: Int): Bitmap? {
        val bitmap = getCustomizedExternalIcon(Common.FILE_NAME_TAB_PREFIX + "$index.png") ?: return null
        val crop = MaterialTabCustomIconPolicy.cropWindow(bitmap.width, bitmap.height) ?: return null
        val croppedBitmap = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.size, crop.size)
        val outputSize = MaterialTabCustomIconPolicy.outputSizePx(bitmapScale)
        return if (croppedBitmap.width == outputSize && croppedBitmap.height == outputSize) {
            croppedBitmap
        } else {
            Bitmap.createScaledBitmap(croppedBitmap, outputSize, outputSize, true)
        }
    }

    fun getRedPacketBubbleLeftIcon(): Bitmap? {
        return getIcon(Common.CHAT_RED_PACKET_BUBBLE_LEFT_FILENAME)
    }

    fun getUnopenedRedPacketBubbleLeftIcon(): Bitmap? {
        return getIcon(Common.CHAT_UNOPENED_RED_PACKET_BUBBLE_LEFT_FILENAME)
    }

    fun getRedPacketBubbleRightIcon(): Bitmap? {
        return getIcon(Common.CHAT_RED_PACKET_BUBBLE_RIGHT_FILENAME)
    }

    fun getUnopenedRedPacketBubbleRightIcon(): Bitmap? {
        return getIcon(Common.CHAT_UNOPENED_RED_PACKET_BUBBLE_RIGHT_FILENAME)
    }

    fun getBubbleLeftIcon(): Bitmap? {
        return getIcon(Common.CHAT_BUBBLE_LEFT_FILENAME)
    }

    fun getBubbleRightIcon(): Bitmap? {
        return getIcon(Common.CHAT_BUBBLE_RIGHT_FILENAME)
    }

    //自动适配屏幕分辨率
    fun getTabBg(index: Int): Bitmap {
        val bg = getIcon(Common.FILE_NAME_TAB_BG_PREFIX + "$index.png")
        return resizeBg(bg)
    }

    //自动适配屏幕分辨率
    fun getChatBg(): Bitmap {
        var bg = getIcon(Common.FILE_NAME_CHAT_BG)
        if (bg == null) {
            bg = getTabBg(0)
        }
        return resizeBg(bg)
    }

    fun resizeBg(_bg: Bitmap?): Bitmap {
        var bg = _bg
        if (bg == null) bg = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val value_resolution = HookConfig.value_resolution
        if (value_resolution[0] > 0) {
            return BitmapUtil.scaleImage(bg!!, value_resolution[0], value_resolution[1])
        } else {
            return bg!!
        }
    }

    fun getFloatButtonConfig(): FloatButtonConfig? {
        return readBundledOrExternal(
                getViewConfigFile(Common.FILE_NAME_FLOAT_BUTTON),
                "${Common.CONFIG_VIEW_DIR}/${Common.FILE_NAME_FLOAT_BUTTON}") {
            readFloatButtonConfig(it)
        } ?: defaultFloatButtonConfig()
    }

    //保存图片的默认高度
    val picPositionConfig: PicPositionConfig = readPicPositionConfig()

    fun readPicPositionConfig(): PicPositionConfig {
        val path = getViewConfigFile(Common.FILE_NAME_PIC_POSITION)
        var lastModifiedTimeOfSettings: Long = 0
        try {
            lastModifiedTimeOfSettings = File(getConfigFile(Common.MOD_PREFS + ".xml")).lastModified()
            val `is` = FileInputStream(path)
            val json = gson.fromJson(InputStreamReader(`is`), PicPositionConfig::class.java)
            if (json.lastModifiedTimeOfSettings == lastModifiedTimeOfSettings) {
                return json
            }
        } catch (e: Exception) {
        }
        return PicPositionConfig(lastModifiedTimeOfSettings, -1, mutableListOf(
                PicPosition(0, 0),
                PicPosition(0, 0),
                PicPosition(0, 0),
                PicPosition(0, 0)
        ))
    }

    fun writePicPositionConfig() {
        val json = gson.toJson(picPositionConfig) +
                "\n//提示：此文件自动生成，用于保存沉浸背景的图片位置信息。\n" +
                "//Created by JoshCai"
        val op = getViewConfigFile(Common.FILE_NAME_PIC_POSITION)
        val succ = FileIOUtils.writeFileFromString(op, json)
        LogUtil.log("记录图片位置信息至文件:" + succ)
    }

    fun getIconPath(fileName: String): String {
        return Common.APP_DIR_PATH + Common.ICON_DIR + File.separator + fileName
    }

    fun getIcon(fileName: String): Bitmap? {
        val filePath = getIconPath(fileName)
        BitmapFactory.decodeFile(filePath)?.let { return it }
        return openBundledAsset("${Common.ICON_DIR}/$fileName").useQuietly { input ->
            if (input == null) null else BitmapFactory.decodeStream(input)
        }
    }

    private fun getCustomizedExternalIcon(fileName: String): Bitmap? {
        val iconFile = File(getIconPath(fileName))
        val shouldUseExternalIcon = MaterialTabCustomIconPolicy.shouldUseExternalIcon(
            hasExternalIcon = iconFile.isFile,
            matchesBundledIcon = iconFile.isFile && iconFile.matchesBundledIcon(fileName)
        )
        if (!shouldUseExternalIcon) {
            return null
        }
        return BitmapFactory.decodeFile(iconFile.absolutePath)
    }

    private fun File.matchesBundledIcon(fileName: String): Boolean {
        val externalBytes = try {
            readBytes()
        } catch (_: Exception) {
            return false
        }
        val bundledBytes = openBundledAsset("${Common.ICON_DIR}/$fileName").useQuietly { input ->
            input?.readBytes()
        } ?: return false
        return externalBytes.contentEquals(bundledBytes)
    }

    fun getScaleBitmap(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * bitmapScale).toInt(), (bitmap.height * bitmapScale).toInt(), true)
    }

    private fun <T> readBundledOrExternalJson(path: String, bundledAssetPath: String, clazz: Class<T>): T? {
        return readBundledOrExternal(path, bundledAssetPath) { input ->
            InputStreamReader(input).use { reader ->
                gson.fromJson(reader, clazz)
            }
        }
    }

    private fun <T> readBundledOrExternal(path: String, bundledAssetPath: String, readerBlock: (InputStream) -> T?): T? {
        try {
            FileInputStream(path).use { input ->
                return readerBlock(input)
            }
        } catch (_: Exception) {
        }
        return openBundledAsset(bundledAssetPath).useQuietly { input ->
            if (input == null) {
                null
            } else {
                readerBlock(input)
            }
        }
    }

    private fun readFloatButtonConfig(input: InputStream): FloatButtonConfig? {
        InputStreamReader(input).use { reader ->
            val root = gson.fromJson(reader, JsonObject::class.java) ?: return null
            val info = root.get("info")?.asString ?: ""
            val menuObject = root.getAsJsonObject("menu") ?: return null
            val menu = FLoatButtonConfigItem(
                    icon = menuObject.get("icon")?.asString ?: return null
            )
            val itemsArray = root.getAsJsonArray("items") ?: return null
            val items = itemsArray.map { element ->
                val item = element.asJsonObject
                FLoatButtonConfigItem(
                        order = item.get("order")?.asInt ?: 0,
                        type = item.get("type")?.asString ?: "",
                        icon = item.get("icon")?.asString ?: "",
                        text = item.get("text")?.asString ?: ""
                )
            }.toTypedArray()
            return FloatButtonConfig(info, menu, items)
        }
    }

    private fun defaultFloatButtonConfig(): FloatButtonConfig {
        return FloatButtonConfig(
                info = "内置悬浮按钮配置",
                menu = FLoatButtonConfigItem(icon = "ic_add.png"),
                items = arrayOf(
                        FLoatButtonConfigItem(order = 1, type = "com.tencent.mm.ui.contact.SelectContactUI", icon = "ic_chat.png", text = "群聊"),
                        FLoatButtonConfigItem(order = 2, type = "com.tencent.mm.plugin.subapp.ui.pluginapp.AddMoreFriendsUI", icon = "ic_person_add.png", text = "添加好友"),
                        FLoatButtonConfigItem(order = 3, type = "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI", icon = "ic_money.png", text = "收付款"),
                        FLoatButtonConfigItem(order = 4, type = "com.tencent.mm.plugin.scanner.ui.BaseScanUI", icon = "ic_scan.png", text = "扫一扫"),
                        FLoatButtonConfigItem(order = 5, type = "com.tencent.mm.plugin.fts.ui.FTSMainUI", icon = "ic_search.png", text = "搜索"),
                        FLoatButtonConfigItem(order = 6, type = "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI", icon = "ic_friendsgroup.png", text = "朋友圈")
                )
        )
    }

    private fun openBundledAsset(assetPath: String): InputStream? {
        try {
            return getModuleContext().assets.open(assetPath)
        } catch (_: Exception) {
        }
        return openBundledAssetFromApk(assetPath)
    }

    private fun openBundledAssetFromApk(assetPath: String): InputStream? {
        return try {
            val appInfo = getModuleApplicationInfo()
            val zipFile = ZipFile(appInfo.sourceDir)
            val entry = zipFile.getEntry("assets/$assetPath") ?: run {
                zipFile.close()
                return null
            }
            val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
            zipFile.close()
            ByteArrayInputStream(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <T> InputStream?.useQuietly(block: (InputStream?) -> T): T {
        return try {
            block(this)
        } finally {
            CloseUtils.closeIO(this)
        }
    }
}
