package com.dsh.codepocket.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.codepocket.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Survives tab switches because it is remembered in AppRoot, like AiUiState. */
class AccountUiState {
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var code by mutableStateOf("")
    var activationCode by mutableStateOf("")
    /** "login" | "verify" | "signedIn" */
    var stage by mutableStateOf("login")
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var profile by mutableStateOf<Profile?>(null)
}

@Composable
fun AccountScreen(state: AccountUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AuthStore(context) }
    val api = remember { AccountApi() }

    // Restore a previous session once, so a cold start does not ask for the password again.
    remember {
        if (state.profile == null) {
            state.profile = store.profile
            state.stage = if (store.refreshToken != null) "signedIn" else "login"
        }
        true
    }

    fun fail(t: Throwable) {
        // Raw Java exceptions used to land on the screen verbatim ("Hostname ... not verified").
        // Anything the user cannot act on is translated; the original still goes to Diag.
        state.status = when (t) {
            is ApiFailure -> t.message ?: "请求失败"
            is java.net.SocketTimeoutException -> "连接服务器超时，请检查网络"
            is java.net.UnknownHostException -> "无法解析服务器地址"
            is java.net.ConnectException -> "连不上服务器，请稍后重试"
            is javax.net.ssl.SSLException -> "安全连接失败（证书校验未通过）"
            else -> t.message ?: t.javaClass.simpleName
        }
        Diag.log("account", "failed: ${t.message}")
    }

    fun persist(session: Session) {
        store.accessToken = session.accessToken
        store.refreshToken = session.refreshToken
        store.profile = session.profile
        store.profileFetchedAt = System.currentTimeMillis()
        state.profile = session.profile
        state.stage = "signedIn"
        state.password = ""
        state.status = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("我的账号", fontSize = 20.sp)
        Text(
            "用于 VIP 权益、AI 配额与设备管理。会员绑定在设备上：一个激活码只能激活一台设备。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state.stage) {
            "login" -> {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = { state.email = it },
                    label = { Text("邮箱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { state.password = it },
                    label = { Text("密码（至少 8 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !state.busy,
                        onClick = {
                            val email = state.email.trim()
                            val password = state.password
                            if (email.isBlank() || password.length < 8) {
                                state.status = "请填写邮箱和至少 8 位密码"
                                return@Button
                            }
                            state.busy = true
                            state.status = "登录中…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { api.login(email, password, store) }
                                state.busy = false
                                result.onSuccess { persist(it) }.onFailure { fail(it) }
                            }
                        },
                    ) { Text("登录") }
                    OutlinedButton(
                        enabled = !state.busy,
                        onClick = {
                            val email = state.email.trim()
                            val password = state.password
                            if (email.isBlank() || password.length < 8) {
                                state.status = "请填写邮箱和至少 8 位密码"
                                return@OutlinedButton
                            }
                            state.busy = true
                            state.status = "注册中…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { api.register(email, password) }
                                state.busy = false
                                result
                                    .onSuccess {
                                        state.stage = "verify"
                                        state.status = "验证码已发到 ${state.email}，请查看邮箱"
                                    }
                                    .onFailure { fail(it) }
                            }
                        },
                    ) { Text("注册") }
                }
            }

            "verify" -> {
                Text("验证码已发到 ${state.email}", fontSize = 13.sp)
                OutlinedTextField(
                    value = state.code,
                    onValueChange = { state.code = it },
                    label = { Text("邮箱验证码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !state.busy,
                        onClick = {
                            state.busy = true
                            state.status = "验证中…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    api.verify(state.email.trim(), state.code.trim(), store)
                                }
                                state.busy = false
                                result.onSuccess { persist(it) }.onFailure { fail(it) }
                            }
                        },
                    ) { Text("验证并登录") }
                    OutlinedButton(
                        enabled = !state.busy,
                        onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { api.resend(state.email.trim()) }
                                state.status = result.getOrElse { "重发失败：${it.message}" }
                            }
                        },
                    ) { Text("重发验证码") }
                    OutlinedButton(
                        enabled = !state.busy,
                        onClick = { state.stage = "login" },
                    ) { Text("返回") }
                }
            }

            else -> {
                val profile = state.profile
                Text("邮箱：${profile?.email ?: "-"}", fontSize = 14.sp)
                Text(
                    "本机设备号：${profile?.deviceId?.take(12) ?: store.deviceId.take(12)}…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val now = System.currentTimeMillis()
                val vip = Entitlement.isVip(profile?.tier, profile?.tierExpiresAt, now)
                Text(
                    if (vip) {
                        "本机会员：VIP ✓ " + if (profile?.tierExpiresAt.isNullOrBlank()) "（永久）"
                        else "（到期 ${profile?.tierExpiresAt?.take(10)}）"
                    } else {
                        "本机会员：免费版"
                    },
                    fontSize = 14.sp,
                )
                val cached = Entitlement.withinGrace(store.profileFetchedAt, now)
                Text(
                    "AI 今日已用 ${profile?.aiUsedToday ?: 0} / ${profile?.aiFreePerDay ?: 20}" +
                        if (cached) "" else "（离线超过 14 天，请联网刷新）",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Buying is not wired up yet (the payment channel is still being chosen), but
                // redeeming already works, so a key bought anywhere can be activated here.
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.activationCode,
                    onValueChange = { state.activationCode = it },
                    label = { Text("激活码 CP-XXXXX-XXXXX-XXXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !state.busy && state.activationCode.isNotBlank(),
                        onClick = {
                            val key = state.activationCode.trim()
                            state.busy = true
                            state.status = "激活中…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    api.activate(store.accessToken ?: "", key)
                                }
                                state.busy = false
                                result
                                    .onSuccess { updated ->
                                        store.profile = updated
                                        store.profileFetchedAt = System.currentTimeMillis()
                                        state.profile = updated
                                        state.activationCode = ""
                                        state.status = "激活成功，本机已是 VIP"
                                    }
                                    .onFailure { fail(it) }
                            }
                        },
                    ) { Text("激活") }
                    OutlinedButton(onClick = { state.status = "购买通道即将开放，敬请期待" }) {
                        Text("购买激活码")
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        enabled = !state.busy,
                        onClick = {
                            scope.launch {
                                state.busy = true
                                val result = withContext(Dispatchers.IO) {
                                    val token = store.accessToken
                                    if (token == null) Result.failure(ApiFailure(0, "no_token", "未登录"))
                                    else api.me(token)
                                }
                                state.busy = false
                                result
                                    .onSuccess { fresh ->
                                        store.profile = fresh
                                        store.profileFetchedAt = System.currentTimeMillis()
                                        state.profile = fresh
                                        state.status = "已刷新"
                                    }
                                    .onFailure { fail(it) }
                            }
                        },
                    ) { Text("刷新状态") }
                    OutlinedButton(
                        enabled = !state.busy,
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    store.refreshToken?.let { api.logout(it) }
                                }
                                store.clear()
                                state.profile = null
                                state.stage = "login"
                                state.status = "已退出登录"
                            }
                        },
                    ) { Text("退出登录") }
                }
            }
        }

        if (state.status.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(state.status, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}