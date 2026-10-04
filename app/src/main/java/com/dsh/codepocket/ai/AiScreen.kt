package com.dsh.codepocket.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.codepocket.Diag
import kotlinx.coroutines.launch
import java.io.File

/** Survives tab switches because it is remembered in AppRoot. */
class AiUiState {
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var showSettings by mutableStateOf(false)
}

@Composable
fun AiScreen(
    settings: AiSettings,
    state: AiUiState,
    modifier: Modifier = Modifier,
    onApplyCode: (CodeBlock) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Needed so the assistant can actually write the files it proposes (the workspace lives
    // under the app's external files dir). Fully qualified to avoid an import.
    val context = androidx.compose.ui.platform.LocalContext.current
    var config by remember { mutableStateOf(settings.load()) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }
    var systemPrompt by remember { mutableStateOf(config.systemPrompt) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    fun send() {
        val text = state.input.trim()
        if (text.isEmpty() || state.busy) return
        // The protocol reminder travels with the request but is NOT shown in the transcript,
        // so the user sees their own words while the model still learns how to create files.
        val history = state.messages + ChatMessage("user", text + AiActions.PROTOCOL_REMINDER)
        state.messages = state.messages + ChatMessage("user", text)
        state.input = ""
        state.busy = true
        state.status = "思考中…"
        scope.launch {
            // Stream the answer: append an empty assistant message and rewrite it as deltas
            // arrive, so text shows up while it is being generated instead of after a long
            // frozen pause.
            state.messages = state.messages + ChatMessage("assistant", "")
            val index = state.messages.size - 1
            var gotAnyDelta = false
            val result = sendStreaming(config, history) { partial ->
                gotAnyDelta = true
                state.messages = state.messages.toMutableList().also {
                    it[index] = ChatMessage("assistant", partial)
                }
                state.status = "生成中… ${partial.length} 字符"
            }
            result
                .onSuccess { reply ->
                    state.messages = state.messages.toMutableList().also {
                        it[index] = ChatMessage("assistant", reply)
                    }
                    state.status = ""
                    Diag.log("ai", "streamed reply chars=${reply.length}")

                    // Act on any file the assistant asked us to create. The blocks stay
                    // visible in the reply, so the user can see what was written.
                    val actions = AiActions.parse(reply)
                    if (actions.isNotEmpty()) {
                        val workspace = File(
                            context.getExternalFilesDir(null) ?: context.filesDir,
                            "workspace",
                        )
                        val applied = AiActions.apply(workspace, actions)
                        if (applied.isNotEmpty()) {
                            state.messages = state.messages + ChatMessage(
                                "assistant",
                                AiActions.summary(applied),
                            )
                            state.status = "已写入 ${applied.size} 个文件"
                        }
                    }
                }
                .onFailure { streamError ->
                    // Some endpoints only speak the non-streaming form, so fall back once
                    // rather than showing the user an error they cannot act on.
                    Diag.log("ai", "stream failed (${streamError.message}); falling back")
                    AiClient().chat(config, history)
                        .onSuccess { reply ->
                            state.messages = state.messages.toMutableList().also {
                                it[index] = ChatMessage("assistant", reply)
                            }
                            state.status = "（该接口不支持流式，已改用整段返回）"
                        }
                        .onFailure { error ->
                            if (!gotAnyDelta) {
                                state.messages = state.messages.filterIndexed { i, _ -> i != index }
                            }
                            state.status = "请求失败：${error.message}"
                            Diag.log("ai", "failed: ${error.message}")
                        }
                }
            state.busy = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ---- header ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    config.apiKey.isBlank() -> "未配置 API Key"
                    state.busy -> "思考中…"
                    state.status.isNotEmpty() -> state.status
                    else -> "${config.model} · ${config.baseUrl}"
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (state.status.startsWith("请求失败")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(
                onClick = { state.showSettings = !state.showSettings },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) { Text(if (state.showSettings) "收起设置" else "设置", fontSize = 12.sp) }
            TextButton(
                onClick = {
                    state.messages = emptyList()
                    state.status = ""
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) { Text("清空", fontSize = 12.sp) }
        }

        if (state.showSettings) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL（OpenAI 兼容）", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（只存在本机）", fontSize = 11.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("系统提示词", fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        config = AiConfig(baseUrl.trim(), apiKey.trim(), model.trim(), systemPrompt)
                        settings.save(config)
                        state.showSettings = false
                        state.status = "设置已保存"
                        Diag.log("ai", "config saved base=${config.baseUrl} model=${config.model} keyLen=${config.apiKey.length}")
                    }) { Text("保存设置") }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // ---- messages ----
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages) { message ->
                MessageBubble(message = message, onApplyCode = onApplyCode)
            }
        }

        // ---- input ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = { state.input = it },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text("让我写点什么…", fontSize = 12.sp, maxLines = 1) },
                keyboardOptions = KeyboardOptions(
                    autoCorrect = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { send() }),
                shape = RoundedCornerShape(8.dp),
            )
            TextButton(
                onClick = { send() },
                enabled = !state.busy,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(40.dp),
            ) { Text(if (state.busy) "…" else "发送", fontSize = 12.sp) }
        }

        if (config.apiKey.isBlank()) {
            Text(
                text = "提示：先点「设置」填 Base URL / 模型 / API Key，Key 只保存在本机。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onApplyCode: (CodeBlock) -> Unit) {
    val isUser = message.role == "user"
    val blocks = remember(message.content) {
        if (isUser) emptyList() else AiClient.extractCodeBlocks(message.content)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isUser) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(10.dp),
            )
            .padding(10.dp),
    ) {
        Text(
            text = if (isUser) "我" else "AI",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message.content,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (blocks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                blocks.forEachIndexed { index, block ->
                    TextButton(
                        onClick = { onApplyCode(block) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text(
                            text = if (blocks.size == 1) {
                                "存为 .${AiClient.extensionFor(block.language)} 并打开"
                            } else {
                                "存第 ${index + 1} 段"
                            },
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
