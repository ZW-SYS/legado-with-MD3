package io.legado.app.ui.main.bookshelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import io.legado.app.data.entities.Book
import io.legado.app.ui.theme.LegadoTheme

/**
 * .nex 编辑器弹窗。
 *
 * - 书架上只有一本 .nex：直接进入编辑界面
 * - 有多本：先弹列表选一本
 * - 一本都没有：提示
 */
@Composable
fun NexEditorHost(
    show: Boolean,
    books: List<BookUiItem>,
    onDismiss: () -> Unit,
    onSave: (book: Book, title: String, author: String, coverUri: Uri?) -> Unit,
) {
    if (!show) return

    val nexBooks = remember(books) {
        books.filter { it.book.bookUrl.endsWith(".nex", ignoreCase = true) }
    }

    var selectedBook by remember { mutableStateOf<Book?>(null) }

    // 多本 → 先选
    if (selectedBook == null && nexBooks.size > 1) {
        BookPickerDialog(
            books = nexBooks,
            onDismiss = onDismiss,
            onPick = { selectedBook = it.book }
        )
        return
    }

    // 一本 → 直接进
    if (selectedBook == null && nexBooks.size == 1) {
        selectedBook = nexBooks.first().book
    }

    // 没有 .nex
    if (selectedBook == null) {
        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .background(
                        LegadoTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(20.dp),
            ) {
                Text("没有可编辑的 .nex 书籍")
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
        return
    }

    val book = selectedBook!!
    var title by remember(book.bookUrl) { mutableStateOf(book.name) }
    var author by remember(book.bookUrl) { mutableStateOf(book.author) }
    var coverUri by remember(book.bookUrl) { mutableStateOf<Uri?>(null) }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) coverUri = uri }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(
                    LegadoTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(16.dp)
                )
                .padding(20.dp),
        ) {
            Text("编辑 .nex", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            // 封面区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFEEEEEE))
                    .clickable { coverPicker.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                if (coverUri != null) {
                    AsyncImage(
                        model = coverUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("点击选择封面")
                        Text(
                            "(不选则保留原封面)",
                            color = Color(0xFF999999),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("书名") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text("作者") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(book, title.trim(), author.trim(), coverUri) },
                ) { Text("保存") }
            }
        }
    }
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