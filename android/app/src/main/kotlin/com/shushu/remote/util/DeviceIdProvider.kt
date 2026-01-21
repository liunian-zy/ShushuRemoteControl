package com.shushu.remote.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.util.Xml
import com.shushu.remote.BuildConfig
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File

object DeviceIdProvider {
    private const val TAG = "DeviceIdProvider"
    private const val UNKNOWN = "unknown"
    private const val SSAID_FILE_NAME = "settings_ssaid.xml"
    private const val releasePackage = "com.sqaigc.shushuphoto"
    private const val debugPackage = "com.sqaigc.shushuphoto.debug"

    fun getDeviceId(context: Context): String {
        val ssaid = readSsaidForTargetPackages()
        if (!ssaid.isNullOrBlank()) {
            return ssaid
        }
        return getAndroidId(context) ?: UNKNOWN
    }

    private fun getAndroidId(context: Context): String? {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (androidId.isNullOrBlank() || androidId == UNKNOWN) {
                Log.w(TAG, "Android ID unavailable")
                null
            } else {
                androidId
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to read Android ID", error)
            null
        }
    }

    private fun readSsaidForTargetPackages(): String? {
        val ssaidBytes = readSsaidFileBytes() ?: return null
        for (packageName in getTargetPackages()) {
            val value = parseSsaidValue(ssaidBytes, packageName)
            if (!value.isNullOrBlank()) {
                Log.d(TAG, "Resolved SSAID for $packageName: $value")
                return value
            }
        }
        return null
    }

    private fun getTargetPackages(): List<String> {
        return if (BuildConfig.DEBUG) {
            listOf(debugPackage, releasePackage)
        } else {
            listOf(releasePackage, debugPackage)
        }
    }

    private fun readSsaidFileBytes(): ByteArray? {
        val userId = Process.myUid() / 100000
        val candidates = listOf(
            "/data/system/users/$userId/$SSAID_FILE_NAME",
            "/data/system_ce/$userId/$SSAID_FILE_NAME",
            "/data/system_de/$userId/$SSAID_FILE_NAME",
            "/data/system/users/0/$SSAID_FILE_NAME"
        )

        for (path in candidates.distinct()) {
            val file = File(path)
            readFileBytes(file)?.let {
                Log.d(TAG, "Read SSAID file from $path (direct)")
                return it
            }
            readFileBytesWithSu(path)?.let {
                Log.d(TAG, "Read SSAID file from $path (su)")
                return it
            }
        }

        Log.w(TAG, "SSAid file not readable for userId=$userId")
        return null
    }

    private fun readFileBytes(file: File): ByteArray? {
        return try {
            if (!file.exists()) {
                null
            } else {
                trimToBinaryXml(file.readBytes())
            }
        } catch (error: Exception) {
            Log.w(TAG, "Direct read failed: ${file.path}", error)
            null
        }
    }

    private fun readFileBytesWithSu(path: String): ByteArray? {
        val commands = listOf(
            listOf("su", "-c", "cat $path"),
            listOf("su", "0", "sh", "-c", "cat $path"),
            listOf("su", "0", "cat", path),
            listOf("su", "-c", "toybox cat $path"),
            listOf("su", "-c", "busybox cat $path")
        )

        for (cmd in commands) {
            val output = runSuCommand(cmd)
            if (output != null) {
                return output
            }
        }
        return null
    }

