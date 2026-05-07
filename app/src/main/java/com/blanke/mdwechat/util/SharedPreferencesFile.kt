package com.blanke.mdwechat.util

import android.content.SharedPreferences
import android.util.Xml
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object SharedPreferencesFile {
    fun write(sharedPreferences: SharedPreferences, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { output ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(output, "utf-8")
            serializer.startDocument("utf-8", true)
            serializer.startTag(null, "map")
            sharedPreferences.all.toSortedMap().forEach { (key, value) ->
                when (value) {
                    is Boolean -> writeValue(serializer, "boolean", key, value.toString())
                    is Int -> writeValue(serializer, "int", key, value.toString())
                    is Long -> writeValue(serializer, "long", key, value.toString())
                    is Float -> writeValue(serializer, "float", key, value.toString())
                    is String -> {
                        serializer.startTag(null, "string")
                        serializer.attribute(null, "name", key)
                        serializer.text(value)
                        serializer.endTag(null, "string")
                    }
                    is Set<*> -> {
                        serializer.startTag(null, "set")
                        serializer.attribute(null, "name", key)
                        value.filterIsInstance<String>().sorted().forEach {
                            serializer.startTag(null, "string")
                            serializer.text(it)
                            serializer.endTag(null, "string")
                        }
                        serializer.endTag(null, "set")
                    }
                }
            }
            serializer.endTag(null, "map")
            serializer.endDocument()
        }
        val moved = temp.renameTo(target)
        if (!moved) {
            FileInputStream(temp).use { input ->
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output)
                }
            }
            temp.delete()
        }
    }

    private fun writeValue(serializer: org.xmlpull.v1.XmlSerializer, tag: String, key: String, value: String) {
        serializer.startTag(null, tag)
        serializer.attribute(null, "name", key)
        serializer.attribute(null, "value", value)
        serializer.endTag(null, tag)
    }
}
