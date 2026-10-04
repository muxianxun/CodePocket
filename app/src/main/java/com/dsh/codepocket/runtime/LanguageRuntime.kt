package com.dsh.codepocket.runtime

import com.dsh.codepocket.Diag
import com.dsh.codepocket.python.CodePython
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * How a language's code gets turned into something that runs.
 *
 * This distinction is the whole point of the manager: Android 10+ forbids an app from
 * exec()-ing files inside its own data directory, so anything that needs a real process
 * (compilers!) needs a privileged helper, while embedded interpreters and the system
 * dalvikvm do not.
 */
enum class Mechanism(val label: String, val needsPrivilege: Boolean, val explanation: String) {
    // NOTE: `needsPrivilege` reads as "needs privileges", but what it actually records is
    // "needs exec() capability for files in app data" — which the targetSdk 28 exemption
    // already provides, so NO language in this app asks the user for root or Shizuku.
    // The UI therefore labels this "依赖 exec 豁免" rather than "需特权". Renaming the field
    // to requiresExecCapability is the follow-up cleanup.
    EMBEDDED(
        "内嵌解释器",
        false,
        "解释器作为库跑在 App 进程里，不涉及 exec，所以不需要任何权限。",
    ),
    SYSTEM_VM(
        "进程内编译 + 系统 VM",
        false,
        "编译在进程内完成（纯 Java 库），最后用系统自带的 dalvikvm 执行 —— 系统二进制不受 W^X 限制。",
    ),
    PRIVILEGED(
        "需要 exec 权限",
        true,
        "编译器和编译产物都必须被 exec。已通过 targetSdk 28 取得豁免，所以对使用者而言同样无需 root 或 Shizuku。",
    ),
}

enum class LangStatus { READY, NEEDS_DOWNLOAD, PARTIAL, UNAVAILABLE }

data class LanguageSpec(
    val id: String,
    val name: String,
    val versionHint: String,
    val mechanism: Mechanism,
    val extensions: List<String>,
    val note: String,
)

data class LanguageState(
    val spec: LanguageSpec,
    val status: LangStatus,
    /** Human readable evidence for the current status (real checks, not guesses). */
    val detail: String,
    val downloadLabel: String? = null,
    val downloadEnabled: Boolean = false,
    val downloadBlockedReason: String? = null,
)

object LanguageCatalog {

    val CPP = LanguageSpec(
        id = "cpp",
        name = "C++",
        versionHint = "clang (Termux 工具链)",
        mechanism = Mechanism.PRIVILEGED,
        extensions = listOf("cpp", "cc", "cxx", "hpp"),
        note = "编译器与产物都必须 exec，只能走特权执行或降 targetSdk",
    )

    val C = LanguageSpec(
        id = "c",
        name = "C",
        versionHint = "clang (Termux 工具链)",
        mechanism = Mechanism.PRIVILEGED,
        extensions = listOf("c", "h"),
        note = "与 C++ 共用同一套 clang 工具链",
    )

    val PYTHON = LanguageSpec(
        id = "python",
        name = "Python",
        versionHint = "CPython 3.13.9 (Chaquopy)",
        mechanism = Mechanism.EMBEDDED,
        extensions = listOf("py"),
        note = "已内置；Chaquopy 只支持构建期装包，运行时 pip 不可用",
    )

    val JAVA = LanguageSpec(
        id = "java",
        name = "Java",
        versionHint = "Janino + D8 + 系统 dalvikvm",
        mechanism = Mechanism.SYSTEM_VM,
        extensions = listOf("java"),
        note = "编译走进程内库，执行走系统 dalvikvm；字节码需 ≤ Java 17",
    )

    val RUST = LanguageSpec(
        id = "rust",
        name = "Rust",
        versionHint = "rustc + cargo（Termux 包，按需下载）",
        mechanism = Mechanism.PRIVILEGED,
        extensions = listOf("rs"),
        note = "编译器必须被 exec；与 clang 共用同一前缀，rustc 才能找到链接器",
    )

