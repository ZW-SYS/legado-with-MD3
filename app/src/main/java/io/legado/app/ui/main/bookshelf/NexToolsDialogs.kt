package io.legado.app.ui.main.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.ui.theme.LegadoTheme

/**
 * 合并 .nex 的对话框：多选 + 输入输出书名。
 */
@Composable
fun MergeNexDialog(
    show: Boolean,
    books: List<BookUiItem>,
    onDismiss: () -> Unit,
    onConfirm: (selectedBookUrls: List<String>, outputTitle: String?) -> Unit,
) {
    if (!show) return

    val nexBooks = remember(books) {
        books.filter { it.book.bookUrl.endsWith(".nex", ignoreCase = true) }
    }
    var selectedUrls by remember { mutableStateOf(setOf<String>()) }
    var outputTitle by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(
                    LegadoTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(16.dp)
                )
                .padding(20.dp),
        ) {
            Text("合并 .nex", style = MaterialTheme.typography.titleMedium)
            Text(
                "选择要合并的 .nex（按列表顺序拼接章节）",
                style = MaterialTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))

            if (nexBooks.isEmpty()) {
                Text("没有可合并的 .nex 书籍")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 340.dp),
                ) {
                    items(nexBooks, key = { it.book.bookUrl }) { item ->
                        val url = item.book.bookUrl
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedUrls = if (selectedUrls.contains(url)) {
                                        selectedUrls - url
                                    } else {
                                        selectedUrls + url
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selectedUrls.contains(url),
                                onCheckedChange = { checked ->
                                    selectedUrls = if (checked) {
                                        selectedUrls + url
                                    } else {
                                        selectedUrls - url
                                    }
                                },
                            )
                            Text(
                                text = item.book.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = outputTitle,
                onValueChange = { outputTitle = it },
                label = { Text("合并后的书名（留空自动生成）") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "已选 ${selectedUrls.size} 本",
                style = MaterialTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = selectedUrls.size >= 2,
                    onClick = {
                        // 保持书架上原始顺序
                        val ordered = nexBooks
                            .filter { selectedUrls.contains(it.book.bookUrl) }
                            .map { it.book.bookUrl }
                        onConfirm(ordered, outputTitle.trim().ifEmpty { null })
                    },
                ) { Text("合并") }
            }
        }
    }
}

/**
 * 导出 .nex 的对话框：选一本书 + 选格式。
 */
@Composable
fun ExportNexDialog(
    show: Boolean,
    books: List<BookUiItem>,
    onDismiss: () -> Unit,
    onConfirm: (bookUrl: String, format: String) -> Unit,
) {
    if (!show) return

    val nexBooks = remember(books) {
        books.filter { it.book.bookUrl.endsWith(".nex", ignoreCase = true) }
    }
    var selectedUrl by remember { mutableStateOf<String?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("epub") }

    if (selectedUrl == null && nexBooks.size == 1) {
        selectedUrl = nexBooks.first().book.bookUrl
        selectedName = nexBooks.first().book.name
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(
                    LegadoTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(16.dp)
                )
                .padding(20.dp),
        ) {
            Text("导出 .nex", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            if (nexBooks.isEmpty()) {
                Text("没有可导出的 .nex 书籍")
            } else {
                Text("选择要导出的书：", style = MaterialTheme.typography.bodySmall)
                LazyColumn(
                    modifier = Modifier.heightIn(max = 240.dp),
                ) {
                    items(nexBooks, key = { it.book.bookUrl }) { item ->
                        val url = item.book.bookUrl
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedUrl = url
                                    selectedName = item.book.name
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedUrl == url,
                                onClick = {
                                    selectedUrl = url
                                    selectedName = item.book.name
                                },
                            )
                            Text(item.book.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("导出格式：", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = format == "epub",
                        onClick = { format = "epub" },
                    )
                    Text("EPUB")
                    Spacer(Modifier.width(16.dp))
                    RadioButton(
                        selected = format == "txt",
                        onClick = { format = "txt" },
                    )
                    Text("TXT")
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = selectedUrl != null,
                    onClick = {
                        val url = selectedUrl ?: return@Button
                        onConfirm(url, format)
                    },
                ) { Text("导出") }
            }
        }
    }
}