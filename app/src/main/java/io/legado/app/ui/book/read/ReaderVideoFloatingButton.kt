package io.legado.app.ui.book.read

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.model.localBook.NexFile
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 阅读界面右上角的“本章视频”悬浮按钮。
 * 只在书的格式是 .nex 且书内存在视频时显示。
 * 点击后弹列表，选一个视频用系统播放器播。
 */
@Composable
fun ReaderVideoFloatingButton(
    book: Book?,
    menuVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var videoList by remember(book?.bookUrl) { mutableStateOf<List<String>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(book?.bookUrl) {
        if (book == null || !book.bookUrl.endsWith(".nex", ignoreCase = true)) {
            videoList = emptyList()
            return@LaunchedEffect
        }
        videoList = withContext(Dispatchers.IO) {
            runCatching { NexFile.listAllVideos(book) }.getOrDefault(emptyList())
        }
    }

    AnimatedVisibility(
        visible = videoList.isNotEmpty() && !menuVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(LegadoTheme.colorScheme.primary.copy(alpha = 0.85f))
                .clickable {
                    if (videoList.size == 1) {
                        playVideoFromNex(context, scope, book, videoList.first())
                    } else {
                        showPicker = true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "本章视频",
                tint = LegadoTheme.colorScheme.onPrimary,
                modifier = Modifier.size(26.dp),
            )
        }
    }

    if (showPicker && book != null) {
        AppAlertDialog(
            show = true,
            onDismissRequest = { showPicker = false },
            title = "选择视频",
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        videoList.forEach { videoPath ->
                            TextButton(
                                onClick = {
                                    showPicker = false
                                    playVideoFromNex(context, scope, book, videoPath)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = videoPath.substringAfterLast('/'),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            },
            confirmText = "关闭",
            onConfirm = { showPicker = false },
            dismissText = null,
            onDismiss = { showPicker = false },
        )
    }
}

private fun playVideoFromNex(
    context: Context,
    scope: CoroutineScope,
    book: Book,
    videoPath: String,
) {
    scope.launch {
        val file = withContext(Dispatchers.IO) {
            runCatching { NexFile.extractVideo(book, videoPath) }.getOrNull()
        }
        if (file == null) {
            context.toastOnUi("视频解压失败")
            return@launch
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileProvider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "选择播放器"))
        } catch (e: Exception) {
            AppLog.put("播放 .nex 视频失败\n${e.localizedMessage}", e)
            context.toastOnUi("播放失败：${e.localizedMessage}")
        }
    }
}