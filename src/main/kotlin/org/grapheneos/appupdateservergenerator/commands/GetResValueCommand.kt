package org.grapheneos.appupdateservergenerator.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceFile
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceIdentifier
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceValue
import com.google.devrel.gmscore.tools.apk.arsc.ResourceTableChunk
import org.grapheneos.appupdateservergenerator.apkparsing.resolveReference
import java.io.File
import java.util.zip.ZipFile

class GetResValueCommand : CliktCommand(
    name = "get-res-value",
    help = "Gets a resource value from an APK's resources. Only the printing of string resources is supported right now."
) {
    private val apk: File by argument(help = "The APK to get resources from")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)
    private val hex: Boolean by option().flag()
    private val resourceId: String by argument()

    override fun run() {
        val isHexString = resourceId.startsWith("0x")
        val actualResId = if (hex || isHexString) {
            val stringToParse = if (isHexString) resourceId.substringAfter("0x") else resourceId
            stringToParse.toInt(radix = 16)
        } else {
            resourceId.toInt()
        }

        val resources: BinaryResourceFile = ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry("resources.arsc")).use { BinaryResourceFile.fromInputStream(it) }
        }
        val resourceTableChunk = resources.chunks.first() as ResourceTableChunk

        val binaryResId = BinaryResourceIdentifier.create(actualResId)
        val resolvedResource = resourceTableChunk.resolveReference(binaryResId)!!
        val type = resolvedResource.value.type()

        println("resource with id $resourceId has type ${resolvedResource.value.type()} and config ${resolvedResource.config}")
        if (type == BinaryResourceValue.Type.STRING) {
            val actualString = resourceTableChunk.stringPool.getString(resolvedResource.value.data())
            println("its value is: [$actualString]")
        } else {
            println("its raw value is: [${resolvedResource.value.data()}]")
        }
    }

}