    private fun runSuCommand(command: List<String>): ByteArray? {
        return try {
            val process = ProcessBuilder(command)
                .start()
            val output = process.inputStream.readBytes()
            process.errorStream.close()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.w(TAG, "su failed: exitCode=$exitCode cmd=${command.joinToString(" ")}")
                null
            } else {
                trimToBinaryXml(output)
            }
        } catch (error: Exception) {
            Log.w(TAG, "su read failed: cmd=${command.joinToString(" ")}", error)
            null
        }
    }

    private fun parseSsaidValue(xmlBytes: ByteArray, packageName: String): String? {
        val abxValue = parseWithBinaryPullParser(xmlBytes, packageName)
        if (!abxValue.isNullOrBlank()) {
            return abxValue
        }
        if (looksLikeBinaryXml(xmlBytes)) {
            val binaryValue = parseWithXmlBlock(xmlBytes, packageName)
            if (!binaryValue.isNullOrBlank()) {
                return binaryValue
            }
        }
        if (looksLikeTextXml(xmlBytes)) {
            val textValue = parseWithTextParser(xmlBytes, packageName)
            if (!textValue.isNullOrBlank()) {
                return textValue
            }
        }
        return null
    }

    private fun parseWithTextParser(xmlBytes: ByteArray, packageName: String): String? {
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            val input = ByteArrayInputStream(xmlBytes)
            parser.setInput(input, null)
            try {
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "setting") {
                        val name = parser.getAttributeValue(null, "name")
                        val pkg = parser.getAttributeValue(null, "package")
                        if (name == packageName || pkg == packageName) {
                            return parser.getAttributeValue(null, "value")
                        }
                    }
                    event = parser.next()
                }
            } finally {
                input.close()
            }
            null
        } catch (error: Exception) {
            Log.w(TAG, "Primary XML parser failed", error)
            null
        }
    }

    private fun looksLikeTextXml(xmlBytes: ByteArray): Boolean {
        for (byte in xmlBytes) {
            val ch = byte.toInt().toChar()
            if (!ch.isWhitespace()) {
                return ch == '<'
            }
        }
        return false
    }
    
    private fun looksLikeBinaryXml(xmlBytes: ByteArray): Boolean {
        if (xmlBytes.size < 4) {
            return false
        }
        return xmlBytes[0] == 0x03.toByte() &&
            xmlBytes[1] == 0x00.toByte() &&
            xmlBytes[2] == 0x08.toByte() &&
            xmlBytes[3] == 0x00.toByte()
    }

    @SuppressLint("PrivateApi", "BlockedPrivateApi")
    private fun parseWithXmlBlock(xmlBytes: ByteArray, packageName: String): String? {
        return try {
            val xmlBlockClass = Class.forName("android.content.res.XmlBlock")
            val ctor = xmlBlockClass.getDeclaredConstructor(ByteArray::class.java)
            ctor.isAccessible = true
            val xmlBlock = ctor.newInstance(xmlBytes)
            val newParserMethod = xmlBlockClass.getDeclaredMethod("newParser")
            val parser = newParserMethod.invoke(xmlBlock) as XmlPullParser

            try {
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "setting") {
                        val name = parser.getAttributeValue(null, "name")
                        val pkg = parser.getAttributeValue(null, "package")
                        if (name == packageName || pkg == packageName) {
                            return parser.getAttributeValue(null, "value")
                        }
                    }
                    event = parser.next()
                }
            } finally {
                try {
                    val closeMethod = xmlBlockClass.getDeclaredMethod("close")
                    closeMethod.isAccessible = true
                    closeMethod.invoke(xmlBlock)
                } catch (_: Exception) {
                    // ignore
                }
            }
            null
        } catch (error: Exception) {
            Log.w(TAG, "XmlBlock parser failed", error)
            null
        }
    }

    @SuppressLint("PrivateApi", "BlockedPrivateApi")
    private fun parseWithBinaryPullParser(xmlBytes: ByteArray, packageName: String): String? {
        if (!looksLikeAbxXml(xmlBytes)) {
            return null
        }
        val parser = newBinaryXmlParser() ?: return null
        val input = ByteArrayInputStream(xmlBytes)
        try {
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "setting") {
                    val name = parser.getAttributeValue(null, "name")
                    val pkg = parser.getAttributeValue(null, "package")
                    if (name == packageName || pkg == packageName) {
                        return parser.getAttributeValue(null, "value")
                    }
                }
                event = parser.next()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Binary XML parser failed", error)
        } finally {
            input.close()
        }
        return null
    }

    @SuppressLint("PrivateApi", "BlockedPrivateApi")
    private fun newBinaryXmlParser(): XmlPullParser? {
        return try {
            val method = Xml::class.java.getDeclaredMethod("newBinaryPullParser")
            method.isAccessible = true
            method.invoke(null) as XmlPullParser
        } catch (error: Exception) {
            Log.w(TAG, "Binary XML parser unavailable", error)
            null
        }
    }

    private fun looksLikeAbxXml(xmlBytes: ByteArray): Boolean {
        if (xmlBytes.size < 4) {
            return false
        }
        return xmlBytes[0] == 'A'.code.toByte() &&
            xmlBytes[1] == 'B'.code.toByte() &&
            xmlBytes[2] == 'X'.code.toByte()
    }

    private fun trimToBinaryXml(bytes: ByteArray): ByteArray {
        val abxHeader = byteArrayOf('A'.code.toByte(), 'B'.code.toByte(), 'X'.code.toByte())
        val axmlHeader = byteArrayOf(0x03, 0x00, 0x08, 0x00)
        val abxIndex = bytes.indexOfSubArray(abxHeader)
        val index = if (abxIndex >= 0) abxIndex else bytes.indexOfSubArray(axmlHeader)
        return if (index <= 0) {
            bytes
        } else {
            Log.w(TAG, "Trimming SSAID bytes prefix: offset=$index")
            bytes.copyOfRange(index, bytes.size)
        }
    }

    private fun ByteArray.indexOfSubArray(target: ByteArray): Int {
        if (target.isEmpty() || this.size < target.size) return -1
        outer@ for (i in 0..(this.size - target.size)) {
            for (j in target.indices) {
                if (this[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
