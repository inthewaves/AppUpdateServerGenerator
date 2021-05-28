package org.grapheneos.appupdateservergenerator.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.TreeSet

/**
 * Derived from [kotlinx.serialization.internal.ListLikeSerializer] and
 * [kotlinx.serialization.internal.AbstractCollectionSerializer] from
 */
class TreeSetSerializer<T>(
    private val elementSerializer: KSerializer<T>
) : KSerializer<TreeSet<T>> {
    override val descriptor: SerialDescriptor = TreeSetDescriptor(elementSerializer.descriptor)

    private fun readAll(decoder: CompositeDecoder, builder: TreeSet<T>, startIndex: Int, size: Int) {
        require(size >= 0) { "Size must be known in advance when using READ_ALL" }
        for (index in 0 until size)
            readElement(decoder, startIndex + index, builder)
    }

    private fun readElement(decoder: CompositeDecoder, index: Int, builder: TreeSet<T>) {
        builder.add(decoder.decodeSerializableElement(descriptor, index, elementSerializer))
    }

    private fun merge(decoder: Decoder, previous: TreeSet<T>?): TreeSet<T> {
        val builder = previous ?: TreeSet()
        val startIndex = builder.size
        val compositeDecoder = decoder.beginStructure(descriptor)
        if (compositeDecoder.decodeSequentially()) {
            readAll(compositeDecoder, builder, startIndex, readSize(compositeDecoder))
        } else {
            while (true) {
                val index = compositeDecoder.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                readElement(compositeDecoder, startIndex + index, builder)
            }
        }
        compositeDecoder.endStructure(descriptor)
        return builder
    }

    private fun readSize(decoder: CompositeDecoder): Int {
        return decoder.decodeCollectionSize(descriptor)
    }

    override fun deserialize(decoder: Decoder): TreeSet<T> = merge(decoder, null)

    override fun serialize(encoder: Encoder, value: TreeSet<T>) {
        val size = value.size
        val composite = encoder.beginCollection(descriptor, size)
        val iterator = value.iterator()
        for (index in 0 until size)
            composite.encodeSerializableElement(descriptor, index, elementSerializer, iterator.next())
        composite.endStructure(descriptor)
    }
}
