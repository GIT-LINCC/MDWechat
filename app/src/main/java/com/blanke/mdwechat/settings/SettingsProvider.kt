package com.blanke.mdwechat.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.preference.PreferenceManager
import com.blanke.mdwechat.Common
import com.blanke.mdwechat.util.SharedPreferencesFile
import com.lincc.mdwechat.R
import java.io.File
import java.io.FileNotFoundException

class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "text/xml"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.contains("w", ignoreCase = true)) {
            throw FileNotFoundException("Read-only provider")
        }
        val context = context ?: throw FileNotFoundException("Provider context unavailable")
        val expectedName = Common.MOD_PREFS + ".xml"
        if (uri.lastPathSegment != expectedName) {
            throw FileNotFoundException(uri.toString())
        }
        PreferenceManager.setDefaultValues(
            context,
            Common.MOD_PREFS,
            Context.MODE_PRIVATE,
            R.xml.pref_settings,
            false
        )
        val prefs = context.getSharedPreferences(Common.MOD_PREFS, Context.MODE_PRIVATE)
        val output = File(context.cacheDir, expectedName)
        SharedPreferencesFile.write(prefs, output)
        return ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
