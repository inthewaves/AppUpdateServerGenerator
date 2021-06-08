package org.grapheneos.appupdateservergenerator.apkparsing

import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceConfiguration
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceIdentifier
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceValue
import com.google.devrel.gmscore.tools.apk.arsc.ResourceTableChunk
import com.google.devrel.gmscore.tools.apk.arsc.TypeChunk
import org.grapheneos.appupdateservergenerator.apkparsing.androidfw.isBetterThan
import org.grapheneos.appupdateservergenerator.apkparsing.androidfw.match

/** See https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#213 */
private const val MAX_REFERENCE_RESOLVE_ITERATIONS = 40

/**
 * Retrieves a resource assigned to the specified resource id if one exists.
 *
 * A single resource may have multiple configurations. This function will attempt to find the
 * resource that best matches against the specified [match] configuration. If a [match] is not provided,
 * [BinaryResourceConfigBuilder.createDummyConfig] will be used.
 *
 * [Reference code from aapt2](https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#213)
 * [BestConfigValue reference](https://android.googlesource.com/platform/frameworks/base/+/c6c226327debf1f3fcbd71e2bbee792118364ee5/tools/aapt2/dump/DumpManifest.cpp#157)
 */
fun ResourceTableChunk.findValueById(
    resId: BinaryResourceIdentifier,
    match: BinaryResourceConfiguration = BinaryResourceConfigBuilder.createDummyConfig().toBinaryResConfig()
): BinaryResourceValue? = packages.find { it.id == resId.packageId() }
    ?.getTypeChunks(resId.typeId())
    ?.asSequence()
    ?.filter { it.containsResource(resId) }
    ?.filterNotNull()
    ?.fold(initial = null as TypeChunk?) { bestValue: TypeChunk?, currentChunk: TypeChunk ->
        val entry = currentChunk.entries[resId.entryId()]!!
        val valueConfig = currentChunk.configuration

        if (entry.isComplex || entry.value() == null) return@fold bestValue
        if (!valueConfig.match(match)) {
            return@fold bestValue // continue
        }

        if (bestValue != null) {
            if (!valueConfig.isBetterThan(bestValue.configuration, match)) {
                // note: BinaryResourceConfiguration already defines an equals method
                if (valueConfig != bestValue.configuration) {
                    return@fold bestValue // continue
                }
            }
        }

        // The new best value for this iteration
        return@fold currentChunk
    }
    ?.entries
    ?.get(resId.entryId())
    ?.value()

/**
 * Attempts to resolve the reference to a non-reference value. The [config] is used to filter out
 * configurations. If a [config] is not specific, [BinaryResourceConfigBuilder.createDummyConfig] will be used.
 *
 * [Reference code from aapt2](https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#213)
 */
fun ResourceTableChunk.resolveReference(
    resId: BinaryResourceIdentifier,
    config: BinaryResourceConfiguration = BinaryResourceConfigBuilder.createDummyConfig().toBinaryResConfig()
): BinaryResourceValue? {
    var currentResId = resId
    for (i in 0 until MAX_REFERENCE_RESOLVE_ITERATIONS) {
        val value: BinaryResourceValue? = findValueById(currentResId, config)
        if (value?.type() == BinaryResourceValue.Type.REFERENCE) {
            currentResId = BinaryResourceIdentifier.create(value.data())
        } else {
            return value
        }
    }
    return null
}

fun ResourceTableChunk.resolveString(resId: BinaryResourceIdentifier): String? {
    val valueToUse: BinaryResourceValue = resolveReference(resId) ?: return null
    if (valueToUse.type() != BinaryResourceValue.Type.STRING) return null
    return stringPool.getString(valueToUse.data())
}
