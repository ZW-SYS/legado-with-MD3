package io.legado.app.web

import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.readAvailable
import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.ReplaceRuleController
import io.legado.app.api.controller.RssSourceController
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.model.localBook.LocalBook
import io.legado.app.service.WebService
import io.legado.app.utils.LogUtils
import io.legado.app.utils.stackTraceStr
import io.legado.app.web.socket.BookSearchWebSocket
import io.legado.app.web.socket.BookSourceDebugWebSocket
import io.legado.app.web.socket.RssSourceDebugWebSocket
import io.legado.app.web.utils.AssetsWeb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File

class KtorServer(private val port: Int) {
    private var server: EmbeddedServer<*, *>? = null
    private var wsServer: EmbeddedServer<*, *>? = null
    private val assetsWeb = AssetsWeb("web")

    private val otherSettingsGateway: OtherSettingsGateway
        get() = GlobalContext.get().get()

    fun start() {
        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                gson {
                    setLenient()
                }
            }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
            }

            routing {
                post("/saveBookSource") { handlePost { BookSourceController.saveSource(it) } }
                post("/saveBookSources") { handlePost { BookSourceController.saveSources(it) } }
                post("/deleteBookSources") { handlePost { BookSourceController.deleteSources(it) } }
                post("/saveBook") { handlePost { BookController.saveBook(it) } }
                post("/deleteBook") { handlePost { BookController.deleteBook(it) } }
                post("/saveBookProgress") { handlePost { BookController.saveBookProgress(it) } }
                post("/addLocalBook") {
                    WebService.serve()
                    val multipart = call.receiveMultipart()
                    var fileName: String? = null
                    val tempFile = File(appCtx.cacheDir, "upload_${System.currentTimeMillis()}")
                    try {
                        multipart.forEachPart { part ->
                            when (part) {
                                is PartData.FormItem -> {
                                    if (part.name == "fileName") fileName = part.value
                                }
                                is PartData.FileItem -> {
                                    val channel = part.provider()
                                    tempFile.outputStream().use { output ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            val bytesRead = channel.readAvailable(buffer)
                                            if (bytesRead == -1) break
                                            output.write(buffer, 0, bytesRead)
                                        }
                                    }
                                    if (fileName == null) {
                                        fileName = part.originalFileName
                                    }
                                }
                                else -> {}
                            }
                            part.dispose()
                        }
                        if (fileName != null && tempFile.exists()) {
                            val returnData = withContext(Dispatchers.IO) {
                                kotlin.runCatching {
                                    tempFile.inputStream().use {
                                        val uri = LocalBook.saveBookFile(it, fileName!!)
                                        LocalBook.importFile(uri)
                                        ReturnData().setData(true)
                                    }
                                }.getOrElse {
                                    LogUtils.e(TAG, it.stackTraceStr)
                                    ReturnData().setErrorMsg(it.localizedMessage ?: "Save book error")
                                }
                            }
                            respondReturnData(returnData)
                        } else {
                            call.respond(HttpStatusCode.BadRequest, "Missing fileName or fileData")
                        }
                    } finally {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }
                post("/saveReadConfig") { handlePost { BookController.saveWebReadConfig(it) } }
                post("/saveRssSource") { handlePost { RssSourceController.saveSource(it) } }
                post("/saveRssSources") { handlePost { RssSourceController.saveSources(it) } }
                post("/deleteRssSources") { handlePost { RssSourceController.deleteSources(it) } }
                post("/saveReplaceRule") { handlePost { ReplaceRuleController.saveRule(it) } }
                post("/deleteReplaceRule") { handlePost { ReplaceRuleController.delete(it) } }
                post("/testReplaceRule") { handlePost { ReplaceRuleController.testRule(it) } }

                get("/getBookSource") { handleGet { BookSourceController.getSource(it) } }
                get("/getBookSources") { handleGet { BookSourceController.sources } }
                get("/getBookshelf") { handleGet { BookController.bookshelf } }
                get("/getChapterList") { handleGet { BookController.getChapterList(it) } }
                get("/refreshToc") { handleGet { BookController.refreshToc(it) } }
                get("/getBookContent") { handleGet { BookController.getBookContent(it) } }
                get("/cover") { handleGet { BookController.getCover(it) } }
                get("/image") { handleGet { BookController.getImg(it) } }
                get("/getReadConfig") { handleGet { BookController.getWebReadConfig() } }
                get("/getRssSource") { handleGet { RssSourceController.getSource(it) } }
                get("/getRssSources") { handleGet { RssSourceController.sources } }
                get("/getReplaceRules") { handleGet { ReplaceRuleController.allRules } }

                // ============ 局域网分享：列出可下载的本地书籍 ============
                get("/share") {
                    WebService.serve()
                    try {
                        val html = buildShareListHtml()
                        call.respondText(html, ContentType.Text.Html)
                    } catch (e: Exception) {
                        LogUtils.e(TAG, e.stackTraceStr)
                        call.respondText(
                            "错误：${e.localizedMessage}",
                            status = HttpStatusCode.InternalServerError
                        )
                    }
                }

                // ============ 局域网分享：下载指定文件 ============
                get("/share/file") {
                    WebService.serve()
                    try {
                        val name = call.request.queryParameters["name"]
                        if (name.isNullOrBlank()) {
                            call.respond(HttpStatusCode.BadRequest, "缺少 name 参数")
                            return@get
                        }
                        // 防路径穿越
                        if (name.contains("..") ||
                            name.contains("/") ||
                            name.contains("\\")
                        ) {
                            call.respond(HttpStatusCode.BadRequest, "非法文件名")
                            return@get
                        }
                        val file = findLocalBookFile(name)
                        if (file == null) {
                            call.respond(HttpStatusCode.NotFound, "文件不存在：$name")
                            return@get
                        }
                        call.respondFile(file)
                    } catch (e: Exception) {
                        LogUtils.e(TAG, e.stackTraceStr)
                        call.respondText(e.message ?: "Unknown error")
                    }
                }

                get("{...}") {
                    WebService.serve()
                    var uri = call.request.path()
                    if (uri.endsWith("/")) uri += "index.html"
                    val inputStream = assetsWeb.getInputStream(uri)
                    if (inputStream != null) {
                        inputStream.use { stream ->
                            call.respondOutputStream(ContentType.parse(assetsWeb.getMimeType(uri))) {
                                stream.copyTo(this)
                            }
                        }
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)
    }

    fun startWebSocket(wsPort: Int) {
        wsServer = embeddedServer(CIO, port = wsPort) {
            install(WebSockets)
            routing {
                webSocket("/bookSourceDebug") {
                    BookSourceDebugWebSocket(this).handle()
                }
                webSocket("/rssSourceDebug") {
                    RssSourceDebugWebSocket(this).handle()
                }
                webSocket("/searchBook") {
                    BookSearchWebSocket(this).handle()
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(0, 0)
        wsServer?.stop(0, 0)
    }

    /* ===================== 局域网分享辅助 ===================== */

    private data class ShareItem(val name: String, val size: Long)

    /** 扫描「书籍保存位置」目录，列出可下载的书籍文件 */
    private fun listShareableFiles(): List<ShareItem> {
        val result = mutableListOf<ShareItem>()
        val treeUri = otherSettingsGateway.currentSettings.defaultBookTreeUri?.toUri()
            ?: return result
        val root = DocumentFile.fromTreeUri(appCtx, treeUri) ?: return result
        scanDir(root, result, 0)
        return result.sortedBy { it.name }
    }

    private fun scanDir(dir: DocumentFile, out: MutableList<ShareItem>, depth: Int) {
        if (depth > 3) return
        val children = dir.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                scanDir(child, out, depth + 1)
            } else {
                val name = child.name ?: continue
                if (isShareableBook(name)) {
                    out.add(ShareItem(name, child.length()))
                }
            }
        }
    }

    private fun isShareableBook(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".nex") ||
                lower.endsWith(".epub") ||
                lower.endsWith(".txt") ||
                lower.endsWith(".pdf") ||
                lower.endsWith(".mobi") ||
                lower.endsWith(".azw3")
    }

    /** 在「书籍保存位置」目录里按文件名查找并复制到 cacheDir，返回可读文件 */
    private fun findLocalBookFile(name: String): File? {
        val treeUri = otherSettingsGateway.currentSettings.defaultBookTreeUri?.toUri()
            ?: return null
        val root = DocumentFile.fromTreeUri(appCtx, treeUri) ?: return null
        val found = findFile(root, name, 0) ?: return null
        // respondFile 需要真实路径，先把 SAF 文件复制到 cacheDir
        val cacheFile = File(appCtx.cacheDir, "share_$name")
        if (!cacheFile.exists() || cacheFile.length() != found.length()) {
            appCtx.contentResolver.openInputStream(found.uri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return if (cacheFile.exists() && cacheFile.length() > 0) cacheFile else null
    }

    private fun findFile(dir: DocumentFile, name: String, depth: Int): DocumentFile? {
        if (depth > 3) return null
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                findFile(child, name, depth + 1)?.let { return it }
            } else if (child.name == name) {
                return child
            }
        }
        return null
    }

    private fun buildShareListHtml(): String {
        val files = listShareableFiles()
        val sb = StringBuilder()
        sb.append("""
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>阅读Max · 局域网分享</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 640px; margin: 0 auto; padding: 20px; background: #f5f6f8; color: #222; }
  h1 { font-size: 20px; color: #3d6ef5; }
  .hint { color: #888; font-size: 13px; margin-bottom: 16px; }
  ul { list-style: none; padding: 0; }
  li { background: #fff; border-radius: 10px; margin-bottom: 8px; padding: 12px 14px; display: flex; justify-content: space-between; align-items: center; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
  .name { font-size: 14px; word-break: break-all; }
  .size { font-size: 12px; color: #999; margin-left: 8px; flex-shrink: 0; }
  a { color: #3d6ef5; text-decoration: none; font-weight: 600; font-size: 14px; }
  a:hover { text-decoration: underline; }
  .empty { text-align: center; color: #aaa; padding: 40px 0; }
</style>
</head>
<body>
<h1>阅读Max · 局域网分享</h1>
<div class="hint">同一 Wi-Fi 下的设备可以下载这些书。</div>
""")
        if (files.isEmpty()) {
            sb.append("<div class=\"empty\">暂无可分享的书籍</div>")
        } else {
            sb.append("<ul>")
            for (item in files) {
                sb.append("<li><span class=\"name\">")
                sb.append(escapeHtml(item.name))
                sb.append("</span><span class=\"size\">")
                sb.append(formatSize(item.size))
                sb.append("</span><a href=\"/share/file?name=")
                sb.append(urlEncode(item.name))
                sb.append("\">下载</a></li>")
            }
            sb.append("</ul>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }

    private suspend fun RoutingContext.handlePost(
        block: suspend (String?) -> ReturnData
    ) {
        WebService.serve()
        try {
            val postData = call.receiveText()
            val returnData = block(postData)
            respondReturnData(returnData)
        } catch (e: Exception) {
            LogUtils.e(TAG, e.stackTraceStr)
            call.respondText(e.message ?: "Unknown error")
        }
    }

    private suspend fun RoutingContext.handleGet(
        block: (Map<String, List<String>>) -> ReturnData?
    ) {
        WebService.serve()
        try {
            val parameters = call.queryParameters.entries()
                .associate { it.key to it.value }
            val returnData = block(parameters)
            if (returnData != null) {
                respondReturnData(returnData)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, e.stackTraceStr)
            call.respondText(e.message ?: "Unknown error")
        }
    }

    private suspend fun RoutingContext.respondReturnData(returnData: ReturnData) {
        if (returnData.data is Bitmap) {
            val bitmap = returnData.data as Bitmap
            val outputStream = ByteArrayOutputStream()
            withContext(Dispatchers.IO) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            call.respondBytes(outputStream.toByteArray(), ContentType.Image.PNG)
        } else {
            call.respond(returnData)
        }
    }

    companion object {
        private const val TAG = "KtorServer"
    }
}