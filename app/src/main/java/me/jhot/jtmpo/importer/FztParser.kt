package me.jhot.jtmpo.importer

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class FztSong(val title: String, val tempo: Int, val numBeats: Int, val numSubBeats: Int, val beatCode: Int)

data class FztSetlist(val title: String, val songs: List<FztSong>)

private const val HEAD_SCAN_BYTES = 4096

/**
 * Parses a Tempo export (an Apple property list): a `.fzt` full export, whose root is an array of
 * setlist dicts, or a `.slist` single-setlist export, whose root is one setlist dict (returned as a
 * one-element list). It is a DOM parse so the duplicate keys some songs carry (`barTotal`,
 * `stopAtCountEnd`) are harmless; none of those are fields we read.
 * Anything that is not a plist of setlists comes back as a failure, never an exception.
 */
fun parseTempoExport(bytes: ByteArray): Result<List<FztSetlist>> = runCatching {
    // Android's parser may ignore the parser features below, so DOCTYPE and ENTITY are also refused up front.
    // A plist export has neither, and entity declarations are what make expansion attacks possible.
    val head = String(bytes, 0, minOf(bytes.size, HEAD_SCAN_BYTES), Charsets.ISO_8859_1).lowercase()
    require("<!doctype" !in head && "<!entity" !in head) { "DOCTYPE and ENTITY declarations are not allowed" }
    val factory = DocumentBuilderFactory.newInstance()
    for (feature in listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
    )) {
        runCatching { factory.setFeature(feature.first, feature.second) }
    }
    factory.isExpandEntityReferences = false
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> InputSource(java.io.StringReader("")) }
    builder.setErrorHandler(null)
    val root = builder.parse(ByteArrayInputStream(bytes)).documentElement
    require(root.tagName == "plist") { "Not a property list" }
    val content = root.elements().firstOrNull { it.tagName == "array" || it.tagName == "dict" }
        ?: error("No setlist array or dict")
    if (content.tagName == "array") {
        content.elements().map { setlist(it) }
    } else {
        // A single exported setlist must say what it is; a stray dict is not an empty import.
        require("songs" in content.dictEntries()) { "Setlist dict without songs" }
        listOf(setlist(content))
    }
}

private fun Element.elements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

/** Key to value element; for a repeated key the last one wins. */
private fun Element.dictEntries(): Map<String, Element> {
    require(tagName == "dict") { "Expected a dict, found <$tagName>" }
    val out = LinkedHashMap<String, Element>()
    val children = elements()
    var i = 0
    while (i < children.size) {
        if (children[i].tagName != "key") { i++; continue }
        require(i + 1 < children.size) { "Key without a value" }
        out[children[i].textContent.trim()] = children[i + 1]
        i += 2
    }
    return out
}

private fun Element?.int(name: String, default: Int? = null): Int {
    if (this == null) return default ?: error("Missing $name")
    require(tagName == "integer") { "$name is not an integer" }
    return textContent.trim().toInt()
}

private fun setlist(dict: Element): FztSetlist {
    val fields = dict.dictEntries()
    val songs = fields["songs"]?.elements()?.map { song(it) } ?: emptyList()
    return FztSetlist(fields["title"]?.textContent.orEmpty(), songs)
}

private fun song(dict: Element): FztSong {
    val f = dict.dictEntries()
    val title = f["title"]?.takeIf { it.tagName == "string" }?.textContent ?: error("Song without a title")
    return FztSong(
        title = title,
        tempo = f["tempo"].int("tempo"),
        numBeats = f["numBeats"].int("numBeats"),
        numSubBeats = f["numSubBeats"].int("numSubBeats"),
        beatCode = f["beatCode"].int("beatCode", default = 0),
    )
}
