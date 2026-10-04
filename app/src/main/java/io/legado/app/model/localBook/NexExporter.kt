package io.legado.app.model.localBook

import android.content.Context
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 把 .nex 导出成其他格式。
 *
 * - toEpub：生成标准 EPUB 3，其他阅读器能读
 * - toTxt：纯文本
 */
object NexExporter {

    /**
     * .nex → EPUB
     */
    suspend fun toEpub(
        context: Context,
        book: Book,
        onProgress: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        onProgress("读取 .nex")
        val tmpIn = copyNexToTemp(book)
        val outFile = File(context.cacheDir, sanitize(book.name) + ".epub")

        try {
            ZipFile(tmpIn).use { zip ->
                // 读元数据
                var title = book.name
                var author = book.author
                var coverPathInZip: String? = null
                val manifestEntry = zip.getEntry("manifest.json")
                if (manifestEntry != null) {
                    val m = JSONObject(
                        zip.getInputStream(manifestEntry).bufferedReader().readText()
                    )
                    title = m.optString("title", title)
                    author = m.optString("author", author)
                    coverPathInZip = m.optString("cover", "").takeIf { it.isNotEmpty() }
                }

                // 读章节列表
                val bookJsonEntry = zip.getEntry("book.json")
                val chapterList = mutableListOf<Triple<String, String, String>>() // id, title, file
                if (bookJsonEntry != null) {
                    val bj = JSONObject(
                        zip.getInputStream(bookJsonEntry).bufferedReader().readText()
                    )
                    val arr = bj.optJSONArray("chapters")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val id = o.optString("id", "ch${i + 1}")
                            val t = o.optString("title", "第 ${i + 1} 章")
                            val f = o.optString("file", "content/ch${i + 1}.html")
                            chapterList.add(Triple(id, t, f))
                        }
                    }
                }

                if (chapterList.isEmpty()) throw IllegalStateException("没有章节可导出")

                onProgress("生成 EPUB")

                ZipOutputStream(FileOutputStream(outFile).buffered()).use { out ->
                    // mimetype 必须第一个，且不压缩
                    val mimetypeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
                    val mimetypeEntry = ZipEntry("mimetype").apply {
                        method = ZipEntry.STORED
                        size = mimetypeBytes.size.toLong()
                        compressedSize = mimetypeBytes.size.toLong()
                        crc = CRC32().apply { update(mimetypeBytes) }.value
                    }
                    out.putNextEntry(mimetypeEntry)
                    out.write(mimetypeBytes)
                    out.closeEntry()

                    // META-INF/container.xml
                    putText(out, "META-INF/container.xml", CONTAINER_XML)

                    // 拷贝 assets 到 EPUB
                    onProgress("拷贝资源")
                    val assets = mutableListOf<Triple<String, String, String>>() // id, epubPath, mediaType
                    var assetIdx = 0
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.isDirectory) continue
                        if (!e.name.startsWith("assets/")) continue

                        val originalPath = e.name
                        val ext = originalPath.substringAfterLast('.', "bin").lowercase()
                        val mediaType = guessMediaType(ext)
                        val subdir = when {
                            originalPath.startsWith("assets/img/") -> "images"
                            originalPath.startsWith("assets/video/") -> "videos"
                            else -> "misc"
                        }
                        val newName = "asset_${assetIdx++}.$ext"
                        val epubPath = "OEBPS/$subdir/$newName"
                        assets.add(Triple(originalPath, epubPath, mediaType))

                        out.putNextEntry(ZipEntry(epubPath))
                        zip.getInputStream(e).use { it.copyTo(out) }
                        out.closeEntry()
                    }

                    // 资源路径映射：assets/xxx → ../images/xxx（在 OEBPS 下的相对路径）
                    val assetMap = assets.associate { (orig, epub, _) ->
                        orig to epub.removePrefix("OEBPS/")
                    }

