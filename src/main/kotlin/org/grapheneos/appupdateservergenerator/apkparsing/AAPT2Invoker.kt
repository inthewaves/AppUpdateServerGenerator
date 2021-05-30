package org.grapheneos.appupdateservergenerator.apkparsing

import org.grapheneos.appupdateservergenerator.model.AndroidApk
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.VersionCode
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
class AAPT2Invoker(aaptPath: Path = Path.of("aapt2")) : Invoker(executablePath = aaptPath) {
    /**
     * Reads the [apkFile] and populates the given [androidApkBuilder] using the info extracted from the APK's manifest
     * via `aapt2 dump badging`.
     *
     * @throws IOException if the APK file can't be processed by aapt2 / there is missing information
     */
    fun getAndroidAppDetails(
        apkFile: File,
        androidApkBuilder: AndroidApk.Builder
    ) {
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
        badgingProcess.inputStream.bufferedReader().useLines { lineSequence ->
            lineSequence.forEach { line ->
                androidApkBuilder.apply {
                    when {
                        packageName == null || versionCode == null || versionName == null -> {
                            badgingFirstLineRegex.matchEntire(line)
                                ?.let {
                                    packageName = PackageName(it.groupValues[1])

                                    versionCode = try {
                                        VersionCode(it.groupValues[2].toInt())
                                    } catch (e: NumberFormatException) {
                                        throw IOException("failed to parse versionCode for $apkFile", e)
                                    }

                                    versionName = it.groupValues[3]
                                }
                        }
                        minSdkVersion == null -> {
                            badgingSdkVersionLineRegex.matchEntire(line)
                                ?.let {
                                    minSdkVersion = try {
                                        it.groupValues[1].toInt()
                                    } catch (e: NumberFormatException) {
                                        throw IOException("failed to parse minSdkVersion for $apkFile", e)
                                    }
                                }
                        }
                        label == null -> {
                            badgingApplicationLabelLineRegex.matchEntire(line)
                                ?.let { label = it.groupValues[1] }
                        }
                        else -> return
                    }
                }
            }
        }

        badgingProcess.waitFor()
        throw IOException("failed to read APK details from aapt2; the builder is $androidApkBuilder")
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
                        .map { it.name to Density.fromPathToDensity(it.name) }
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

    /**
     * Represents the screen pixel density in dpi. The [approximateDpi] values are from [1] and [2].
     *
     * [1] https://android.googlesource.com/platform/frameworks/native/+/7e563f090ba19c36b9879e14388a0e377f1523b5/include/android/configuration.h#92
     * [2] https://developer.android.com/guide/topics/resources/providing-resources#DensityQualifier
     */
    @Suppress("unused")
    sealed class Density(val qualifierValue: String, val approximateDpi: Int) : Comparable<Density> {
        object DEFAULT : Density("*", 0)

        /** Low-density screens; approximately 120dpi.*/
        object LOW : Density("ldpi", 120)
        /** Medium-density (on traditional HVGA) screens; approximately 160dpi.*/
        object MEDIUM : Density("mdpi",160)
        /**
         * Screens somewhere between mdpi and hdpi; approximately 213dpi. This isn't considered a "primary" density
         * group. It is mostly intended for televisions and most apps shouldn't need it—providing mdpi and hdpi
         * resources is sufficient for most apps and the system scales them as appropriate.
         */
        object TV : Density("tvdpi", 213)
        /** High-density screens; approximately 240dpi.*/
        object HIGH : Density("hdpi", 240)
        /** Extra-high-density screens; approximately 320dpi.*/
        object XHIGH : Density("xhdpi", 320)
        /** Extra-extra-high-density screens; approximately 480dpi.*/
        object XXHIGH : Density("xxhdpi", 480)
        /**
         * Extra-extra-extra-high-density uses (launcher icon only, see the note in Supporting Multiple Screens);
         * approximately 640dpi.
         *
         * https://developer.android.com/guide/practices/screens_support#xxxhdpi-note
         */
        object XXXHIGH : Density("xxxhdpi", 640)

        /**
         * This qualifier matches all screen densities and takes precedence over other qualifiers. This is useful for
         * vector drawables.
         */
        object ANY : Density("anydpi", 0xfffe)
        override fun compareTo(other: Density): Int = approximateDpi.compareTo(other.approximateDpi)

        override fun toString(): String {
            return "Density(qualifierValue='$qualifierValue', approximateDpi=$approximateDpi)"
        }

        companion object {
            private val regex = Regex("-(l|m|tv|x{0,3}h|any)dpi")

            /**
             * Maps [Density.qualifierValue] to [Density]. Lazy init to get around issues with this being created before
             * the objects are initialized.
             */
            private val qualifierToDensityMap: Map<String?, Density> by lazy {
                Density::class.sealedSubclasses.associate {
                    val objectInstance: Density = it.objectInstance
                        ?: error("impossible---every sealed subclass should be an object")
                    Density::qualifierValue.get(objectInstance) to objectInstance
                }
            }

            /**
             * Parses a path and returns a [Density], or [Density.DEFAULT] if it doesn't correspond to any instance.
             * Examples of paths: res/drawable-mdpi-v4/notification_bg_normal.9.png corresponds to [Density.MEDIUM].
             */
            fun fromPathToDensity(path: String): Density =
                regex.find(path)
                    ?.groupValues?.get(1)
                    ?.let { dpiPrefix -> qualifierToDensityMap[dpiPrefix + "dpi"] }
                    ?: DEFAULT
        }
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