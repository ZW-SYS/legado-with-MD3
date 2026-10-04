package io.legado.app.model.localBook

import android.content.Context
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 把多本 .nex 合并成一本。
 *
 * 规则：
 * - 章节按选择的顺序拼接
 * - 章节标题加前缀，格式是「书名 · 原章节标题」，避免同名混淆
 * - 每本书的 assets 独立拷贝，文件名加前缀避免冲突
 * - 书名取第一本的名字 + "（合并）"，作者取第一本的作者
 */
object NexMerger {

    suspend fun merge(
        context: Context,
        books: List<Book>,
        outputTitle: String? = null,
        onProgress: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        if (books.isEmpty()) throw IllegalArgumentException("没有要合并的书")

        val outFile = File(context.cacheDir, "nex_merged_${System.currentTimeMillis()}.nex")

        // 全局累积的章节和资源
        val allChapters = mutableListOf<Pair<String, String>>() // title to bodyHtml
        val allAssets = mutableListOf<Pair<String, ByteArray>>() // path to bytes
        var outTitle = outputTitle ?: "${books.first().name}（合并）"
        var outAuthor = books.first().author
        var assetCounter = 0

        ZipOutputStream(FileOutputStream(outFile).buffered()).use { out ->
            books.forEachIndexed { bookIdx, book ->
                onProgress("读取第 ${bookIdx + 1}/${books.size} 本：${book.name}")
                val tmp = File(appCtx.cacheDir, "nex_merge_in_${System.currentTimeMillis()}_$bookIdx.nex")
                try {
                    val pfd = BookHelp.getBookPFD(book)
                        ?: throw IllegalStateException("无法打开：${book.bookUrl}")
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        FileOutputStream(tmp).use { input.copyTo(it) }
                    }
                    pfd.close()

                    ZipFile(tmp).use { zip ->
                        // 读 book.json
                        val bookJsonEntry = zip.getEntry("book.json")
                        val chapterList = mutableListOf<Pair<String, String>>() // title, file
                        if (bookJsonEntry != null) {
                            val json = JSONObject(
                                zip.getInputStream(bookJsonEntry).bufferedReader().readText()
                            )
                            val arr = json.optJSONArray("chapters") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                chapterList.add(
                                    o.optString("title", "第 ${i + 1} 章") to
                                            o.optString("file", "content/ch${i + 1}.html")
                                )
                            }
                        }

                        // 读每章正文，改资源路径前缀
                        chapterList.forEach { (title, file) ->
                            val entry = zip.getEntry(file) ?: return@forEach
                            var html = zip.getInputStream(entry)
                                .bufferedReader(Charsets.UTF_8).readText()
                            // 取出 body
                            val bodyRegex = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
                            html = bodyRegex.find(html)?.groupValues?.get(1) ?: html

                            // 资源路径改名：assets/xxx → assets/b{idx}_xxx
                            val prefix = "b${bookIdx}_"
                            html = html.replace(
                                Regex("(?:\\.\\./)+assets/([^\"'\\s>]+)")
                            ) { m ->
                                "../assets/${prefix}${m.groupValues[1]}"
                            }

                            allChapters.add("${book.name} · $title" to html)
                        }

                        // 拷贝所有 assets，加前缀
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            if (e.isDirectory) continue
                            if (!e.name.startsWith("assets/")) continue
                            val rest = e.name.removePrefix("assets/")
                            val newPath = "assets/b${bookIdx}_$rest"
                            val bytes = zip.getInputStream(e).readBytes()
                            allAssets.add(newPath to bytes)
                            assetCounter++
                        }
                    }
                } finally {
                    tmp.delete()
                }
            }

            // 开始写合并后的 .nex
            onProgress("正在打包合并结果")

            // manifest.json
            val manifest = JSONObject().apply {
                put("format", "nex")
                put("version", "1.0")
                put("spec_author", "ZW-SYS")
                put("spec_url", "https://github.com/ZW-SYS/legado-with-MD3")
                put("title", outTitle)
                put("author", outAuthor)
                put("language", "zh-CN")
                put("merged_from", books.size)
            }
            putText(out, "manifest.json", manifest.toString(2))

            // book.json
            val chapterArr = JSONArray()
            allChapters.forEachIndexed { i, (title, _) ->
                chapterArr.put(JSONObject().apply {
                    put("id", "ch${i + 1}")
                    put("title", title)
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
            putText(out, "book.json", book.toString(2))

            // style
            putText(out, "style/main.css", NEX_CSS)

            // 每章内容
            allChapters.forEachIndexed { i, (title, body) ->
                val html = """<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>${escapeHtml(title)}</title>
<link rel="stylesheet" href="../style/main.css"></head>
<body>
$body
</body></html>"""
                putText(out, "content/ch${i + 1}.html", html)
            }

            // assets
            allAssets.forEach { (path, bytes) ->
                out.putNextEntry(ZipEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }

        outFile
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
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
blockquote { border-left: 4px solid #3d7dff; margin: 1em 0; padding: 4px 16px; color: #555; background: #f2f6ff; }
"""
}