    /** Order shown in the UI: cheapest to use first. */
    val all = listOf(PYTHON, JAVA, RUST, C, CPP)
}

/**
 * Performs real capability checks for every language.
 *
 * Nothing here is simulated: Python asks the embedded interpreter, Java looks for the
 * system VM binary and the bundled compiler jars, C/C++ look for a downloaded toolchain.
 */
class RuntimeManager(private val filesDir: File) {

    private val toolchainDir get() = File(filesDir, "toolchains")

    suspend fun inspectAll(): List<LanguageState> = withContext(Dispatchers.IO) {
        // Driven by the catalog rather than a hand-written list: Rust was missing from the UI
        // for exactly that reason, and a hard-coded list will always drift again.
        LanguageCatalog.all.map { spec ->
            when (spec.id) {
                LanguageCatalog.PYTHON.id -> inspectPython()
                LanguageCatalog.JAVA.id -> inspectJava()
                LanguageCatalog.RUST.id -> inspectRust()
                else -> inspectClang(spec) // C and C++ share the Termux clang toolchain
            }
        }
    }

    /**
     * Rust installs into the same Termux prefix as clang (so rustc can find a linker), but the
     * thing to probe is `rustc`, not `clang`.
     */
    private fun inspectRust(): LanguageState {
        val spec = LanguageCatalog.RUST
        val binDir = File(toolchainDir, "root/usr/bin")
        val names = binDir.listFiles()?.map { it.name }.orEmpty()
        val hasRustc = names.any { it == "rustc" }
        val hasCargo = names.any { it == "cargo" }
        val installedMb = toolchainInstalledMb()
        Diag.log("runtime", "rust: rustc=$hasRustc cargo=$hasCargo installed=${"%.1f".format(installedMb)}MB")

        return LanguageState(
            spec = spec,
            status = if (hasRustc) LangStatus.READY else LangStatus.NEEDS_DOWNLOAD,
            detail = if (hasRustc) {
                "已就绪 · rustc${if (hasCargo) " + cargo" else ""} · 工具链 ${"%.1f".format(installedMb)} MB"
            } else {
                "未安装（首次运行 .rs 时会自动下载）"
            },
            downloadLabel = if (hasRustc) "重新安装 Rust" else "下载并安装 Rust",
            downloadEnabled = true,
            downloadBlockedReason = null,
        )
    }

    private suspend fun inspectPython(): LanguageState {
        val spec = LanguageCatalog.PYTHON
        val env = CodePython.environment()
        val ok = !env.startsWith("Python 不可用")
        Diag.log("runtime", "python: ok=$ok env=$env")
        return LanguageState(
            spec = spec,
            status = if (ok) LangStatus.READY else LangStatus.UNAVAILABLE,
            detail = if (ok) "已就绪 · $env" else env,
        )
    }

    private fun inspectJava(): LanguageState {
        val spec = LanguageCatalog.JAVA
        val vm = listOf(
            "/apex/com.android.art/bin/dalvikvm",
            "/system/bin/dalvikvm",
        ).firstOrNull { File(it).exists() }

        // ECJ and Janino ship as ordinary dependencies, so they live in the APK's dex, not
        // as files on disk — asking the class loader is the only correct probe.
        val hasEcj = runCatching {
            Class.forName("org.codehaus.janino.SimpleCompiler")
        }.isSuccess
        val hasD8 = runCatching {
            Class.forName("com.android.tools.r8.D8")
        }.isSuccess

        val detail = buildString {
            append(if (vm != null) "dalvikvm 可用 ✓" else "找不到 dalvikvm ✗")
            append(" · Janino ").append(if (hasEcj) "✓" else "待接入")
            append(" · D8 ").append(if (hasD8) "✓" else "待接入")
        }
        Diag.log("runtime", "java: vm=$vm janino=$hasEcj d8=$hasD8")

        return LanguageState(
            spec = spec,
            status = when {
                vm == null -> LangStatus.UNAVAILABLE
                hasEcj && hasD8 -> LangStatus.READY
                else -> LangStatus.PARTIAL
            },
            detail = detail,
            downloadLabel = "下载编译器（Janino + D8）",
            downloadEnabled = vm != null && !(hasEcj && hasD8),
            downloadBlockedReason = if (vm == null) "系统缺少 dalvikvm，无法执行字节码" else null,
        )
    }

