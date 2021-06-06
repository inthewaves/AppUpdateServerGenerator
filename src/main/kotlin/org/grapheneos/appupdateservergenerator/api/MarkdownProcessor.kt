package org.grapheneos.appupdateservergenerator.api

import com.googlecode.htmlcompressor.compressor.HtmlCompressor
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

object MarkdownProcessor {
    // Custom configurations and Markdown flavor changes can be made here
    private val markdownFlavor: MarkdownFlavourDescriptor by lazy { GFMFlavourDescriptor() }
    private val htmlCompressor: HtmlCompressor by lazy { HtmlCompressor() }

    private val markdownParser by lazy { MarkdownParser(markdownFlavor) }

    fun markdownToCompressedHtml(markdown: String): String {
        val mdTree = markdownParser.buildMarkdownTreeFromString(markdown)
        val generatedHtml = HtmlGenerator(markdown, mdTree, markdownFlavor).generateHtml()
        return htmlCompressor.compress(generatedHtml)
    }
}