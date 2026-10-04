package io.legado.app.model.localBook

import android.content.Context
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 把 EPUB / DOCX / TXT 转成 .nex 文件。
 */
object NexConverter {

    private data class Chapter(val title: String, val html: String)
    private data class Asset(val path: String, val bytes: ByteArray)
    private data class NexContent(
        val title: String,
        val author: String,
        val chapters: List<Chapter>,
        val assets: List<Asset>
    )

    suspend fun convert(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val lower = displayName.lowercase()
        val content = when {
            lower.endsWith(".epub") -> parseEpub(context, uri, displayName, onProgress)
            lower.endsWith(".docx") -> parseDocx(context, uri, displayName, onProgress)
            lower.endsWith(".txt") -> parseTxt(context, uri, displayName, onProgress)
            else -> throw IllegalArgumentException("暂不支持：$displayName")
        }
        onProgress("正在打包 .nex")
        val outFile = File(context.cacheDir, "nex_converted_${System.currentTimeMillis()}.nex")
        writeNex(outFile, content)
        outFile
    }

    private fun writeNex(outFile: File, content: NexContent) {
        ZipOutputStream(FileOutputStream(outFile).buffered()).use { zip ->
            val manifest = JSONObject().apply {
                put("format", "nex")
                put("version", "1.0")
                put("spec_author", "ZW-SYS")
                put("spec_url", "https://github.com/ZW-SYS/legado-with-MD3")
                put("title", content.title)
                put("author", content.author)
                put("language", "zh-CN")
            }
            putText(zip, "manifest.json", manifest.toString(2))

            val chapterArr = JSONArray()
            content.chapters.forEachIndexed { i, ch ->
                chapterArr.put(JSONObject().apply {
                    put("id", "ch${i + 1}")
                    put("title", ch.title)
                    put("file", "content/ch${i + 1}.html")
                })
            }
            val book = JSONObject().apply {
                put("chapters", chapterArr)
                put("settings", JSONObject().apply {
                    put("defaultFontSize", 16)
                    put("lineHeight", 1.8)
                })
            }
            putText(zip, "book.json", book.toString(2))
            putText(zip, "style/main.css", NEX_CSS)

            content.chapters.forEachIndexed { i, ch ->
                putText(zip, "content/ch${i + 1}.html", buildChapterHtml(ch.title, ch.html))
            }

            content.assets.forEach { asset ->
                zip.putNextEntry(ZipEntry(asset.path))
                zip.write(asset.bytes)
                zip.closeEntry()
            }
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildChapterHtml(title: String, body: String): String {
        val safeTitle = escapeHtml(title)
        return """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>$safeTitle</title>
<link rel="stylesheet" href="../style/main.css">
</head>
<body>
$body
</body>
</html>"""
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private const val NEX_CSS = """
body {
    font-family: 'Noto Serif SC', Georgia, serif;
    font-size: 16px;
    line-height: 1.8;
    max-width: 42em;
    margin: 0 auto;
    padding: 2em 1em;
    color: #222;
    background: #fff;
    word-break: break-word;
}
h1, h2, h3 { color: #111; border-bottom: 2px solid #eee; padding-bottom: .3em; }
img, video { max-width: 100%; display: block; margin: 1em 0; }
video { width: 100%; background: #000; }
blockquote { border-left: 4px solid #3d7dff; margin: 1em 0; padding: 4px 16px; color: #555; background: #f2f6ff; }
"""

    /* ===================== EPUB ===================== */

    private fun parseEpub(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (String) -> Unit
    ): NexContent {
        val temp = File.createTempFile("nex_epub_", ".epub", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("无法打开文件")

        try {
            ZipFile(temp).use { zip ->
                onProgress("读取 EPUB 结构")
                val container = zip.getEntry("META-INF/container.xml")
                    ?: throw IllegalStateException("不是有效的 EPUB：缺少 container.xml")
                val containerXml = zip.getInputStream(container).bufferedReader().readText()
                val opfPath = Regex("<rootfile[^>]*full-path=\"([^\"]+)\"")
                    .find(containerXml)?.groupValues?.get(1)
                    ?: throw IllegalStateException("EPUB 缺少 rootfile")
                val opfDir = opfPath.substringBeforeLast("/", "").let {
                    if (it.isEmpty()) "" else "$it/"
                }

                val opfEntry = zip.getEntry(opfPath)
                    ?: throw IllegalStateException("找不到 opf: $opfPath")
                val opfXml = zip.getInputStream(opfEntry).bufferedReader().readText()

                val title = extractXmlTag(opfXml, "dc:title")
                    .ifEmpty { displayName.substringBeforeLast(".") }
                val author = extractXmlTag(opfXml, "dc:creator")

                val manifest = mutableMapOf<String, String>()
                val mediaTypes = mutableMapOf<String, String>()
                Regex("<item\\b[^>]*>").findAll(opfXml).forEach { m ->
                    val item = m.value
                    val id = Regex("id=\"([^\"]+)\"").find(item)?.groupValues?.get(1)
                        ?: return@forEach
                    val href = Regex("href=\"([^\"]+)\"").find(item)?.groupValues?.get(1)
                        ?: return@forEach
                    val mt = Regex("media-type=\"([^\"]+)\"").find(item)
                        ?.groupValues?.get(1) ?: ""
                    manifest[id] = opfDir + href
                    mediaTypes[id] = mt
                }

                val spineIds = Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(opfXml)
                    .map { it.groupValues[1] }.toList()

                val titleMap = mutableMapOf<String, String>()
                val ncxPath = Regex("<item\\b[^>]*media-type=\"application/x-dtbncx\\+xml\"[^>]*href=\"([^\"]+)\"")
                    .find(opfXml)?.groupValues?.get(1)?.let { opfDir + it }
                if (ncxPath != null) {
                    zip.getEntry(ncxPath)?.let { e ->
                        val ncxXml = zip.getInputStream(e).bufferedReader().readText()
                        Regex("<navPoint[^>]*>[\\s\\S]*?</navPoint>").findAll(ncxXml).forEach { m ->
                            val seg = m.value
                            val label = Regex("<text>([\\s\\S]*?)</text>").find(seg)
                                ?.groupValues?.get(1)?.trim().orEmpty()
                            val src = Regex("<content[^>]*src=\"([^\"]+)\"").find(seg)
                                ?.groupValues?.get(1)?.substringBefore("#")?.let { opfDir + it }
                            if (label.isNotEmpty() && src != null) titleMap[src] = label
                        }
                    }
                }
                if (titleMap.isEmpty()) {
                    val navPath = Regex("<item\\b[^>]*properties=\"[^\"]*nav[^\"]*\"[^>]*href=\"([^\"]+)\"")
                        .find(opfXml)?.groupValues?.get(1)?.let { opfDir + it }
                    if (navPath != null) {
                        zip.getEntry(navPath)?.let { e ->
                            val navDoc = Jsoup.parse(zip.getInputStream(e).bufferedReader().readText())
                            navDoc.select("nav a[href]").forEach { a ->
                                val label = a.text().trim()
                                val href = a.attr("href").substringBefore("#")
                                if (label.isNotEmpty() && href.isNotEmpty()) {
                                    titleMap[opfDir + href] = label
                                }
                            }
                        }
                    }
                }

                onProgress("抽取 EPUB 资源")
                val assetMap = mutableMapOf<String, String>()
                val assets = mutableListOf<Asset>()
                manifest.forEach { (id, full) ->
                    val mt = mediaTypes[id] ?: return@forEach
                    val subdir = when {
                        mt.startsWith("image/") -> "img"
                        mt.startsWith("video/") -> "video"
                        else -> return@forEach
                    }
                    val entry = zip.getEntry(full) ?: return@forEach
                    val bytes = zip.getInputStream(entry).readBytes()
                    val ext = guessExt(full)
                    val name = "r_${UUID.randomUUID().toString().take(8)}$ext"
                    val path = "assets/$subdir/$name"
                    assets.add(Asset(path, bytes))
                    assetMap[full] = path
                }

                onProgress("解析 EPUB 章节")
                val chapters = mutableListOf<Chapter>()
                var idx = 0
                for (id in spineIds) {
                    val full = manifest[id] ?: continue
                    val mt = mediaTypes[id] ?: ""
                    if (!mt.contains("html") && !mt.contains("xml")) continue
                    val lowerFull = full.lowercase()
                    if (lowerFull.contains("cover") ||
                        lowerFull.contains("titlepage") ||
                        lowerFull.contains("nav.xhtml") ||
                        lowerFull.contains("toc.") ||
                        lowerFull.contains("copyright")
                    ) continue

                    val entry = zip.getEntry(full) ?: continue
                    val raw = zip.getInputStream(entry).bufferedReader().readText()

                    val doc = Jsoup.parse(raw)
                    doc.select("script, style").remove()

                    val chapterDir = full.substringBeforeLast("/", "").let {
                        if (it.isEmpty()) "" else "$it/"
                    }
                    doc.select("img, video, source, audio").forEach { el ->
                        val src = el.attr("src").ifEmpty { el.attr("xlink:href") }
                        if (src.isBlank()) return@forEach
                        val resolved = resolveHref(chapterDir, src)
                        val mapped = assetMap[resolved]
                        if (mapped != null) {
                            el.attr("src", "../$mapped")
                        } else {
                            el.remove()
                        }
                    }

                    val body = doc.body()?.html() ?: continue
                    if (body.isBlank()) continue

                    idx++
                    onProgress("EPUB 第 $idx 章")

                    val chapterTitle = extractChapterTitle(
                        mapped = titleMap[full],
                        doc = doc,
                        path = full,
                        idx = idx
                    )
                    chapters.add(Chapter(chapterTitle, body))
                }

                if (chapters.isEmpty()) throw IllegalStateException("EPUB 没有可读章节")
                return NexContent(title, author, chapters, assets)
            }
        } finally {
            temp.delete()
        }
    }

    private fun extractChapterTitle(
        mapped: String?,
        doc: Document,
        path: String,
        idx: Int
    ): String {
        if (!mapped.isNullOrBlank() && !isFileNameLike(mapped)) {
            return mapped
        }
        doc.selectFirst("h1, h2, h3")?.text()?.trim()?.let {
            if (it.isNotEmpty() && !isFileNameLike(it)) return it
        }
        doc.selectFirst("body p")?.text()?.trim()?.let {
            if (it.length >= 2 && !isFileNameLike(it)) {
                return if (it.length > 40) it.take(40) + "…" else it
            }
        }
        return prettifyFileName(path, idx)
    }

    private fun isFileNameLike(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        if (t.matches(Regex("^[a-zA-Z_\\-]+\\d*\\.(x?html?|xml|htm|xhtml)$", RegexOption.IGNORE_CASE))) return true
        if (t.matches(Regex("^(ch|chapter|part|sec|section|index|c\\d*)\\d*$", RegexOption.IGNORE_CASE))) return true
        return false
    }

    private fun prettifyFileName(path: String, idx: Int): String {
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        val num = Regex("(\\d+)").find(name)?.groupValues?.get(1)
        return if (num != null) {
            val n = num.toIntOrNull()
            if (n != null) "第 $n 章" else "第 $num 章"
        } else {
            "第 $idx 章"
        }
    }

    /* ===================== DOCX ===================== */

    private fun parseDocx(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (String) -> Unit
    ): NexContent {
        onProgress("读取 DOCX")
        val temp = File.createTempFile("nex_docx_", ".zip", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("无法打开文件")

        try {
            ZipFile(temp).use { zip ->
                var title = displayName.substringBeforeLast(".")
                var author = ""
                val coreFile = zip.getEntry("docProps/core.xml")
                if (coreFile != null) {
                    val xml = zip.getInputStream(coreFile).bufferedReader().readText()
                    title = extractXmlTag(xml, "dc:title").ifEmpty { title }
                    author = extractXmlTag(xml, "dc:creator")
                }

                onProgress("解析 DOCX 关系")
                val relsEntry = zip.getEntry("word/_rels/document.xml.rels")
                val relMap = mutableMapOf<String, String>()
                if (relsEntry != null) {
                    val relsXml = zip.getInputStream(relsEntry).bufferedReader().readText()
                    Regex("<Relationship[^>]*>").findAll(relsXml).forEach { m ->
                        val seg = m.value
                        val rid = Regex("Id=\"([^\"]+)\"").find(seg)?.groupValues?.get(1)
                            ?: return@forEach
                        val target = Regex("Target=\"([^\"]+)\"").find(seg)
                            ?.groupValues?.get(1) ?: return@forEach
                        if (target.contains("media/")) {
                            val normalized = target.removePrefix("/").let {
                                if (it.startsWith("word/")) it else "word/$it"
                            }
                            relMap[rid] = normalized
                        }
                    }
                }

                onProgress("抽取 DOCX 媒体")
                val assetMap = mutableMapOf<String, String>()
                val assets = mutableListOf<Asset>()
                zip.entries().asSequence()
                    .filter { it.name.startsWith("word/media/") }
                    .forEach { entry ->
                        val bytes = zip.getInputStream(entry).readBytes()
                        val ext = entry.name.substringAfterLast(".", "bin")
                        val subdir = if (ext.lowercase() in
                            listOf("mp4", "webm", "mov", "avi", "mkv")
                        ) "video" else "img"
                        val name = "d_${UUID.randomUUID().toString().take(8)}.$ext"
                        val path = "assets/$subdir/$name"
                        assets.add(Asset(path, bytes))
                        relMap.entries.firstOrNull { it.value == entry.name }?.let { e ->
                            assetMap[e.key] = path
                        }
                    }

                onProgress("解析 DOCX 正文")
                val docEntry = zip.getEntry("word/document.xml")
                    ?: throw IllegalStateException("DOCX 缺少 word/document.xml")
                val paragraphs = parseDocxParagraphs(zip.getInputStream(docEntry), assetMap)

                // 先把所有段落拼成一整段纯文本，按「第X章」的正则扫描一遍，
                // 这样即使 Word 里标题没设样式、只是普通段落，也能识别出来。
                val chapters = splitDocxParagraphs(paragraphs)

                return NexContent(title, author, chapters, assets)
            }
        } finally {
            temp.delete()
        }
    }

    private data class DocxParagraph(val level: Int, val text: String, val html: String)

    /**
     * 解析 DOCX 段落。
     *
     * 关键修复：Android XmlPullParser 在不同系统上返回的标签名可能带前缀
     * （"w:p"）也可能不带（"p"）。统一去前缀后再比较。
     */
    private fun parseDocxParagraphs(
        input: InputStream,
        assetMap: Map<String, String>
    ): List<DocxParagraph> {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val result = mutableListOf<DocxParagraph>()
        var inParagraph = false
        var headingLevel = 0
        var currentText = StringBuilder()
        var currentHtml = StringBuilder()

        fun local(raw: String?): String {
            if (raw == null) return ""
            val i = raw.indexOf(':')
            return if (i >= 0) raw.substring(i + 1) else raw
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = local(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "p" -> {
                            inParagraph = true
                            headingLevel = 0
                            currentText = StringBuilder()
                            currentHtml = StringBuilder()
                        }
                        "pStyle" -> {
                            val v = parser.getAttributeValue(null, "w:val")
                                ?: parser.getAttributeValue(null, "val") ?: ""
                            val lv = local(v)
                            if (lv.contains("Heading1", true) || lv == "1" ||
                                v.contains("Heading1", true)) headingLevel = 1
                            else if (lv.contains("Heading2", true) || lv == "2" ||
                                v.contains("Heading2", true)) headingLevel = 2
                            else if (lv.contains("Heading3", true) || lv == "3" ||
                                v.contains("Heading3", true)) headingLevel = 3
                        }
                        "t" -> {
                            val text = readTextUntilEndTag(parser, "t")
                            if (inParagraph && text.isNotEmpty()) {
                                currentText.append(text)
                                currentHtml.append(escapeHtml(text))
                            }
                        }
                        "br" -> if (inParagraph) currentHtml.append("<br>")
                        "tab" -> if (inParagraph) currentHtml.append("&nbsp;&nbsp;&nbsp;&nbsp;")
                        "blip" -> {
                            val embed = parser.getAttributeValue(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                                "embed"
                            ) ?: parser.getAttributeValue(null, "r:embed")
                            if (embed != null && inParagraph) {
                                val mapped = assetMap[embed]
                                if (mapped != null) {
                                    currentHtml.append("""<img src="../$mapped">""")
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "p" && inParagraph) {
                        inParagraph = false
                        val text = currentText.toString().trim()
                        val html = currentHtml.toString()
                        result.add(DocxParagraph(level = headingLevel, text = text, html = html))
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    /** 从当前 START_TAG 起，读到同名 END_TAG 为止，返回中间的所有文本。 */
    private fun readTextUntilEndTag(parser: XmlPullParser, tag: String): String {
        val sb = StringBuilder()
        var depth = 1
        try {
            var ev = parser.next()
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text ?: "")
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> {
                        depth--
                        if (depth == 0) return sb.toString()
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {
        }
        return sb.toString()
    }

    /**
     * DOCX 分章逻辑（按优先级）：
     *
     * 1. 有 Heading 样式的段落：按 Heading1 切
     * 2. 没有 Heading：扫描每个段落的文本，凡是匹配「第X章 / 第X回 / 第X节 / 卷X / Chapter X」
     *    这类章节标题格式的段落，就当成一章的标题，从它开始切
     * 3. 都没有：整篇一章
     */
    private fun splitDocxParagraphs(paragraphs: List<DocxParagraph>): List<Chapter> {
        // 1. 先试样式
        val byStyle = splitByHeadingStyle(paragraphs, 1)
        if (byStyle.size > 1) return byStyle

        // 2. 再试文本
        val byText = splitByChapterText(paragraphs)
        if (byText.size > 1) return byText

        // 3. 兜底
        val body = paragraphs.joinToString("\n") { p ->
            if (p.html.isBlank()) "" else "<p>${p.html}</p>"
        }
        return listOf(Chapter("正文", body))
    }

    private fun splitByHeadingStyle(paragraphs: List<DocxParagraph>, level: Int): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var currentTitle: String? = null
        val buffer = StringBuilder()

        fun flush() {
            if (currentTitle != null || buffer.isNotBlank()) {
                chapters.add(Chapter(currentTitle ?: "正文", buffer.toString()))
            }
            buffer.clear()
        }

        for (p in paragraphs) {
            if (p.level == level && p.text.isNotEmpty()) {
                flush()
                buffer.append("<h1>${escapeHtml(p.text)}</h1>\n")
                currentTitle = p.text
            } else if (p.html.isNotBlank()) {
                buffer.append("<p>${p.html}</p>\n")
            }
        }
        flush()
        return chapters
    }

    /**
     * 扫描普通段落文本，凡匹配章节标题格式就当标题切章。
     * 这就是用户要的"自动识别文字里的第X章"。
     */
    private fun splitByChapterText(paragraphs: List<DocxParagraph>): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var currentTitle: String? = null
        val buffer = StringBuilder()

        fun flush() {
            if (currentTitle != null || buffer.isNotBlank()) {
                chapters.add(Chapter(currentTitle ?: "正文", buffer.toString()))
            }
            buffer.clear()
        }

        for (p in paragraphs) {
            val text = p.text
            if (text.isNotEmpty() && isChapterTitle(text)) {
                flush()
                buffer.append("<h2>${escapeHtml(text)}</h2>\n")
                currentTitle = text
            } else if (p.html.isNotBlank()) {
                buffer.append("<p>${p.html}</p>\n")
            }
        }
        flush()
        return chapters
    }

    /** 判断一个段落的纯文本是不是章节标题 */
    private fun isChapterTitle(text: String): Boolean {
        if (text.length > 60) return false
        if (text.length < 2) return false
        // 中文常见章节标题
        if (Regex("^第[一二三四五六七八九十百千万零两0-9]{1,10}[章节回卷篇][\\s\\S]{0,50}$").matches(text)) return true
        // Chapter X
        if (Regex("^Chapter\\s+\\d+[\\s\\S]{0,50}$", RegexOption.IGNORE_CASE).matches(text)) return true
        // 卷X
        if (Regex("^卷[一二三四五六七八九十百千万零两0-9]{1,10}[\\s\\S]{0,50}$").matches(text)) return true
        // 纯数字编号 "1." "1、" "（1）" 后跟少量文字
        if (Regex("^[（(]?[0-9]{1,4}[）)][、\\.\\s]?[\\s\\S]{0,50}$").matches(text) &&
            text.length <= 40) return true
        if (Regex("^[0-9]{1,4}[、\\.][\\s\\S]{1,50}$").matches(text) &&
            text.length <= 40) return true
        return false
    }

    /* ===================== TXT ===================== */

    private fun parseTxt(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (String) -> Unit
    ): NexContent {
        onProgress("读取 TXT")
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            decodeText(input.readBytes())
        } ?: throw IllegalStateException("无法打开文件")

        onProgress("分章")
        val chapters = splitTxtByChapter(text)
        return NexContent(
            title = displayName.substringBeforeLast("."),
            author = "",
            chapters = chapters,
            assets = emptyList()
        )
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (utf8 != null && !utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, charset("GBK")) }
            .getOrDefault(String(bytes))
    }

    private fun splitTxtByChapter(text: String): List<Chapter> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val chapters = mutableListOf<Chapter>()
        var currentTitle: String? = null
        val currentBody = StringBuilder()

        fun flush() {
            if (currentTitle != null || currentBody.isNotBlank()) {
                chapters.add(Chapter(currentTitle ?: "正文", bodyToHtml(currentBody.toString())))
            }
            currentBody.clear()
        }

        for (line in lines) {
            val trimmed = line.trim()
            val isTitle = trimmed.isNotEmpty() && isChapterTitle(trimmed)
            if (isTitle) {
                flush()
                currentTitle = trimmed
            } else {
                currentBody.append(line).append('\n')
            }
        }
        flush()

        if (chapters.isEmpty()) {
            chapters.add(Chapter("正文", bodyToHtml(text)))
        }
        return chapters
    }

    private fun bodyToHtml(text: String): String {
        return text.replace("\r\n", "\n").split("\n").joinToString("\n") { line ->
            if (line.isBlank()) "<p>&nbsp;</p>" else "<p>${escapeHtml(line.trim())}</p>"
        }
    }

    /* ===================== 通用工具 ===================== */

    private fun guessExt(href: String): String {
        val lower = href.lowercase()
        return when {
            lower.endsWith(".png") -> ".png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> ".jpg"
            lower.endsWith(".gif") -> ".gif"
            lower.endsWith(".webp") -> ".webp"
            lower.endsWith(".svg") -> ".svg"
            lower.endsWith(".bmp") -> ".bmp"
            lower.endsWith(".mp4") -> ".mp4"
            lower.endsWith(".webm") -> ".webm"
            lower.endsWith(".mov") -> ".mov"
            lower.endsWith(".mkv") -> ".mkv"
            else -> ".bin"
        }
    }

    private fun resolveHref(base: String, rel: String): String {
        if (rel.startsWith("data:") || rel.startsWith("http")) return rel
        if (rel.startsWith("/")) return rel.removePrefix("/")
        val baseParts = base.substringBeforeLast("/")
            .split("/").filter { it.isNotEmpty() }
        val stack = baseParts.toMutableList()
        rel.split("/").forEach { seg ->
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(seg)
            }
        }
        return stack.joinToString("/")
    }

    private fun extractXmlTag(xml: String, tag: String): String {
        val m = Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>").find(xml) ?: return ""
        return m.groupValues[1].trim()
    }
}