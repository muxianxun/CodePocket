package com.dsh.codepocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.dsh.codepocket.ai.AiScreen
import com.dsh.codepocket.ai.AiSettings
import com.dsh.codepocket.ai.AiUiState
import com.dsh.codepocket.editor.EditorScreen
import com.dsh.codepocket.files.FileRepository
import com.dsh.codepocket.files.FilesScreen
import com.dsh.codepocket.terminal.TerminalScreen
import com.dsh.codepocket.ui.LanguagesScreen
import com.dsh.codepocket.ui.theme.CodePocketTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Diag + Python are initialised by CodePocketApp; clearing here would wipe the
        // Python.start result that is useful evidence for the very first launch.
        Diag.log("ui", "MainActivity.onCreate")
        setContent {
            CodePocketTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

enum class AppTab(val label: String, val icon: ImageVector) {
    FILES("文件", Icons.Filled.Folder),
    EDITOR("编辑", Icons.Filled.Edit),
    TERMINAL("终端", Icons.Filled.Terminal),
    LANGS("语言", Icons.Filled.Memory),
    AI("AI", Icons.Filled.AutoAwesome),
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(AppTab.TERMINAL) }

    // Workspace: app external files dir — no permission needed, still reachable via
    // adb pull /sdcard/Android/data/com.dsh.codepocket/files/workspace
    val workspace = remember {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, "workspace").apply { mkdirs() }
    }
    val repo = remember { FileRepository(workspace) }
    val aiSettings = remember { AiSettings(context) }
    val aiState = remember { AiUiState() }

    var openFile by remember { mutableStateOf<File?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, fontSize = 11.sp) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                AppTab.FILES -> FilesScreen(
                    repo = repo,
                    onOpenFile = { file ->
                        openFile = file
                        tab = AppTab.EDITOR
                    },
                )

                AppTab.EDITOR -> EditorScreen(
                    repo = repo,
                    file = openFile,
                )

                AppTab.TERMINAL -> TerminalScreen(workDir = workspace.absolutePath)

                AppTab.LANGS -> LanguagesScreen()

                AppTab.AI -> AiScreen(
                    settings = aiSettings,
                    state = aiState,
                    onApplyCode = { block ->
                        val extension = com.dsh.codepocket.ai.AiClient.extensionFor(block.language)
                        val name = "ai_" +
                            SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date()) +
                            "." + extension
                        val target = File(workspace, name)
                        repo.write(target, block.code + "\n")
                        openFile = target
                        tab = AppTab.EDITOR
                        Diag.log(
                            "ai",
                            "code saved to ${target.name} lang=${block.language} chars=${block.code.length}",
                        )
                    },
                )
            }
        }
    }
}
