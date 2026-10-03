package io.legado.app.model.localBook

import android.os.ParcelFileDescriptor
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * .nex 本地书解析。
 *
 * .nex 本质是 ZIP，内部结构：
 *   manifest.json        书名、作者
 *   book.json            章节列表
 *   content/ch1.html     每章正文
 *   assets/img/xxx       图片
 *   style/main.css       样式
 *
 * 为兼容从 SAF / 网盘 / 各种文件管理器导入的文件，这里先把原始流
 * 复制到应用缓存目录，再用普通路径的 ZipFile 打开（避免 fd 不可 seek 的问题）。
 */
class NexFile(var book: Book) {

    companion object : BaseLocalBookParse {
        private var nFile: NexFile? = null

        @Synchronized
        private fun getNFile(book: Book): NexFile {
            if (nFile == null || nFile?.book?.bookUrl != book.bookUrl) {
                nFile = NexFile(book)
                return nFile!!
            }
            nFile?.book = book
            return nFile!!
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getNFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getNFile(book).getContent(chapter)
        }

        @Synchronized
        override fun getImage(book: Book, href: String): InputStream? {
            return getNFile(book).getImage(href)
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            getNFile(book).upBookInfo()
        }

        fun clear() {
            nFile = null
        }
    }

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var zipFile: ZipFile? = null
    private var cacheFile: File? = null

    init {
        upBookCover(true)
    }

    /* ============ 打开 zip ============ */

    @Synchronized
    private fun ensureOpen() {
        if (zipFile != null) return

        val cache = File(appCtx.cacheDir, "nex_" + MD5Utils.md5Encode16(book.bookUrl) + ".zip")
        cacheFile = cache

        // 缓存不存在或为空，就从源复制过来
        if (!cache.exists() || cache.length() == 0L) {
            val descriptor = BookHelp.getBookPFD(book)
                ?: throw IOException("无法打开 .nex 文件：${book.bookUrl}")
            fileDescriptor = descriptor
            try {
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    FileOutputStream(cache).use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }
            } catch (e: Exception) {
                // 复制失败就删掉半成品
                try { cache.delete() } catch (_: Throwable) {}
                throw IOException("复制 .nex 到缓存失败：${e.localizedMessage}", e)
            }
        }

        zipFile = ZipFile(cache)
    }

    private fun readEntry(name: String): String? {
        return try {
            ensureOpen()
            val entry: ZipEntry = zipFile?.getEntry(name) ?: run {
                AppLog.putDebug("NexFile 缺少条目: $name")
                return null
            }
            zipFile?.getInputStream(entry)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) {
            AppLog.put("NexFile 读 $name 失败\n${e.localizedMessage}", e)
            e.printOnDebug()
            null
        }
    }

    /* ============ 书籍信息 ============ */

    private fun upBookInfo() {
        val manifest = readEntry("manifest.json")
        if (manifest == null) {
            book.intro = "不是有效的 .nex 文件：缺少 manifest.json"
            return
        }
        try {
            val json = JSONObject(manifest)
            val title = json.optString("title", "").trim()
            val author = json.optString("author", "").trim()
            if (title.isNotEmpty()) book.name = title
            if (author.isNotEmpty()) book.author = author
            val intro = json.optString("intro", "").trim()
            if (intro.isNotEmpty()) book.intro = intro
        } catch (e: Exception) {
            AppLog.put("解析 manifest.json 失败\n${e.localizedMessage}", e)
        }
        upBookCover()
    }

    private fun upBookCover(fastCheck: Boolean = false) {
        // .nex 暂不支持封面，留空
    }

    /* ============ 章节列表 ============ */

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        val bookJson = readEntry("book.json")
        if (bookJson == null) {
            AppLog.put("NexFile 缺少 book.json: ${book.bookUrl}")
            return chapterList
        }
        try {
            val json = JSONObject(bookJson)
            val arr = json.optJSONArray("chapters") ?: return chapterList
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val chapter = BookChapter(
                    bookUrl = book.bookUrl,
                    title = o.optString("title", "第 ${i + 1} 章"),
                    url = o.optString("file", "content/ch${i + 1}.html"),
                    index = i
                )
                chapterList.add(chapter)
            }
        } catch (e: Exception) {
            AppLog.put("解析 book.json 失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
        return chapterList
    }

    /* ============ 章节正文 ============ */

    private fun getContent(chapter: BookChapter): String? {
        val raw = readEntry(chapter.url) ?: return null
        val body = extractBody(raw)
        val normalized = normalizeAssetPaths(body)
        return HtmlFormatter.formatKeepImg(normalized)
    }

    private fun extractBody(html: String): String {
        val regex = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1) ?: html
    }

    /**
     * 把 ../assets/img/xxx 这类相对路径规范化为 assets/img/xxx
     */
    private fun normalizeAssetPaths(html: String): String {
        return html.replace(Regex("(?:\\.\\./)+assets/"), "assets/")
    }

    /* ============ 图片 ============ */

    private fun getImage(href: String): InputStream? {
        val cleanHref = href.replace(Regex("^(?:\\.\\./)+"), "")
        return try {
            ensureOpen()
            val entry = zipFile?.getEntry(cleanHref) ?: return null
            zipFile?.getInputStream(entry)
        } catch (e: Exception) {
            AppLog.put("NexFile 读图片 $href 失败\n${e.localizedMessage}", e)
            null
        }
    }

    protected fun finalize() {
        try { zipFile?.close() } catch (_: Throwable) {}
        try { fileDescriptor?.close() } catch (_: Throwable) {}
        // 缓存文件保留，下次打开同一个书时复用，系统空间紧张时会自动清理 cacheDir
    }
}