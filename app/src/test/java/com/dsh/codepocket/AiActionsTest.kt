package com.dsh.codepocket

import com.dsh.codepocket.ai.AiActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The file-writing protocol is pure text logic, so it is verified here rather than by asking
 * a live model and hoping. Security-relevant cases (path traversal, absolute paths) matter
 * most: this code writes files based on model output.
 */
class AiActionsTest {

    @Test
    fun parsesFileBlockWithPathAndContent() {
        val reply = """
            好的，我给你写一个脚本：

            ```file:tools/hello.py
            print("hi")
            ```

            运行 `python hello.py` 即可。
        """.trimIndent()

        val actions = AiActions.parse(reply)
        assertEquals(1, actions.size)
        assertEquals("tools/hello.py", actions[0].path)
        assertTrue(actions[0].content.contains("print(\"hi\")"))
    }

    @Test
    fun ignoresOrdinaryCodeBlocks() {
        val reply = """
            这是示例（不落盘）：

            ```python
            print("example")
            ```
        """.trimIndent()
        assertTrue(AiActions.parse(reply).isEmpty())
    }

    @Test
    fun parsesMultipleBlocksInOrder() {
        val reply = "```file:a.txt\n1\n```\n\n```file:b/c.txt\n2\n```"
        val actions = AiActions.parse(reply)
        assertEquals(listOf("a.txt", "b/c.txt"), actions.map { it.path })
    }

    @Test
    fun refusesPathsThatEscapeTheWorkspace() {
        assertNull(AiActions.sanitize("../evil.txt"))
        assertNull(AiActions.sanitize("a/../../evil.txt"))
        assertNull(AiActions.sanitize("/etc/passwd"))
        assertNull(AiActions.sanitize("C:/Windows/system32/x.dll"))
        assertNull(AiActions.sanitize(""))
        assertNull(AiActions.sanitize(".hidden"))
    }

    @Test
    fun normalisesHarmlessPaths() {
        assertEquals("hello.py", AiActions.sanitize("./hello.py"))
        assertEquals("hello.py", AiActions.sanitize("hello.py"))
        assertEquals("dir/sub/file.c", AiActions.sanitize("dir//sub/./file.c"))
        assertEquals("dir/file.c", AiActions.sanitize("dir\\file.c"))
    }

    @Test
    fun writesFilesIntoTheWorkspace() {
        val workspace = File(System.getProperty("java.io.tmpdir"), "ai_actions_${System.nanoTime()}")
        try {
            val applied = AiActions.apply(
                workspace,
                listOf(AiActions.WriteAction("notes/a.txt", "hello")),
            )
            assertEquals(1, applied.size)
            assertEquals("notes/a.txt", applied[0].path)
            assertTrue("新建应标记为未覆盖", !applied[0].replaced)
            assertEquals("hello\n", File(workspace, "notes/a.txt").readText())

            // Second write to the same path reports a replacement.
            val again = AiActions.apply(workspace, listOf(AiActions.WriteAction("notes/a.txt", "bye")))
            assertTrue("覆盖应被标记", again[0].replaced)
            assertEquals("bye\n", File(workspace, "notes/a.txt").readText())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun skipsUnsafeActionsInsteadOfFailingTheWholeBatch() {
        val workspace = File(System.getProperty("java.io.tmpdir"), "ai_actions_${System.nanoTime()}")
        try {
            val applied = AiActions.apply(
                workspace,
                listOf(
                    AiActions.WriteAction("../escape.txt", "nope"),
                    AiActions.WriteAction("ok.txt", "yes"),
                ),
            )
            assertEquals("只有安全的那条应被写入", 1, applied.size)
            assertEquals("ok.txt", applied[0].path)
            assertTrue("不得在工作区外创建文件", !File(workspace.parentFile, "escape.txt").exists())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun summaryMentionsEveryFile() {
        val text = AiActions.summary(
            listOf(
                AiActions.Applied("a.py", 10, replaced = false),
                AiActions.Applied("b.py", 20, replaced = true),
            ),
        )
        assertTrue(text.contains("2 个文件"))
        assertTrue(text.contains("a.py"))
        assertTrue(text.contains("b.py"))
        assertTrue(text.contains("已覆盖"))
    }

    @Test
    fun protocolReminderDocumentsTheFence() {
        assertTrue(AiActions.PROTOCOL_REMINDER.contains("```file:"))
        assertTrue(AiActions.PROTOCOL_REMINDER.contains("相对路径"))
    }
}