                    // 每章
                    chapterList.forEachIndexed { i, (id, chTitle, file) ->
                        val entry = zip.getEntry(file) ?: return@forEachIndexed
                        var html = zip.getInputStream(entry)
                            .bufferedReader(Charsets.UTF_8).readText()

                        val bodyRegex = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
                        var body = bodyRegex.find(html)?.groupValues?.get(1) ?: html

                        // 改资源路径
                        body = body.replace(
                            Regex("(?:\\.\\./)+assets/([^\"'\\s>]+)")
                        ) { m ->
                            val key = "assets/${m.groupValues[1]}"
                            assetMap[key]?.let { "../$it" } ?: m.value
                        }

                        val xhtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head>
<meta charset="utf-8"/>
<title>${escapeXml(chTitle)}</title>
<link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
$body
</body>
</html>"""
                        putText(out, "OEBPS/text/ch${i + 1}.xhtml", xhtml)
                    }

                    // 样式
                    putText(out, "OEBPS/style.css", EPUB_CSS)

                    // content.opf
                    val opf = buildOpf(title, author, chapterList, assets, coverPathInZip)
                    putText(out, "OEBPS/content.opf", opf)

                    // nav.xhtml（EPUB3 目录）
                    val nav = buildNav(chapterList)
                    putText(out, "OEBPS/nav.xhtml", nav)

                    // toc.ncx（兼容 EPUB2）
                    val ncx = buildNcx(title, chapterList)
                    putText(out, "OEBPS/toc.ncx", ncx)
                }
            }
            outFile
        } finally {
            tmpIn.delete()
        }
    }

    /**
     * .nex → TXT
     */
    suspend fun toTxt(
        context: Context,
        book: Book,
        onProgress: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        onProgress("读取 .nex")
        val tmpIn = copyNexToTemp(book)
        val outFile = File(context.cacheDir, sanitize(book.name) + ".txt")

        try {
            val sb = StringBuilder()
            ZipFile(tmpIn).use { zip ->
                val bookJsonEntry = zip.getEntry("book.json")
                    ?: throw IllegalStateException("缺少 book.json")
                val bj = JSONObject(
                    zip.getInputStream(bookJsonEntry).bufferedReader().readText()
                )
                val arr = bj.optJSONArray("chapters") ?: throw IllegalStateException("没有章节")

                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val title = o.optString("title", "第 ${i + 1} 章")
                    val file = o.optString("file", "content/ch${i + 1}.html")

                    val entry = zip.getEntry(file) ?: continue
                    val html = zip.getInputStream(entry)
                        .bufferedReader(Charsets.UTF_8).readText()
                    val bodyRegex = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
                    val body = bodyRegex.find(html)?.groupValues?.get(1) ?: html

                    val doc = Jsoup.parse(body)
                    val text = doc.text()

                    sb.append("\n\n").append(title).append("\n\n")
                    sb.append(text)
                }
            }
            outFile.writeText(sb.toString(), Charsets.UTF_8)
            outFile
        } finally {
            tmpIn.delete()
        }
    }

    /* ===================== 内部工具 ===================== */

    private fun copyNexToTemp(book: Book): File {
        val tmp = File(appCtx.cacheDir, "nex_export_in_${System.currentTimeMillis()}.nex")
        val pfd = BookHelp.getBookPFD(book)
            ?: throw IllegalStateException("无法打开 .nex：${book.bookUrl}")
        FileInputStream(pfd.fileDescriptor).use { input ->
            FileOutputStream(tmp).use { input.copyTo(it) }
        }
        pfd.close()
        return tmp
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun sanitize(name: String): String {
        val r = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return if (r.isEmpty()) "untitled" else if (r.length > 60) r.take(60) else r
    }

    private fun guessMediaType(ext: String): String = when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    private fun buildOpf(
        title: String,
        author: String,
        chapters: List<Triple<String, String, String>>,
        assets: List<Triple<String, String, String>>,
        coverPathInZip: String?
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="BookId">urn:uuid:${UUID.randomUUID()}</dc:identifier>
<dc:title>${escapeXml(title)}</dc:title>
<dc:creator>${escapeXml(author)}</dc:creator>
<dc:language>zh-CN</dc:language>
<meta property="dcterms:modified">${java.time.Instant.now().toString().substringBefore('.') + "Z"}</meta>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="css" href="style.css" media-type="text/css"/>
""")
        chapters.forEachIndexed { i, _ ->
            sb.append("""<item id="ch${i + 1}" href="text/ch${i + 1}.xhtml" media-type="application/xhtml+xml"/>
""")
        }
        assets.forEachIndexed { i, (_, epubPath, mediaType) ->
            sb.append("""<item id="asset$i" href="${epubPath.removePrefix("OEBPS/")}" media-type="$mediaType"/>
""")
        }
        sb.append("</manifest>\n<spine toc=\"ncx\">\n")
        chapters.forEachIndexed { i, _ ->
            sb.append("""<itemref idref="ch${i + 1}"/>
""")
        }
        sb.append("</spine>\n</package>")
        return sb.toString()
    }

    private fun buildNav(chapters: List<Triple<String, String, String>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><meta charset="utf-8"/><title>目录</title></head>
<body>
<nav epub:type="toc" id="toc">
<h1>目录</h1>
<ol>
""")
        chapters.forEachIndexed { i, (_, title, _) ->
            sb.append("""<li><a href="text/ch${i + 1}.xhtml">${escapeXml(title)}</a></li>
""")
        }
        sb.append("</ol>\n</nav>\n</body>\n</html>")
        return sb.toString()
    }

    private fun buildNcx(title: String, chapters: List<Triple<String, String, String>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="urn:uuid:${UUID.randomUUID()}"/></head>
<docTitle><text>${escapeXml(title)}</text></docTitle>
<navMap>
""")
        chapters.forEachIndexed { i, (_, chTitle, _) ->
            sb.append("""<navPoint id="np$i" playOrder="${i + 1}">
<navLabel><text>${escapeXml(chTitle)}</text></navLabel>
<content src="text/ch${i + 1}.xhtml"/>
</navPoint>
""")
        }
        sb.append("</navMap>\n</ncx>")
        return sb.toString()
    }

    private const val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles>
<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
</rootfiles>
</container>"""

    private const val EPUB_CSS = """
body {
    font-family: serif;
    font-size: 1em;
    line-height: 1.8;
    max-width: 42em;
    margin: 0 auto;
    padding: 1em;
    color: #222;
    background: #fff;
    word-break: break-word;
}
h1, h2, h3 { color: #111; border-bottom: 1px solid #eee; padding-bottom: .3em; }
img, video { max-width: 100%; display: block; margin: 1em 0; }
blockquote { border-left: 4px solid #888; margin: 1em 0; padding: 4px 16px; color: #555; }
"""
}