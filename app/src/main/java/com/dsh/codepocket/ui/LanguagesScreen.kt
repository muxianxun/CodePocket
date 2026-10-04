package com.dsh.codepocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.codepocket.Diag
import com.dsh.codepocket.runtime.JavaPipeline
import com.dsh.codepocket.runtime.LangStatus
import com.dsh.codepocket.runtime.LanguageCatalog
import com.dsh.codepocket.runtime.LanguageState
import com.dsh.codepocket.runtime.ClangToolchain
import com.dsh.codepocket.runtime.NativeToolchain
import com.dsh.codepocket.runtime.RustToolchain
import com.dsh.codepocket.runtime.TermuxRepo
import com.dsh.codepocket.runtime.RuntimeManager
import kotlinx.coroutines.launch

/**
 * Language runtime manager: shows what each language needs, what is already available,
 * and offers an on-demand download for the parts that are not bundled.
 */
@Composable
fun LanguagesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { RuntimeManager(context.filesDir) }
    val scope = rememberCoroutineScope()
    var states by remember { mutableStateOf<List<LanguageState>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var runningId by remember { mutableStateOf<String?>(null) }
    var outputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun refresh() {
        scope.launch {
            loading = true
            message = null
            states = manager.inspectAll()
            loading = false
            Diag.log("runtime", "inspect done: " + states.joinToString { "${it.spec.id}=${it.status}" })
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val noPriv = states.count { !it.spec.mechanism.needsPrivilege }
    val priv = states.count { it.spec.mechanism.needsPrivilege }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("语言运行时管理器", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Android 10+ 禁止 App 执行自己数据目录里的文件，所以每种语言的接入方式不同：" +
                        "解释器可以内嵌进进程；编译器必须真正被 exec，已用 targetSdk 28 的豁免解决。" +
                        "四种语言都不需要 root 或 Shizuku。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge("免权限 $noPriv 项", Color(0xFF2E7D32))
                    Badge("依赖 exec 豁免 $priv 项", Color(0xFFB26500))
                }
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        Text("正在检测…", fontSize = 11.sp)
                    }
                }
                TextButton(onClick = { refresh() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("重新检测全部", fontSize = 12.sp)
                }
            }
        }

        states.forEach { state ->
            val id = state.spec.id
            val isRust = id == LanguageCatalog.RUST.id
            val isNative = id == LanguageCatalog.C.id || id == LanguageCatalog.CPP.id || isRust
            val label = when {
                id == LanguageCatalog.JAVA.id -> "编译并运行示例"
                isNative -> "检测执行权限"
                else -> null
            }
            LanguageCard(
                state = state,
                selfTestLabel = label,
                selfTestRunning = runningId == id,
                selfTestOutput = outputs[id],
                onSelfTest = label?.let {
                    {
                        scope.launch {
                            runningId = id
                            outputs = outputs - id
                            val result = if (isNative) {
                                NativeToolchain.execCapability(context)
                            } else {
                                JavaPipeline.selfTest(context.filesDir)
                            }
                            outputs = outputs + (id to result)
                            runningId = null
                            states = manager.inspectAll()
                        }
                    }
                },
                onDownload = {
                    if (isNative) {
                        scope.launch {
                            runningId = id
                            outputs = outputs + (id to "准备中…")
                            val result = if (isRust) {
                                // Rust has no demo compile here: the install is the whole job.
                                runCatching {
                                    val rustc = RustToolchain.install(context) { progress ->
                                        outputs = outputs + (id to progress)
                                    }
                                    "Rust 已就绪: ${rustc.absolutePath}"
                                }.getOrElse { "Rust 安装失败：${it.message}" }
                            } else {
                                // Full flow: resolve the closure, download ~83 MB, unpack it,
                                // then compile and run a C and a C++ program with clang.
                                ClangToolchain.installAndRun(context) { progress ->
                                    outputs = outputs + (id to progress)
                                }
                            }
                            outputs = outputs + (id to result)
                            runningId = null
                            states = manager.inspectAll()
                        }
                    } else {
                        message = state.downloadBlockedReason
                            ?: "下载通道已就绪，等待该语言的工具链地址接入"
                    }
                },
            )
        }

        message?.let {
            Text(
                "· $it",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Text(
            "工具链占用：" + String.format(java.util.Locale.US, "%.1f MB", manager.toolchainSizeMb()),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LanguageCard(
    state: LanguageState,
    onDownload: () -> Unit,
    selfTestLabel: String? = null,
    selfTestRunning: Boolean = false,
    selfTestOutput: String? = null,
    onSelfTest: (() -> Unit)? = null,
) {
    val spec = state.spec
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "  " + spec.extensions.joinToString("/") { ".$it" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // weight(1f) takes only the *remaining* width; fillMaxWidth() would push
                // the status chip off the card entirely.
                Spacer(Modifier.weight(1f))
                StatusChip(state.status)
            }

            Text(spec.versionHint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(
                    // Not "needs privilege": the exec requirement is already covered for the
                    // user by the targetSdk 28 exemption.
                    if (spec.mechanism.needsPrivilege) "依赖 exec 豁免" else "免权限",
                    if (spec.mechanism.needsPrivilege) Color(0xFFB26500) else Color(0xFF2E7D32),
                )
                Badge(spec.mechanism.label, Color(0xFF37474F))
            }

            Text(state.detail, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

            Text(
                spec.mechanism.explanation,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.downloadLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onDownload,
                        enabled = state.downloadEnabled,
                    ) {
                        Text(state.downloadLabel, fontSize = 11.sp)
                    }
                }
                state.downloadBlockedReason?.let {
                    Text("受限：$it", fontSize = 10.sp, color = Color(0xFFB26500))
                }
            }

            if (selfTestLabel != null && onSelfTest != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSelfTest, enabled = !selfTestRunning) {
                        Text(if (selfTestRunning) "运行中…" else selfTestLabel, fontSize = 11.sp)
                    }
                    if (selfTestRunning) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
                selfTestOutput?.let { output ->
                    Text(
                        text = output.trim().ifBlank { "（无输出）" },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = Color.White,
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatusChip(status: LangStatus) {
    val (label, color) = when (status) {
        LangStatus.READY -> "已就绪" to Color(0xFF2E7D32)
        LangStatus.PARTIAL -> "部分可用" to Color(0xFFB26500)
        LangStatus.NEEDS_DOWNLOAD -> "需下载" to Color(0xFF1565C0)
        LangStatus.UNAVAILABLE -> "不可用" to Color(0xFFB00020)
    }
    Badge(label, color)
}
