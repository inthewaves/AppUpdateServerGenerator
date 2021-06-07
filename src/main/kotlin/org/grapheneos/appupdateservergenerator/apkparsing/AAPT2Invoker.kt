package org.grapheneos.appupdateservergenerator.apkparsing

import org.grapheneos.appupdateservergenerator.model.Density
import org.grapheneos.appupdateservergenerator.util.Invoker
import org.grapheneos.appupdateservergenerator.util.readTextFromErrorStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Wrapper class for the aapt2 tool.
 */
@Deprecated("not needed anymore")
class AAPT2Invoker(aaptPath: Path = Path.of("aapt2")) : Invoker(executablePath = aaptPath) {
    /**
     * Reads the [apkFile] and returns a [Pair] of the version name and the label for the APK using the info extracted
     * from the APK's AndroidManifest via `aapt2 dump badging`.
     *
     * @throws IOException if the APK file can't be processed or parsed by aapt2 / there is missing information
     */
    @Deprecated("use AndroidApk.getIcon instead")
    fun getVersionNameAndLabel(apkFile: File): Pair<String, String> {
        val badgingProcess: Process = ProcessBuilder(
            executablePath.toString(), "dump", "badging", apkFile.absolutePath,
        ).start()

        /*
        Sample first few lines for Auditor:

        $ aapt2 dump badging Auditor-26.apk
        package: name='app.attestation.auditor' versionCode='26' versionName='26' platformBuildVersionName='11' platformBuildVersionCode='30' compileSdkVersion='30' compileSdkVersionCodename='11'
        sdkVersion:'26'
        targetSdkVersion:'30'
        uses-permission: name='android.permission.CAMERA'
        uses-permission: name='android.permission.INTERNET'
        uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'
        uses-permission: name='android.permission.USE_FINGERPRINT' maxSdkVersion='28'
        uses-permission: name='android.permission.USE_BIOMETRIC'
        uses-permission: name='android.permission.QUERY_ALL_PACKAGES'
        application-label:'Auditor'
        application-icon-120:'res/mipmap-anydpi-v21/ic_launcher.xml'
        application-icon-160:'res/mipmap-anydpi-v21/ic_launcher.xml'
        application-icon-240:'res/mipmap-anydpi-v21/ic_launcher.xml'
        application-icon-320:'res/mipmap-anydpi-v21/ic_launcher.xml'
        application-icon-480:'res/mipmap-anydpi-v21/ic_launcher.xml'
        application-icon-640:'res/mipmap-anydpi-v21/ic_launcher.xml'
        application-icon-65534:'res/mipmap-anydpi-v21/ic_launcher.xml'
        application: label='Auditor' icon='res/mipmap-anydpi-v21/ic_launcher.xml'
        launchable-activity: name='app.attestation.auditor.AttestationActivity'  label='Auditor' icon=''
        feature-group: label=''
          uses-feature: name='android.hardware.camera'
          uses-implied-feature: name='android.hardware.camera' reason='requested android.permission.CAMERA permission'
          uses-feature: name='android.hardware.faketouch'
          uses-implied-feature: name='android.hardware.faketouch' reason='default feature for all apps'
        main
        other-activities
        other-services
        supports-screens: 'small' 'normal' 'large' 'xlarge'
        supports-any-density: 'true'
        locales: '--_--'
        densities: '120' '160' '240' '320' '480' '640' '65534'aa
         */
        var versionName: String? = null
        var label: String? = null
        badgingProcess.inputStream.bufferedReader().useLines { lineSequence ->
            lineSequence.forEach { line ->
                when {
                    versionName == null -> {
                        badgingFirstLineRegex.matchEntire(line)
                            ?.let { versionName = it.groupValues[3] }
                    }
                    label == null -> {
                        badgingApplicationLabelLineRegex.matchEntire(line)
                            ?.let { label = it.groupValues[1] }
                    }
                    else -> return@useLines
                }
            }
        }
        if (versionName != null && label != null) return versionName!! to label!!

        badgingProcess.waitFor()
        throw IOException("failed to read versionName and label APK details from aapt2 for $apkFile")
    }

