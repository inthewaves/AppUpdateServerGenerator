package util.invoker

import model.AndroidApk
import model.VersionCode
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * Wrapper class for the aapt tool.
 */
class AAPT2Invoker(aaptPath: Path = Path.of("aapt2")) : Invoker(executablePath = aaptPath) {
    /**
     * Reads the [apkFile] and populates the given [androidApkBuilder] using the values in the APK's AndroidManifest.xml
     * @throws IOException if the APK file can't be processed by aapt
     */
    @Throws(IOException::class)
    fun getAndroidAppDetails(apkFile: File, androidApkBuilder: AndroidApk.Builder) {
        val manifestProcess: Process = ProcessBuilder(
            executablePath.toString(), "dump", "xmltree", "--file", "AndroidManifest.xml", apkFile.absolutePath,
        ).start()

        /*
        Sample first few lines for Auditor:

        $ aapt dump xmltree Auditor-26.apk AndroidManifest.xml
        N: android=http://schemas.android.com/apk/res/android
          E: manifest (line=2)
            A: android:versionCode(0x0101021b)=(type 0x10)0x1a
            A: android:versionName(0x0101021c)="26" (Raw: "26")
            A: android:compileSdkVersion(0x01010572)=(type 0x10)0x1e
            A: android:compileSdkVersionCodename(0x01010573)="11" (Raw: "11")
            A: package="app.attestation.auditor" (Raw: "app.attestation.auditor")
            A: platformBuildVersionCode=(type 0x10)0x1e
            A: platformBuildVersionName=(type 0x10)0xb
            E: uses-sdk (line=7)
              A: android:minSdkVersion(0x0101020c)=(type 0x10)0x1a
              A: android:targetSdkVersion(0x01010270)=(type 0x10)0x1e
         */
        manifestProcess.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                androidApkBuilder.apply {
                    versionCodeRegex.find(line)
                        ?.let {
                            versionCode = try {
                                VersionCode(it.groupValues[2].toInt())
                            } catch (e: NumberFormatException) {
                                throw IOException("failed to parse versionCode for $apkFile")
                            }
                        }
                        ?: versionNameRegex.find(line)
                            ?.let { versionName = it.groupValues[2] }
                        ?: packageRegex.find(line)
                            ?.let { packageName = it.groupValues[1] }
                        ?: minSdkVersionRegex.find(line)
                            ?.let {
                                minSdkVersion = try {
                                    it.groupValues[2].toInt()
                                } catch (e: NumberFormatException) {
                                    throw IOException("failed to parse minSdkVersion for $apkFile")
                                }
                            }

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
        private val versionCodeRegex = Regex("""A: (http://schemas.android.com/apk/res/)?android:versionCode\(0x[0-9a-f]*\)=([0-9]*)""")
        private val versionNameRegex = Regex("""A: (http://schemas.android.com/apk/res/)?android:versionName\(0x[0-9a-f]*\)="(.*)" \(Raw: ".*"\)""")
        private val packageRegex = Regex(""" A: package="(.*)" \(Raw: ".*"\)""")
        private val minSdkVersionRegex = Regex("""A: (http://schemas.android.com/apk/res/)?android:minSdkVersion\(0x[0-9a-f]*\)=([a-e0-9]*)""")
    }
}