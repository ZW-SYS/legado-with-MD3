package io.legado.app.model.localBook

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 编辑 .nex 文件。
 *
 * 两种用法：
 * 1. edit(...)               —— 改书名、作者、封面（旧接口，保留）
 * 2. exportFromEditor(...)   —— 接收 WebView 编辑器导出的内容，写回 .nex
 */
object NexEditor {

    /**
     * 旧接口：改书名、作者、封面。
     */
    suspend fun edit(
        context: Context,
        book: Book,
        newTitle: String,
        newAuthor: String,
        newCoverUri: Uri?,
        onProgress: (String) -> Unit = {}
    ): Book = withContext(Dispatchers.IO) {
        val title = newTitle.ifBlank { book.name }
        val author = newAuthor.ifBlank { book.author }

        onProgress("读取 .nex")
        val tmpIn = copyNexToTemp(book)
        val tmpOut = File(appCtx.cacheDir, "nex_edit_out_${System.currentTimeMillis()}.nex")

        var coverAssetPath: String? = null
        if (newCoverUri != null) {
            val ext = guessCoverExt(context, newCoverUri)
            coverAssetPath = "assets/cover_${System.currentTimeMillis()}$ext"
        }

        onProgress("写入 .nex")
        ZipFile(tmpIn).use { zip ->
            ZipOutputStream(FileOutputStream(tmpOut).buffered()).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name == "manifest.json") continue
                    if (name.startsWith("assets/cover")) continue
                    out.putNextEntry(ZipEntry(name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }

                val manifest = JSONObject().apply {
                    put("format", "nex")
                    put("version", "1.0")
                    put("spec_author", "ZW-SYS")
                    put("spec_url", "https://github.com/ZW-SYS/legado-with-MD3-Max")
                    put("title", title)
                    put("author", author)
                    put("language", "zh-CN")
                    if (coverAssetPath != null) put("cover", coverAssetPath)
                }
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                out.closeEntry()

                if (coverAssetPath != null && newCoverUri != null) {
                    context.contentResolver.openInputStream(newCoverUri)?.use { input ->
                        out.putNextEntry(ZipEntry(coverAssetPath))
                        input.copyTo(out)
                        out.closeEntry()
                    }
                }
            }
        }

        onProgress("保存")
        copyBackToBook(book, tmpOut)

        tmpIn.delete()
        tmpOut.delete()

