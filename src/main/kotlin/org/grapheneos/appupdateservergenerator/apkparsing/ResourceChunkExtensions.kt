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

typealias BinaryResAndConfigPredicate =
        (ResourceTableChunk.(resValue: BinaryResourceValue, config: BinaryResourceConfiguration) -> Boolean)

class BinaryResAndConfig(val value: BinaryResourceValue, val config: BinaryResourceConfiguration)

/**
 * Retrieves a resource assigned to the specified resource id if one exists.
 *
 * A single resource may have multiple configurations. This function will attempt to find the
 * resource that best matches against the specified [match] configuration. If a [match] is not provided,
 * [BinaryResourceConfigBuilder.createDummyConfig] will be used.
 *
 * An optional [extraFilter] can be specified to further filter the results. This [extraFilter] should be careful if
 * expects the given `resValue` to be of a certain type (like expecting a [BinaryResourceValue.Type.STRING], because
 * the value could be a [BinaryResourceValue.Type.REFERENCE].
 *
 * [Reference code from aapt2](https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#213)
 * [BestConfigValue reference](https://android.googlesource.com/platform/frameworks/base/+/c6c226327debf1f3fcbd71e2bbee792118364ee5/tools/aapt2/dump/DumpManifest.cpp#157)
 */
fun ResourceTableChunk.findValueById(
    resId: BinaryResourceIdentifier,
    match: BinaryResourceConfiguration = BinaryResourceConfigBuilder.createDummyConfig().toBinaryResConfig(),
    extraFilter: BinaryResAndConfigPredicate? = null
): BinaryResAndConfig? = packages.find { it.id == resId.packageId() }
    ?.getTypeChunks(resId.typeId())
    ?.asSequence()
    ?.filter {
        // TODO: Support complex values properly? Although not really important, since this is only used for icon and
        //  app label resolution at the moment.
        // TODO: Look into https://cs.android.com/android/platform/superproject/+/master:frameworks/base/tools/aapt2/ResourceUtils.cpp;drc=master;l=736
        //  to be able to distinguish between reference types.
        it.containsResource(resId) && it.entries[resId.entryId()]?.value() != null
    }
    ?.filterNotNull()
    ?.fold(initial = null as TypeChunk?) { bestValue: TypeChunk?, currentChunk: TypeChunk ->
        // currentEntry must be not null because it passed the filter above, which checks if
        // the resource is in the entries map.
        val currentEntry = currentChunk.entries[resId.entryId()]!!
        val currentConfig = currentChunk.configuration
        if (!currentConfig.match(match)) {
            return@fold bestValue // continue
        }

        if (bestValue != null) {
            if (!currentConfig.isBetterThan(bestValue.configuration, match)) {
                // note: BinaryResourceConfiguration already defines an equals method
                if (currentConfig != bestValue.configuration) {
                    return@fold bestValue // continue
                }
            }
        }

        if (extraFilter != null && !extraFilter(currentEntry.value()!!, currentConfig)) {
            return@fold bestValue // continue
        }

        // The new best value for this iteration
        return@fold currentChunk
    }
    ?.let {
        BinaryResAndConfig(it.entries[resId.entryId()]!!.value(), it.configuration)
    }

/**
 * Attempts to resolve the reference to a non-reference value. The [config] is used to filter out
 * configurations. If a [config] is not specific, [BinaryResourceConfigBuilder.createDummyConfig] will be used.
 *
 * The [extraConfigFilter] can be used to further
 *
 * [Reference code from aapt2](https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#213)
 */
fun ResourceTableChunk.resolveReference(
    resId: BinaryResourceIdentifier,
    config: BinaryResourceConfiguration = BinaryResourceConfigBuilder.createDummyConfig().toBinaryResConfig(),
    extraConfigFilter: BinaryResAndConfigPredicate? = null
): BinaryResAndConfig? {
    var currentResId = resId
    for (i in 0 until MAX_REFERENCE_RESOLVE_ITERATIONS) {
        val resAndConfig: BinaryResAndConfig? = findValueById(currentResId, config, extraConfigFilter)
        if (resAndConfig?.value?.type() == BinaryResourceValue.Type.REFERENCE) {
            currentResId = BinaryResourceIdentifier.create(resAndConfig.value.data())
        } else {
            return resAndConfig
        }
    }
    return null
}

fun ResourceTableChunk.resolveString(resId: BinaryResourceIdentifier): String? {
    val valueToUse: BinaryResourceValue = resolveReference(resId)?.value ?: return null
    if (valueToUse.type() != BinaryResourceValue.Type.STRING) return null
    return stringPool.getString(valueToUse.data())
}
