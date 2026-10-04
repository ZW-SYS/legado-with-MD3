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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 编辑 .nex 文件：改书名、作者，换封面。
 *
 * 做法：把原 zip 全部条目复制一遍，跳过旧 manifest.json 和旧封面，
 * 顺手写入新的 manifest.json 和新封面，最后覆盖回原文件。
 */
object NexEditor {

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

        // 1. 原文件读到临时文件（PFD 不方便重复读）
        onProgress("读取 .nex")
        val pfd = BookHelp.getBookPFD(book)
            ?: throw IllegalStateException("无法打开 .nex 文件：${book.bookUrl}")
        val tmpIn = File(appCtx.cacheDir, "nex_edit_in_${System.currentTimeMillis()}.nex")
        FileInputStream(pfd.fileDescriptor).use { input ->
            FileOutputStream(tmpIn).use { input.copyTo(it) }
        }
        pfd.close()

        val tmpOut = File(appCtx.cacheDir, "nex_edit_out_${System.currentTimeMillis()}.nex")

        // 2. 决定新封面路径
        var coverAssetPath: String? = null
        if (newCoverUri != null) {
            val ext = guessCoverExt(context, newCoverUri)
            coverAssetPath = "assets/cover_${System.currentTimeMillis()}$ext"
        }

        // 3. 重写 zip
        onProgress("写入 .nex")
        ZipFile(tmpIn).use { zip ->
            ZipOutputStream(FileOutputStream(tmpOut).buffered()).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    // 跳过旧 manifest 和旧封面
                    if (name == "manifest.json") continue
                    if (name.startsWith("assets/cover")) continue

                    out.putNextEntry(ZipEntry(name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }

                // 写新 manifest
                val manifest = JSONObject().apply {
                    put("format", "nex")
                    put("version", "1.0")
                    put("spec_author", "ZW-SYS")
                    put("spec_url", "https://github.com/ZW-SYS/legado-with-MD3")
                    put("title", title)
                    put("author", author)
                    put("language", "zh-CN")
                    if (coverAssetPath != null) put("cover", coverAssetPath)
                }
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                out.closeEntry()

                // 写新封面
                if (coverAssetPath != null && newCoverUri != null) {
                    context.contentResolver.openInputStream(newCoverUri)?.use { input ->
                        out.putNextEntry(ZipEntry(coverAssetPath))
                        input.copyTo(out)
                        out.closeEntry()
                    }
                }
            }
        }

        // 4. 覆盖回原文件
        onProgress("保存")
        val uri = book.bookUrl.toUri()
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: FileOutputStream(File(uri.path!!))
        output.use { out ->
            tmpOut.inputStream().use { it.copyTo(out) }
        }

        tmpIn.delete()
        tmpOut.delete()

        // 5. 更新书架记录
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

        // 清掉 NexFile 缓存，下次打开重新解析
        NexFile.clear()

        book
    }

    private fun guessCoverExt(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri) ?: ""
        return when {
            mime.contains("png", true) -> ".png"
            mime.contains("webp", true) -> ".webp"
            mime.contains("gif", true) -> ".gif"
            else -> ".jpg"
        }
    }
}