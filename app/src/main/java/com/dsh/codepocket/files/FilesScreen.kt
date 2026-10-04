package com.dsh.codepocket.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.codepocket.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FilesScreen(
    repo: FileRepository,
    modifier: Modifier = Modifier,
    onOpenFile: (File) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var currentDir by remember { mutableStateOf(repo.root) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var reloadToken by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }

    var showNewFile by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }

    LaunchedEffect(currentDir, reloadToken) {
        entries = withContext(Dispatchers.IO) {
            runCatching { repo.list(currentDir) }.getOrElse {
                message = "读取目录失败：${it.message}"
                emptyList()
            }
        }
    }

    fun refresh() {
        reloadToken += 1
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ---- toolbar ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { repo.parentOf(currentDir)?.let { currentDir = it } },
                enabled = !repo.isRoot(currentDir),
                modifier = Modifier.size(34.dp),
            ) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "上级目录")
            }
            Text(
                text = repo.displayPath(currentDir),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            IconButton(onClick = { showNewFile = true }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.NoteAdd, contentDescription = "新建文件")
            }
            IconButton(onClick = { showNewFolder = true }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "新建文件夹")
            }
            IconButton(onClick = { refresh() }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }

        if (message.isNotEmpty()) {
            Text(
                text = message,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "空目录\n用右下角按钮新建文件，或在终端里创建",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(entries, key = { it.file.absolutePath }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isDirectory) {
                                    currentDir = entry.file
                                } else {
                                    onOpenFile(entry.file)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                            contentDescription = null,
                            tint = if (entry.isDirectory) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.file.name,
                                fontSize = 14.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = if (entry.isDirectory) {
                                    "目录"
                                } else {
                                    "${FileRepository.formatSize(entry.size)} · ${FileRepository.formatTime(entry.modified)}"
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { menuFor = entry },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = menuFor?.file?.absolutePath == entry.file.absolutePath,
                                onDismissRequest = { menuFor = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    onClick = {
                                        renameTarget = entry
                                        menuFor = null
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除") },
                                    onClick = {
                                        deleteTarget = entry
                                        menuFor = null
                                    },
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }

    if (showNewFile) {
        NameInputDialog(
            title = "新建文件",
            initial = "untitled.txt",
            onDismiss = { showNewFile = false },
            onConfirm = { name ->
                showNewFile = false
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        runCatching { repo.createFile(currentDir, name) }.getOrNull()
                    }
                    if (created != null) {
                        Diag.log("files", "created file ${created.name}")
                        refresh()
                        onOpenFile(created)
                    } else {
                        message = "创建失败：$name"
                    }
                }
            },
        )
    }

    if (showNewFolder) {
        NameInputDialog(
            title = "新建文件夹",
            initial = "new_folder",
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                showNewFolder = false
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { repo.createDirectory(currentDir, name) } }
                    Diag.log("files", "created dir $name")
                    refresh()
                }
            },
        )
    }

    renameTarget?.let { target ->
        NameInputDialog(
            title = "重命名",
            initial = target.file.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                scope.launch {
                    val renamed = withContext(Dispatchers.IO) {
                        runCatching { repo.rename(target.file, name) }.getOrNull()
                    }
                    if (renamed == null) message = "重命名失败（可能已存在同名文件）" else message = ""
                    refresh()
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除") },
            text = {
                Text(
                    if (target.isDirectory) {
                        "确定删除目录「${target.file.name}」及其中所有内容？"
                    } else {
                        "确定删除文件「${target.file.name}」？"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val victim = target.file
                    deleteTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { repo.delete(victim) } }
                        Diag.log("files", "deleted ${victim.name}")
                        refresh()
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
