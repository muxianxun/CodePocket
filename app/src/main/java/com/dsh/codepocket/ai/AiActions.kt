package com.dsh.codepocket.ai

import com.dsh.codepocket.Diag
import java.io.File

/**
 * Lets the assistant actually *do* something instead of only printing code: it can create and
 * overwrite files in the workspace.
 *
 * ## Why a fenced block and not OpenAI tool-calling
 *
 * This app talks to arbitrary "OpenAI-compatible" endpoints. Plenty of them implement
 * `/chat/completions` but **not** `tools` / `tool_calls`, and a failed tool call is invisible
 * to the user. A fenced block with a `file:` info string works with every endpoint, survives
 * streaming, is trivially parseable, and — crucially — is *visible in the transcript*, so the
 * user can see exactly what was written before trusting it.
 *
 *     ```file:hello.py
 *     print("hi")
 *     ```
 *
 * Paths are treated as hostile input: absolute paths, `..`, drive letters, dotfiles and
 * anything that resolves outside the workspace are rejected.
 */
object AiActions {

    data class WriteAction(val path: String, val content: String)

    /** What actually happened on disk, for the transcript. */
    data class Applied(val path: String, val chars: Int, val replaced: Boolean)

    /**
     * The instruction appended to every outgoing user message. Kept short so it does not
     * crowd out the user's own words, and repeated per turn because the system prompt may be
     * edited by the user at any time.
     */
    const val PROTOCOL_REMINDER = """

---
（应用内提示，请勿复述：你运行在一个手机 IDE 里，可以**直接创建文件**。
要创建或覆盖文件，用信息串为 file:相对路径 的代码块，例如：

```file:hello.py
print("hi")
```

路径相对于工作区，不要用绝对路径或 ..。普通讲解用的代码块（不带 file:）只显示、不落盘。
写完文件后，用一两句话说明你创建了什么即可。）
"""

    private val FENCE = Regex("```file:([^\\n`]+)\\n([\\s\\S]*?)```")

    /** Extracts every `file:` block from a reply, in order. Invalid paths are skipped. */
    fun parse(reply: String): List<WriteAction> =
        FENCE.findAll(reply).mapNotNull { match ->
            val path = sanitize(match.groupValues[1]) ?: return@mapNotNull null
            WriteAction(path, match.groupValues[2])
        }.toList()

    /**
     * @return a safe relative path, or null when the input must be refused.
     */
    fun sanitize(rawPath: String): String? {
        val unified = rawPath.replace('\\', '/').trim()
        // Refuse absolute paths instead of quietly rewriting them. The first version did
        // `.removePrefix("/")`, which turned "/etc/passwd" into "etc/passwd" — silently
        // "helping" a model that had ignored the contract. The unit test caught it.
        if (unified.startsWith("/")) return null
        if (unified.contains(':')) return null // drive letters, schemes, "file:" leftovers
        val cleaned = unified.removePrefix("./")
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        val name = parts.last()
        if (name.isEmpty() || name.startsWith(".")) return null // no dotfiles
        return parts.joinToString("/")
    }

    /**
     * Writes the actions into [workspace]. Every target is re-checked after canonicalisation
     * so a symlink or a clever path cannot escape the workspace.
     */
    fun apply(workspace: File, actions: List<WriteAction>): List<Applied> {
        val applied = mutableListOf<Applied>()
        workspace.mkdirs()
        for (action in actions) {
            val safe = sanitize(action.path)
            if (safe == null) {
                Diag.log("ai", "refused unsafe path: ${action.path}")
                continue
            }
            val target = File(workspace, safe)
            val root = workspace.canonicalFile
            if (!target.canonicalFile.path.startsWith(root.path)) {
                Diag.log("ai", "refused out-of-workspace path: $safe")
                continue
            }
            val replaced = target.exists()
            target.parentFile?.mkdirs()
            val text = if (action.content.endsWith("\n")) action.content else action.content + "\n"
            target.writeText(text)
            Diag.log("ai", "wrote $safe (${text.length} chars, replaced=$replaced)")
            applied += Applied(safe, text.length, replaced)
        }
        return applied
    }

    /** Human-readable summary appended to the chat after files were written. */
    fun summary(applied: List<Applied>): String = buildString {
        append("已写入 ").append(applied.size).append(" 个文件：\n")
        for (item in applied) {
            append("· ").append(item.path)
                .append("（").append(item.chars).append(" 字符")
                .append(if (item.replaced) "，已覆盖" else "，新建").append("）\n")
        }
        append("去「文件」页就能打开它们。")
    }
}
