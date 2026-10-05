package io.legado.app.ui.main.bookshelf

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * .nex 编辑器入口。
 *
 * - 书架上有多本 .nex：先弹列表选一本
 * - 只有一本：直接进入
 * - 一本都没有：进入新建模式
 *
 * 编辑器本体是 WebView 加载 assets/nex_editor.html。
 */
@Composable
fun NexEditorHost(
    show: Boolean,
    books: List<BookUiItem>,
    onDismiss: () -> Unit,
    onSave: (bookUrl: String?, payload: String) -> Unit,
) {
    if (!show) return

    val nexBooks = remember(books) {
        books.filter { it.book.bookUrl.endsWith(".nex", ignoreCase = true) }
    }

    var selectedUrl by remember(show) { mutableStateOf<String?>(null) }
    var picked by remember(show) { mutableStateOf(false) }

    if (!picked && nexBooks.size > 1) {
        BookPickerDialog(
            books = nexBooks,
            onDismiss = onDismiss,
            onPick = {
                selectedUrl = it.book.bookUrl
                picked = true
            }
        )
        return
    }

    if (!picked && nexBooks.size == 1) {
        selectedUrl = nexBooks.first().book.bookUrl
        picked = true
    }

    if (!picked) {
        picked = true
    }

    NexEditorDialog(
        bookUrl = selectedUrl,
        onSave = onSave,
        onDismiss = onDismiss,
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun NexEditorDialog(
    bookUrl: String?,
    onSave: (bookUrl: String?, payload: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pendingPickType by remember { mutableStateOf<String?>(null) }

    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val type = pendingPickType
        pendingPickType = null
        if (uri == null || type == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = copyUriToCache(context, uri)
            if (path == null) {
                context.toastOnUi("文件导入失败")
                return@launch
            }
            val safePath = path.replace("\\", "\\\\").replace("'", "\\'")
            webViewRef?.evaluateJavascript(
                "window.onMediaPicked && window.onMediaPicked('$safePath', '$type')",
                null
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF12161C))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        @Suppress("DEPRECATION")
                        settings.allowFileAccessFromFileURLs = true
                        @Suppress("DEPRECATION")
                        settings.allowUniversalAccessFromFileURLs = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.loadWithOverviewMode = false
                        settings.useWideViewPort = true
                        settings.setSupportZoom(false)

                        addJavascriptInterface(
                            NexBridge(
                                onPickMedia = { type ->
                                    Handler(Looper.getMainLooper()).post {
                                        pendingPickType = type
                                        val mime = if (type == "video") "video/*" else "image/*"
                                        pickMediaLauncher.launch(mime)
                                    }
                                },
                                onSave = { payload ->
                                    Handler(Looper.getMainLooper()).post {
                                        onSave(bookUrl, payload)
                                    }
                                },
                                onClose = {
                                    Handler(Looper.getMainLooper()).post {
                                        onDismiss()
                                    }
                                }
                            ),
                            "NexBridge"
                        )

                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()

                        loadUrl("file:///android_asset/nex_editor.html")
                    }
                }
            )

            LaunchedEffect(bookUrl) {
                if (bookUrl == null) return@LaunchedEffect
                val content = withContext(Dispatchers.IO) {
                    loadNexForEditor(context, bookUrl)
                }
                if (content == null) {
                    context.toastOnUi("加载失败")
                    return@LaunchedEffect
                }
                delay(500)
                val js = buildString {
                    append("window.loadContent && window.loadContent(")
                    append(jsString(content.title)).append(", ")
                    append(jsString(content.author)).append(", ")
                    append(jsString(content.html)).append(", ")
                    append(content.fontSize)
                    append(")")
                }
                webViewRef?.evaluateJavascript(js, null)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            } catch (_: Throwable) {}
            webViewRef = null
        }
    }
}

private class NexBridge(
    private val onPickMedia: (String) -> Unit,
    private val onSave: (String) -> Unit,
    private val onClose: () -> Unit,
) {
    @JavascriptInterface
    fun pickMedia(type: String) {
        onPickMedia(type)
    }

    @JavascriptInterface
    fun saveContent(payload: String) {
        onSave(payload)
    }

    @JavascriptInterface
    fun closeEditor() {
        onClose()
    }
}

/* ===================== 辅助函数 ===================== */

