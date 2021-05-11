package org.grapheneos.appupdateservergenerator.apkparsing

import org.grapheneos.appupdateservergenerator.model.AndroidApk
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.util.Invoker
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * Wrapper class for the aapt2 tool.
 */
class AAPT2Invoker(aaptPath: Path = Path.of("aapt2")) : Invoker(executablePath = aaptPath) {
    /**
     * Reads the [apkFile] and populates the given [androidApkBuilder] using the values in the APK's AndroidManifest.xml
     * @throws IOException if the APK file can't be processed by aapt
     */
    @Throws(IOException::class)
    fun getAndroidAppDetails(apkFile: File, androidApkBuilder: AndroidApk.Builder) {
        val manifestProcess: Process = ProcessBuilder(
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
        densities: '120' '160' '240' '320' '480' '640' '65534'
         */
        manifestProcess.inputStream.bufferedReader().useLines { lineSequence ->
            lineSequence.forEach { line ->
                androidApkBuilder.apply {
                    if (packageName != null && versionCode != null && versionName != null && minSdkVersion != null) {
                        return
                    }

                    when {
                        packageName == null -> {
                            badgingFirstLineRegex.find(line)
                                ?.let {
                                    packageName = it.groupValues[1]

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
                        else -> {
                            badgingApplicationLabelLineRegex.matchEntire(line)
                                ?.let {

                                }
                        }
                    }
                }
            }
        }

        manifestProcess.waitFor()
        throw IOException("failed to read APK details from aapt; the builder is $androidApkBuilder")
    }

    companion object {
        private val badgingFirstLineRegex = Regex("^package: name='(.*)' versionCode='([0-9]*)' versionName='(.*)' platformBuildVersionName='(.*)' platformBuildVersionCode='[0-9]*' compileSdkVersion='[0-9]*' compileSdkVersionCodename='.*'$")
        private val badgingSdkVersionLineRegex = Regex("^sdkVersion:'([0-9]*)'$")
        private val badgingApplicationLabelLineRegex = Regex("^application-label:'(.*)'$")
    }
}