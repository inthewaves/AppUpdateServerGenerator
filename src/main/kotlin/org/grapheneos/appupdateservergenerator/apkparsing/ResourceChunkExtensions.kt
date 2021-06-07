package org.grapheneos.appupdateservergenerator.apkparsing

import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceConfiguration
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceIdentifier
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceValue
import com.google.devrel.gmscore.tools.apk.arsc.ResourceTableChunk
import com.google.devrel.gmscore.tools.apk.arsc.TypeChunk

/** See https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#213 */
private const val MAX_REFERENCE_RESOLVE_ITERATIONS = 40

/** Links a [TypeChunk.Entry] with its [BinaryResourceConfiguration]. */
data class ChunkEntryAndConfig(val chunkEntry: TypeChunk.Entry, val config: BinaryResourceConfiguration)

/**
 * Retrieves the resources assigned to the specified resource id if one exists.
 * A single resource may have multiple configurations, hence the return type is a sequence.
 *
 * https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#213
 */
fun ResourceTableChunk.findValuesByIdAsSequence(
    resId: BinaryResourceIdentifier,
    configPredicate: ((BinaryResourceConfiguration) -> Boolean)?
): Sequence<ChunkEntryAndConfig>? = packages.find { it.id == resId.packageId() }
    ?.getTypeChunks(resId.typeId())
    ?.asSequence()
    // maybe we should also copy in BestConfigValue from aapt2?
    // https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#213
    // https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/libs/androidfw/ResourceTypes.cpp#2780
    // https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/libs/androidfw/ResourceTypes.cpp#2517
    ?.filter { it.containsResource(resId) && (configPredicate?.invoke(it.configuration) ?: true) }
    ?.map { chunk -> chunk.entries[resId.entryId()]?.let { ChunkEntryAndConfig(it, chunk.configuration) } }
    ?.filterNotNull()

/**
 * Attempts to resolve the reference to a non-reference value. The [configPredicate] is used to filter out
 * configurations
 *
 * Reference: https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#213
 */
fun ResourceTableChunk.resolveReference(
    resId: BinaryResourceIdentifier,
    configPredicate: ((BinaryResourceConfiguration) -> Boolean)?,
    sequenceTransformer: (Sequence<ChunkEntryAndConfig>) -> BinaryResourceValue?
): BinaryResourceValue? {
    var currentResId = resId
    for (i in 0 until MAX_REFERENCE_RESOLVE_ITERATIONS) {
        val value: BinaryResourceValue? = findValuesByIdAsSequence(currentResId, configPredicate)
            ?.run(sequenceTransformer)
        if (value?.type() == BinaryResourceValue.Type.REFERENCE) {
            currentResId = BinaryResourceIdentifier.create(value.data())
        } else {
            return value
        }
    }
    return null
}

fun ResourceTableChunk.resolveString(resId: BinaryResourceIdentifier): String? {
    val valueToUse: BinaryResourceValue = resolveReference(
        resId = resId,
        configPredicate = { it.isDefault },
        sequenceTransformer = { it.first().chunkEntry.value() }
    ) ?: return null

    if (valueToUse.type() != BinaryResourceValue.Type.STRING) return null
    return stringPool.getString(valueToUse.data())
}