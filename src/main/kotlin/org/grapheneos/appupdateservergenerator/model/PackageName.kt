package org.grapheneos.appupdateservergenerator.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class PackageName(val pkg: String): Comparable<PackageName> {
    init {
        // require(packageRegex.matchEntire(pkg) != null)
    }

    override fun compareTo(other: PackageName): Int = pkg.compareTo(other.pkg)

    override fun toString() = pkg

    companion object {
        /**
         * Matches against a full Java-language-style package name for the Android app. The name may contain uppercase
         * or lowercase letters ('A' through 'Z'), numbers, and underscores ('_'). However, individual package name
         * parts may only start with letters. [1]
         *
         * [1] https://developer.android.com/guide/topics/manifest/manifest-element#package
         *
         * Or see
         * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/tools/aapt2/util/Util.cpp;drc=master;l=147?q=package&ss=android%2Fplatform%2Fsuperproject:frameworks%2Fbase%2Ftools%2Faapt2%2F&start=41
         */
        val packageRegex = Regex("""^[a-zA-Z][a-zA-Z0-9]*(\.[a-zA-Z][a-zA-Z0-9]*)+$""")
    }
}