private suspend fun copyUriToCache(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        try {
            val mime = context.contentResolver.getType(uri) ?: ""
            val ext = when {
                mime.contains("png", true) -> ".png"
                mime.contains("gif", true) -> ".gif"
                mime.contains("webp", true) -> ".webp"
                mime.contains("jpeg", true) || mime.contains("jpg", true) -> ".jpg"
                mime.contains("mp4", true) -> ".mp4"
                mime.contains("webm", true) -> ".webm"
                mime.contains("quicktime", true) || mime.contains("mov", true) -> ".mov"
                else -> ".bin"
            }
            val dir = File(context.cacheDir, "editor_media")
            dir.mkdirs()
            val f = File(dir, "media_${System.currentTimeMillis()}$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(f).use { input.copyTo(it) }
            } ?: return@withContext null
            "file://${f.absolutePath}"
        } catch (_: Exception) {
            null
        }
    }

private data class EditorContent(
    val title: String,
    val author: String,
    val html: String,
    val fontSize: Int,
)

private suspend fun loadNexForEditor(context: Context, bookUrl: String): EditorContent? =
    withContext(Dispatchers.IO) {
        try {
            val book = Book(bookUrl = bookUrl)
            val pfd = BookHelp.getBookPFD(book) ?: return@withContext null
            val tmp = File(context.cacheDir, "nex_edit_load_${System.currentTimeMillis()}.nex")
            try {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    FileOutputStream(tmp).use { input.copyTo(it) }
                }
            } finally {
                try { pfd.close() } catch (_: Throwable) {}
            }

            val extractDir = File(context.cacheDir, "editor_media/book_${bookUrl.hashCode()}")
            if (extractDir.exists()) extractDir.deleteRecursively()
            extractDir.mkdirs()

            var title = "未命名"
            var author = "佚名"
            var fontSize = 16
            val chapterList = mutableListOf<Pair<String, String>>()

            val sb = StringBuilder()

            ZipFile(tmp).use { zip ->
                zip.getEntry("manifest.json")?.let { e ->
                    runCatching {
                        val j = JSONObject(zip.getInputStream(e).bufferedReader().readText())
                        title = j.optString("title", title).ifBlank { title }
                        author = j.optString("author", author).ifBlank { author }
                    }
                }

                zip.getEntry("book.json")?.let { e ->
                    runCatching {
                        val j = JSONObject(zip.getInputStream(e).bufferedReader().readText())
                        j.optJSONObject("settings")?.let { s ->
                            fontSize = s.optInt("defaultFontSize", 16)
                        }
                        val arr = j.optJSONArray("chapters")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                chapterList.add(
                                    o.optString("title", "第 ${i + 1} 章") to
                                            o.optString("file", "content/ch${i + 1}.html")
                                )
                            }
                        }
                    }
                }

                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    if (!e.name.startsWith("assets/")) continue
                    val out = File(extractDir, e.name)
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input ->
                        FileOutputStream(out).use { input.copyTo(it) }
                    }
                }

                val basePath = "file://${extractDir.absolutePath}/"
                chapterList.forEach { (chTitle, file) ->
                    val entry = zip.getEntry(file) ?: return@forEach
                    val raw = zip.getInputStream(entry).bufferedReader().readText()
                    val bodyRegex = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
                    var body = bodyRegex.find(raw)?.groupValues?.get(1) ?: raw
                    body = body.replace(Regex("(?:\\.\\./)+assets/")) { "${basePath}assets/" }
                    sb.append("<h2>").append(escapeHtml(chTitle)).append("</h2>\n")
                    sb.append(body).append("\n")
                }
            }

            tmp.delete()

            EditorContent(
                title = title,
                author = author,
                html = sb.toString(),
                fontSize = fontSize
            )
        } catch (_: Exception) {
            null
        }
    }

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun jsString(s: String): String {
    val escaped = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    return "'$escaped'"
}

@Composable
private fun BookPickerDialog(
    books: List<BookUiItem>,
    onDismiss: () -> Unit,
    onPick: (BookUiItem) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(
                    LegadoTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(16.dp)
                )
                .padding(20.dp),
        ) {
            Text("选择要编辑的 .nex", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.height(360.dp)) {
                items(books, key = { it.book.bookUrl }) { item ->
                    Text(
                        text = item.book.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(item) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) { Text("取消") }
        }
    }
}