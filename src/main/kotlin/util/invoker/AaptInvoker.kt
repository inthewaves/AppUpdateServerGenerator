package util.invoker

import model.AndroidApk
import model.VersionCode
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * Wrapper class for the aapt tool.
 */
class AaptInvoker(val aaptPath: Path = Path.of("aapt")) : Invoker(aaptPath) {
    /**
     * Reads the [apkFile] and populates the given [androidApkBuilder] from AndroidManifest.xml.
     * @throws IOException if the APK file can't be processed by aapt
     */
    @Throws(IOException::class)
    fun getAndroidAppDetails(apkFile: File, androidApkBuilder: AndroidApk.Builder) {
        val manifestProcess: Process = ProcessBuilder().run {
            command(aaptPath.toString(), "dump", "xmltree", apkFile.absolutePath, "AndroidManifest.xml")
            start()
        }

        manifestProcess.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                androidApkBuilder.apply {
                    versionCodeRegex.find(line)
                        ?.let { versionCode = VersionCode(it.groupValues[1].toInt(radix = 16)) }
                        ?: versionNameRegex.find(line)
                            ?.let { versionName = it.groupValues[1] }
                        ?: packageRegex.find(line)
                            ?.let { packageName = it.groupValues[1] }
                        ?: minSdkVersionRegex.find(line)
                            ?.let { minSdkVersion = it.groupValues[1].toInt(radix = 16) }

                    if (packageName != null && versionCode != null && versionName != null && minSdkVersion != null) {
                        return
                    }
                }
                line = reader.readLine()
            }
        }
        manifestProcess.waitFor()
        throw IOException("failed to read APK details from aapt")
    }

    companion object {
        private val versionCodeRegex = Regex("""A: android:versionCode\(0x[0-9a-f]*\)=\(type 0x10\)0x([a-e0-9]*)""")
        private val versionNameRegex = Regex("""A: android:versionName\(0x[0-9a-f]*\)="(.*)" \(Raw: ".*"\)""")
        private val packageRegex = Regex(""" A: package="(.*)" \(Raw: ".*"\)""")
        private val minSdkVersionRegex = Regex("""A: android:minSdkVersion\(0x[0-9a-f]*\)=\(type 0x10\)0x([a-e0-9]*)""")
    }
}