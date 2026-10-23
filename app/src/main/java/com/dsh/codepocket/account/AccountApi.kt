package com.dsh.codepocket.account

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class Profile(
    val email: String,
    val verified: Boolean,
    val tier: String,
    val tierExpiresAt: String,
    val deviceId: String,
    val aiUsedToday: Int,
    val aiFreePerDay: Int,
) {
    fun toJson(): String = JSONObject()
        .put("email", email)
        .put("verified", verified)
        .put("tier", tier)
        .put("tierExpiresAt", tierExpiresAt)
        .put("deviceId", deviceId)
        .put("aiUsedToday", aiUsedToday)
        .put("aiFreePerDay", aiFreePerDay)
        .toString()

    companion object {
        fun from(o: JSONObject) = Profile(
            email = o.optString("email"),
            verified = o.optBoolean("verified"),
            tier = o.optString("tier", "free"),
            tierExpiresAt = o.optString("tierExpiresAt"),
            deviceId = o.optString("deviceId"),
            aiUsedToday = o.optInt("aiUsedToday"),
            aiFreePerDay = o.optInt("aiFreePerDay", 20),
        )

        fun fromJsonSafely(text: String): Profile? =
            runCatching { from(JSONObject(text)) }.getOrNull()
    }
}

data class Session(val accessToken: String, val refreshToken: String, val profile: Profile)

/**
 * Talks to the self-hosted auth server over a pinned TLS connection (see PinnedTls).
 *
 * Errors carry the server's machine-readable code so the UI can explain what happened instead
 * of showing a raw HTTP status.
 */
class AccountApi(private val baseUrl: String = PinnedTls.BASE_URL) {

    fun register(email: String, password: String): Result<String> =
        request("/api/v1/auth/register", "POST", JSONObject().put("email", email).put("password", password), null)
            .map { it.optString("message", "验证码已发送") }

    fun resend(email: String): Result<String> =
        request("/api/v1/auth/resend", "POST", JSONObject().put("email", email), null)
            .map { "验证码已重新发送" }

    fun verify(email: String, code: String, store: AuthStore): Result<Session> =
        request(
            "/api/v1/auth/verify",
            "POST",
            JSONObject().put("email", email).put("code", code)
                .put("deviceId", store.deviceId).put("model", store.deviceModel),
            null,
        ).map { toSession(it) }

    fun login(email: String, password: String, store: AuthStore): Result<Session> =
        request(
            "/api/v1/auth/login",
            "POST",
            JSONObject().put("email", email).put("password", password)
                .put("deviceId", store.deviceId).put("model", store.deviceModel),
            null,
        ).map { toSession(it) }

    fun refresh(refreshToken: String): Result<Session> =
        request("/api/v1/auth/refresh", "POST", JSONObject().put("refreshToken", refreshToken), null)
            .map { toSession(it) }

    fun me(accessToken: String): Result<Profile> =
        request("/api/v1/me", "GET", null, accessToken).map { Profile.from(it) }

    /**
     * Redeems a purchased key. The device is taken from the signed token, never from a value the
     * client sends, so a caller cannot pretend to be a different device.
     */
    fun activate(accessToken: String, code: String): Result<Profile> =
        request("/api/v1/activate", "POST", JSONObject().put("code", code), accessToken)
            .map { Profile.from(it) }

    fun logout(refreshToken: String): Result<Unit> =
        request("/api/v1/auth/logout", "POST", JSONObject().put("refreshToken", refreshToken), null)
            .map { }

    private fun toSession(o: JSONObject): Session = Session(
        accessToken = o.getString("accessToken"),
        refreshToken = o.getString("refreshToken"),
        profile = Profile.from(o.getJSONObject("profile")),
    )

    private fun request(
        path: String,
        method: String,
        body: JSONObject?,
        bearer: String?,
    ): Result<JSONObject> = runCatching {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Accept", "application/json")
            if (bearer != null) {
                setRequestProperty("Authorization", "Bearer $bearer")
            }
            (this as? javax.net.ssl.HttpsURLConnection)?.sslSocketFactory = PinnedTls.socketFactory()
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        connection.disconnect()
        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (code !in 200..299) {
            throw ApiFailure(code, json.optString("error", "http_$code"), explain(json))
        }
        json
    }

    /** Turns the server's error code into something a user can act on. */
    private fun explain(json: JSONObject): String = when (json.optString("error")) {
        "bad_email" -> "邮箱格式不正确"
        "weak_password" -> "密码至少 8 位"
        "email_taken" -> "该邮箱已注册，直接登录即可"
        "no_such_user" -> "该邮箱还没注册"
        "code_expired" -> "验证码已过期，请重新获取"
        "bad_code" -> "验证码不正确"
        "no_code" -> "请先获取验证码"
        "bad_credentials" -> "邮箱或密码错误"
        "not_verified" -> "请先完成邮箱验证"
        "too_many_codes" -> "请求太频繁，请稍后再试"
        "too_many_devices" -> "设备数已达上限，请先解绑"
        "quota_exceeded" -> "今日免费额度已用完"
        "code_not_found" -> "激活码不存在，请检查是否输错"
        "code_used_elsewhere" -> "该激活码已绑定到另一台设备，无法在本机使用"
        "no_device" -> "设备标识缺失，请重新登录后再试"
        "server_error" -> "服务器出错了，请稍后重试"
        "bad_request" -> "请求格式错误"
        else -> json.optString("message", "请求失败")
    }
}

class ApiFailure(val status: Int, val code: String, override val message: String) : Exception(message)