    private fun inspectC(): LanguageState = inspectClang(LanguageCatalog.C)

    private fun inspectCpp(): LanguageState = inspectClang(LanguageCatalog.CPP)

    /**
     * C and C++ share one downloaded clang toolchain.
     *
     * The probe must look where the installer actually puts things — `toolchains/root/usr/bin`
     * (the .deb payload after the Termux prefix is stripped) — otherwise the card claims
     * "needs download" while a perfectly working compiler sits on disk. That exact mistake
     * was shipped once and caught by comparing against the install log.
     */
    private fun inspectClang(spec: LanguageSpec): LanguageState {
        val binDir = File(toolchainDir, "root/usr/bin")
        val entries = binDir.listFiles()?.map { it.name }.orEmpty()
        val hasCompiler = entries.any { it == "clang" || it.startsWith("clang-") }
        val hasCpp = entries.any { it == "clang++" || it.startsWith("clang++") }

        val relevant = if (spec.id == LanguageCatalog.CPP.id) hasCpp else hasCompiler
        val installedMb = toolchainInstalledMb()
        Diag.log(
            "runtime",
            "${spec.id}: binDir=${binDir.absolutePath} entries=${entries.size} " +
                "clang=$hasCompiler clang++=$hasCpp installed=${"%.1f".format(installedMb)}MB",
        )

        return LanguageState(
            spec = spec,
            status = if (relevant) LangStatus.READY else LangStatus.NEEDS_DOWNLOAD,
            detail = if (relevant) {
                "已就绪 · clang${if (hasCpp) " + clang++" else ""} · 工具链 ${"%.1f".format(installedMb)} MB"
            } else if (hasCompiler || hasCpp) {
                "工具链已下载但缺少${if (spec.id == LanguageCatalog.CPP.id) " clang++" else " clang"}"
            } else {
                "未安装工具链（下载约 83 MB，含 15 个包）"
            },
            downloadLabel = if (relevant) "重新安装工具链" else "下载并安装 clang（约 83 MB）",
            downloadEnabled = true,
            downloadBlockedReason = null,
        )
    }

    /** Bytes actually on disk under toolchains/ (both the unpacked tree and the cache). */
    private fun toolchainInstalledMb(): Double {
        val dir = toolchainDir
        if (!dir.exists()) return 0.0
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return bytes / 1024.0 / 1024.0
    }

    /** Size of an installed toolchain, for the UI. */
    fun toolchainSizeMb(): Double {
        val dir = toolchainDir
        if (!dir.exists()) return 0.0
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return bytes / 1024.0 / 1024.0
    }
}

/**
 * Minimal download helper with progress, used by language packs.
 * Kept here so the manager owns the whole lifecycle: download -> verify -> install.
 */
object Downloader {

    /** @param onProgress (bytesRead, totalBytes) — totalBytes is -1 when unknown. */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            dest.parentFile?.mkdirs()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CodePocket")
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("HTTP ${connection.responseCode}"),
                )
            }
            val total = connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            connection.disconnect()
            Diag.log("runtime", "downloaded $url -> ${dest.name} (${read / 1024} KB)")
            Result.success(dest)
        } catch (t: Throwable) {
            Diag.log("runtime", "download failed: ${t.javaClass.simpleName} ${t.message}")
            Result.failure(t)
        }
    }
}