        book.name = title
        book.author = author
        if (newCoverUri != null) {
            val coverPath = book.coverUrl?.takeIf { it.isNotEmpty() }
                ?: LocalBook.getCoverPath(book).also { book.coverUrl = it }
            FileUtils.createFileIfNotExist(coverPath)
            context.contentResolver.openInputStream(newCoverUri)?.use { input ->
                FileOutputStream(coverPath).use { input.copyTo(it) }
            }
        }
        book.save()
        NexFile.clear()
        book
    }

    /**
     * 新接口：WebView 编辑器保存。
     *
     * @param bookUrl   已有 .nex 的 bookUrl；如果要新建，传 null
     * @param payload   编辑器传来的 JSON 字符串：{"title","author","html","fontSize"}
     * @param onProgress 进度回调
     * @return 保存后的 .nex 文件（覆盖模式返回空文件占位）
     */
    suspend fun exportFromEditor(
        context: Context,
        bookUrl: String?,
        payload: String,
        onProgress: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val json = JSONObject(payload)
        val title = json.optString("title", "未命名").ifBlank { "未命名" }
        val author = json.optString("author", "佚名").ifBlank { "佚名" }
        val bodyHtml = json.optString("html", "")
        val fontSize = json.optInt("fontSize", 16)

        onProgress("解析内容")
        val assets = mutableListOf<Pair<String, ByteArray>>()
        val rewritten = extractAndRewriteDataUris(bodyHtml, assets)

        onProgress("自动分章")
        val chapters = splitIntoChapters(rewritten)

        onProgress("打包 .nex")
        val outFile = File(context.cacheDir, "nex_export_${System.currentTimeMillis()}.nex")

        ZipOutputStream(FileOutputStream(outFile).buffered()).use { zip ->
            val manifest = JSONObject().apply {
                put("format", "nex")
                put("version", "1.0")
                put("spec_author", "ZW-SYS")
                put("spec_url", "https://github.com/ZW-SYS/legado-with-MD3-Max")
                put("title", title)
                put("author", author)
                put("language", "zh-CN")
            }
            putText(zip, "manifest.json", manifest.toString(2))

            val chapterArr = org.json.JSONArray()
            chapters.forEachIndexed { i, (t, _) ->
                chapterArr.put(JSONObject().apply {
                    put("id", "ch${i + 1}")
                    put("title", t)
                    put("file", "content/ch${i + 1}.html")
                })
            }
            val book = JSONObject().apply {
                put("chapters", chapterArr)
                put("settings", JSONObject().apply {
                    put("defaultFontSize", fontSize)
                    put("lineHeight", 1.8)
                })
            }
            putText(zip, "book.json", book.toString(2))

            putText(zip, "style/main.css", NEX_CSS)

            chapters.forEachIndexed { i, (t, body) ->
                val html = """<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>${escapeHtml(t)}</title>
<link rel="stylesheet" href="../style/main.css"></head>
<body>
$body
</body></html>"""
                putText(zip, "content/ch${i + 1}.html", html)
            }

            assets.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        if (bookUrl != null) {
            onProgress("写回原文件")
            val fakeBook = Book(bookUrl = bookUrl)
            copyBackToBook(fakeBook, outFile)
            outFile.delete()
            NexFile.clear()
            return@withContext File("")
        }

        outFile
    }

    /* ===================== 内部工具 ===================== */

    private fun copyNexToTemp(book: Book): File {
        val tmp = File(appCtx.cacheDir, "nex_edit_in_${System.currentTimeMillis()}.nex")
        val pfd = BookHelp.getBookPFD(book)
            ?: throw IllegalStateException("无法打开 .nex：${book.bookUrl}")
        FileInputStream(pfd.fileDescriptor).use { input ->
            FileOutputStream(tmp).use { input.copyTo(it) }
        }
        pfd.close()
        return tmp
    }

    private fun copyBackToBook(book: Book, src: File) {
        val uri = book.bookUrl.toUri()
        val output = appCtx.contentResolver.openOutputStream(uri, "wt")
            ?: FileOutputStream(File(uri.path!!))
        output.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private fun guessCoverExt(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri) ?: ""
        return when {
            mime.contains("png", true) -> ".png"
            mime.contains("webp", true) -> ".webp"
            mime.contains("gif", true) -> ".gif"
            else -> ".jpg"
        }
    }

    /**
     * 把 HTML 里所有 data:image/...;base64,xxx 和 data:video/...;base64,xxx 抽出来，
     * 解码写入 assets/，并把 src 改成相对路径 ../assets/xxx。
     * （兼容老编辑器；新编辑器应该直接传 file:// 路径，不走这里）
     */
    private fun extractAndRewriteDataUris(
        html: String,
        outAssets: MutableList<Pair<String, ByteArray>>
    ): String {
        val regex = Regex(
            "src\\s*=\\s*\"(data:(image|video)/[^\";]+;base64,)([^\"]+)\"",
            RegexOption.IGNORE_CASE
        )
        return regex.replace(html) { m ->
            val mimeType = m.groupValues[2].lowercase()
            val b64 = m.groupValues[3]
            val ext = when {
                mimeType == "image" && m.value.contains("image/png", true) -> ".png"
                mimeType == "image" && m.value.contains("image/gif", true) -> ".gif"
                mimeType == "image" && m.value.contains("image/webp", true) -> ".webp"
                mimeType == "image" -> ".jpg"
                mimeType == "video" && m.value.contains("video/webm", true) -> ".webm"
                mimeType == "video" -> ".mp4"
                else -> ".bin"
            }
            val subdir = if (mimeType == "video") "video" else "img"
            val name = "u_${UUID.randomUUID().toString().take(8)}$ext"
            val path = "assets/$subdir/$name"
            val bytes = decodeBase64(b64)
            if (bytes != null) {
                outAssets.add(path to bytes)
                "src=\"../$path\""
            } else {
                m.value
            }
        }
    }

    private fun decodeBase64(s: String): ByteArray? {
        return try {
            android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 把整篇 HTML 按「第X章」等标题切成多章。
     */
    private fun splitIntoChapters(html: String): List<Pair<String, String>> {
        val doc = org.jsoup.Jsoup.parseBodyFragment(html)
        val body = doc.body()

        val chapters = mutableListOf<Pair<String, String>>()
        var currentTitle: String? = null
        val buffer = StringBuilder()

        fun flush() {
            if (currentTitle != null || buffer.isNotBlank()) {
                chapters.add((currentTitle ?: "正文") to buffer.toString())
            }
            buffer.clear()
        }

        for (node in body.childNodes()) {
            val nodeHtml = when (node) {
                is org.jsoup.nodes.Element -> node.outerHtml()
                is org.jsoup.nodes.TextNode -> escapeHtml(node.text())
                else -> node.outerHtml()
            }
            val text = when (node) {
                is org.jsoup.nodes.Element -> node.text().trim()
                is org.jsoup.nodes.TextNode -> node.text().trim()
                else -> ""
            }
            if (text.isNotEmpty() && isChapterTitle(text)) {
                flush()
                currentTitle = text
                buffer.append("<h2>${escapeHtml(text)}</h2>\n")
            } else {
                buffer.append(nodeHtml).append('\n')
            }
        }
        flush()

        if (chapters.isEmpty()) {
            chapters.add("正文" to html)
        }
        return chapters
    }

    private fun isChapterTitle(text: String): Boolean {
        if (text.length > 60 || text.length < 2) return false
        if (Regex("^第[一二三四五六七八九十百千万零两0-9]{1,10}[章节回卷篇][\\s\\S]{0,50}$").matches(text)) return true
        if (Regex("^Chapter\\s+\\d+[\\s\\S]{0,50}$", RegexOption.IGNORE_CASE).matches(text)) return true
        if (Regex("^卷[一二三四五六七八九十百千万零两0-9]{1,10}[\\s\\S]{0,50}$").matches(text)) return true
        if (Regex("^[（(]?[0-9]{1,4}[）)][、\\.\\s]?[\\s\\S]{0,50}$").matches(text) && text.length <= 40) return true
        if (Regex("^[0-9]{1,4}[、\\.][\\s\\S]{1,50}$").matches(text) && text.length <= 40) return true
        return false
    }

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
img, video { max-width: 100%; display: block; margin: 1em 0; border-radius: 8px; }
video { background: #000; }
blockquote { border-left: 4px solid #3d6ef5; margin: 1em 0; padding: 4px 16px; color: #555; background: #f2f6ff; }
hr { border: none; border-top: 1px solid #ddd; margin: 1.5em 0; }
"""
}