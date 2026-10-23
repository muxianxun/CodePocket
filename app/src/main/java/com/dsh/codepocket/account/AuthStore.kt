package com.dsh.codepocket.account

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Tokens and the cached entitlement, encrypted with an AES key that lives in the Android
 * Keystore, so the ciphertext is useless if the file is copied off the device.
 *
 * Nothing here is a security boundary on its own: anything that costs money (AI calls) is
 * counted on the server. This exists so the UI knows what to show and so the app keeps working
 * offline.
 */
class AuthStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    /** Stable per-installation id. A factory reset produces a new one, hence the device limit. */
    val deviceId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"

    val deviceModel: String
        get() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    var accessToken: String?
        get() = read("access")
        set(value) = write("access", value)

    var refreshToken: String?
        get() = read("refresh")
        set(value) = write("refresh", value)

    /** When the profile below was fetched, used for the offline grace decision. */
    var profileFetchedAt: Long
        get() = read("fetchedAt")?.toLongOrNull() ?: 0L
        set(value) = write("fetchedAt", value.toString())

    var profile: Profile?
        get() = read("profile")?.let { Profile.fromJsonSafely(it) }
        set(value) = write("profile", value?.toJson() ?: "")

    fun clear() {
        prefs.edit().remove("access").remove("refresh").remove("profile").remove("fetchedAt").apply()
    }

    private fun write(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val blob = cipher.iv + encrypted
            prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        }
    }

    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, 12)
            val body = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "codepocket_account"
    }
}

/**
 * Pure decision logic, deliberately free of Android types so it can be unit tested on the JVM.
 *
 * Fail-closed rule: a tier that cannot be understood is treated as free. A paid feature must
 * never be granted because a date failed to parse.
 */
object Entitlement {

    /** How long a cached entitlement keeps working without reaching the server. */
    const val OFFLINE_GRACE_MILLIS = 14L * 24 * 60 * 60 * 1000

    fun isVip(tier: String?, expiresAtIso: String?, nowMillis: Long): Boolean {
        if (tier != "vip") return false
        // The server sends an empty string for a licence without an end date.
        if (expiresAtIso.isNullOrBlank()) return true
        val expires = parseIso(expiresAtIso) ?: return false
        return expires > nowMillis
    }

    fun withinGrace(fetchedAtMillis: Long, nowMillis: Long): Boolean {
        if (fetchedAtMillis <= 0L) return false
        if (nowMillis < fetchedAtMillis) return false // clock moved backwards: do not trust it
        return nowMillis - fetchedAtMillis <= OFFLINE_GRACE_MILLIS
    }

    fun canUseAi(
        tier: String?,
        expiresAtIso: String?,
        usedToday: Int,
        freePerDay: Int,
        nowMillis: Long,
    ): Boolean = isVip(tier, expiresAtIso, nowMillis) || usedToday < freePerDay

    /** Parses the server's Instant.toString(), ignoring fractional seconds. */
    internal fun parseIso(text: String): Long? = runCatching {
        val main = text.trim().substringBefore('.').substringBefore('+').removeSuffix("Z")
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        format.parse(main)?.time
    }.getOrNull()
}