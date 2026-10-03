package io.legado.app.model.localBook

import android.content.Context
import android.net.Uri
import android.util.Xml
import io.legado.app.constant.AppLog
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
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
 *
 * 生成的 .nex 结构：
 *   manifest.json
 *   book.json
 *   content/ch1.html, ch2.html ...
 *   assets/img/xxx
 *   assets/video/xxx
 *   style/main.css
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

    /**
     * 把 uri 指向的文件转成 .nex，返回生成的临时文件（在 cacheDir 下）。
     * 调用方负责用 LocalBook.saveBookFile 把它存到用户目录。
     */
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

    /* ===================== 打包 ===================== */

    private fun writeNex(outFile: File, content: NexContent) {
        ZipOutputStream(FileOutputStream(outFile).buffered()).use { zip ->
            val manifest = JSONObject().apply {
                put("format", "nex")
                put("version", "1.0")
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
                    val h = doc.selectFirst("h1, h2, h3, title")
                    val chapterTitle = h?.text()?.trim().orEmpty().ifEmpty { "第 $idx 章" }
                    chapters.add(Chapter(chapterTitle, body))
                }

                if (chapters.isEmpty()) throw IllegalStateException("EPUB 没有可读章节")
                return NexContent(title, author, chapters, assets)
            }
        } finally {
            temp.delete()
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
                val bodyHtml = parseDocxBody(zip.getInputStream(docEntry), assetMap)
                val chapters = splitHtmlByHeading(bodyHtml)
                    .let { if (it.isEmpty()) listOf(Chapter("正文", bodyHtml)) else it }

                return NexContent(title, author, chapters, assets)
            }
        } finally {
            temp.delete()
        }
    }

    private fun parseDocxBody(
        input: InputStream,
        assetMap: Map<String, String>
    ): String {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val sb = StringBuilder()
        var inParagraph = false
        var paragraphStyle = ""
        var currentParagraph = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "w:p" -> {
                            inParagraph = true
                            paragraphStyle = ""
                            currentParagraph = StringBuilder()
                        }
                        "w:pStyle" -> {
                            paragraphStyle = parser.getAttributeValue(null, "w:val")
                                ?: parser.getAttributeValue(null, "val")
                                ?: ""
                        }
                        "w:t" -> {
                            val text = parser.nextText()
                            if (inParagraph) currentParagraph.append(escapeHtml(text))
                        }
                        "w:br" -> {
                            if (inParagraph) currentParagraph.append("<br>")
                        }
                        "a:blip" -> {
                            val embed = parser.getAttributeValue(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                                "embed"
                            ) ?: parser.getAttributeValue(null, "r:embed")
                            if (embed != null) {
                                val mapped = assetMap[embed]
                                if (mapped != null) {
                                    currentParagraph.append(
                                        """<img src="../$mapped">"""
                                    )
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "w:p") {
                        inParagraph = false
                        val text = currentParagraph.toString()
                        if (text.isBlank()) {
                            sb.append("<p>&nbsp;</p>\n")
                        } else {
                            val tag = when {
                                paragraphStyle.contains("Heading1", true) ||
                                        paragraphStyle == "1" -> "h1"
                                paragraphStyle.contains("Heading2", true) ||
                                        paragraphStyle == "2" -> "h2"
                                paragraphStyle.contains("Heading3", true) ||
                                        paragraphStyle == "3" -> "h3"
                                else -> "p"
                            }
                            sb.append("<$tag>$text</$tag>\n")
                        }
                    }
                }
            }
            event = parser.next()
        }
        return sb.toString()
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
        val patterns = listOf(
            Regex("^\\s*第[一二三四五六七八九十百千万零两\\d]+[章节回卷篇][^\\n]{0,40}$"),
            Regex("^\\s*Chapter\\s+\\d+[^\\n]{0,40}$", RegexOption.IGNORE_CASE),
            Regex("^\\s*卷[一二三四五六七八九十百千万零两\\d]+[^\\n]{0,40}$")
        )

        val lines = text.replace("\r\n", "\n").split("\n")
        val chapters = mutableListOf<Chapter>()
        var currentTitle: String? = null
        val currentBody = StringBuilder()

        fun flush() {
            if (currentTitle != null || currentBody.isNotBlank()) {
                val title = currentTitle ?: "正文"
                chapters.add(Chapter(title, bodyToHtml(currentBody.toString())))
            }
            currentBody.clear()
        }

        for (line in lines) {
            val isTitle = patterns.any { it.matches(line) }
            if (isTitle) {
                flush()
                currentTitle = line.trim()
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

    private fun splitHtmlByHeading(html: String): List<Chapter> {
        val parts = html.split(Regex("(?=<h1[^>]*>)", RegexOption.IGNORE_CASE))
            .filter { it.isNotBlank() }
        if (parts.size <= 1) return emptyList()
        return parts.mapIndexed { i, part ->
            val m = Regex("<h1[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE).find(part)
            val title = m?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.trim()
                .orEmpty()
                .ifEmpty { "第 ${i + 1} 章" }
            Chapter(title, part)
        }
    }
}