    /**
     * Gets the launcher icon from the [apkFile] as long as the launcher icon's density
     * is >= [minimumDensity]. The saved icon will be the least possible density that is >= [minimumDensity].
     *
     * In the case that the icon from the app's manifest is .xml file (e.g. adaptive icon), it will try to find an
     * equivalent png launcher icon in the app's resources.
     *
     * @return the application icon bytes, or null if unable to find a suitable icon.
     * @throws IOException if an I/O error (or [ZipException]) occurs.
     */
    @Deprecated("use AndroidApk.getIcon instead")
    fun getApplicationIconFromApk(apkFile: File, minimumDensity: Density): ByteArray? {
        val badgingProcess: Process = ProcessBuilder(
            executablePath.toString(), "dump", "badging", apkFile.absolutePath,
        ).start()

        badgingProcess.inputStream.bufferedReader().useLines { lineSequence ->
            lineSequence.forEach { line ->
                badgingApplicationIconLineRegex.matchEntire(line)
                    ?.let { matchResult ->
                        val iconSize = matchResult.groupValues[1].toIntOrNull() ?: return@let
                        if (iconSize >= minimumDensity.approximateDpi) {
                            val path = matchResult.groupValues[2]
                            return extractIconEntryFromApkIntoOutputFile(
                                apkFile,
                                path,
                                minimumDensity
                            )
                        }
                    }
            }
        }
        badgingProcess.waitFor()
        if (badgingProcess.exitValue() != 0) {
            throw IOException("aapt2 dump badging $apkFile failed: ${badgingProcess.readTextFromErrorStream()}")
        }
        return null
    }

    /**
     * Extracts a launcher icon from the [apkFile] using the given zip entry [path] and returns the icon. The extracted
     * icon will be the least possible density that is >= [minimumDensity].
     *
     * In the case that the [path] is an .xml file, it will try to find an equivalent png launcher icon.
     *
     * @return the application icon in bytes, or null if unable to find a suitable icon
     * @throws IOException if an I/O (or [ZipException]) occurs.
     */
    private fun extractIconEntryFromApkIntoOutputFile(
        apkFile: File,
        path: String,
        minimumDensity: Density
    ): ByteArray? {
        ZipFile(apkFile, ZipFile.OPEN_READ).use { zipFile ->
            val pathToUse: String = when {
                path.endsWith(".png") -> path
                path.endsWith(".xml") -> {
                    // The launcher is some adaptive icon. Find the corresponding png if it exists.
                    // Get the base icon name (e.g., res/mipmap-anydpi-v21/ic_launcher.xml is turned to ic_launcher)
                    val iconName = path.split('/').lastOrNull()
                        ?.split('.')?.firstOrNull()
                        ?: return null

                    // Search for a suitable png of the right density. It should have the same name.
                    zipFile.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith("$iconName.png") }
                        .map { it.name to Density.fromPath(it.name) }
                        .filter { it.second >= minimumDensity }
                        .minByOrNull { it.second }
                        ?.first
                        ?: return null
                }
                else -> return null
            }

            zipFile.getEntry(pathToUse)?.let { iconEntry ->
                return zipFile.getInputStream(iconEntry).buffered().use { it.readBytes() }
            }
        }
        return null
    }

    companion object {
        /**
         * A regex to match against the first line of `aapt2 dump badging apkFile`.
         * For [MatchResult.groupValues]:
         * - `groupValues[1]`: The name of the package
         * - `groupValues[2]`: The versionCode of the APK
         * - `groupValues[3]`: The versionName of the APK
         */
        private val badgingFirstLineRegex = Regex("^package: name='(.*?)' versionCode='([0-9]*?)' " +
                "versionName='(.*?)'( platformBuildVersionName='.*?')?( platformBuildVersionCode='[0-9]*?')?" +
                "( compileSdkVersion='[0-9]*?')?( compileSdkVersionCodename='.*?')?$")
        /**
         * A regex to match against the SDK version line of `aapt2 dump badging apkFile`.
         * For [MatchResult.groupValues]:
         * - `groupValues[1]`: The minSdkVersion of the APK
         */
        private val badgingSdkVersionLineRegex = Regex("^sdkVersion:'([0-9]*)'$")
        /**
         * A regex to match against application label line of `aapt2 dump badging apkFile`.
         * For [MatchResult.groupValues]:
         * - `groupValues[1]`: The application label string
         */
        private val badgingApplicationLabelLineRegex = Regex("^application-label:'(.*)'$")
        /**
         * A regex to match against application icon line of `aapt2 dump badging apkFile`.
         * For [MatchResult.groupValues]:
         * - `groupValues[1]`: The density of the icon (see [Density] for possible values)
         * - `groupValues[2]`: The path to the icon in the APK / ZIP.
         */
        private val badgingApplicationIconLineRegex = Regex("^application-icon-([0-9]*):'(.*)'$")
    }
}