package com.dsh.codepocket

import com.dsh.codepocket.ai.AiClient
import org.junit.Assert.assertEquals
import org.junit.Test

class AiParsingTest {

    @Test
    fun extractsCodeBlockWithLanguage() {
        val reply = "这是代码：\n\n```python\nprint('hi')\n```\n\n要点：很简单。"
        val blocks = AiClient.extractCodeBlocks(reply)
        assertEquals(1, blocks.size)
        assertEquals("python", blocks[0].language)
        assertEquals("print('hi')", blocks[0].code)
        assertEquals("py", AiClient.extensionFor(blocks[0].language))
    }

    @Test
    fun extractsMultipleBlocksIncludingPlainFence() {
        val reply = "```sh\necho hi\n```\n说明\n```\nplain text\n```"
        val blocks = AiClient.extractCodeBlocks(reply)
        assertEquals(2, blocks.size)
        assertEquals("sh", blocks[0].language)
        assertEquals("sh", AiClient.extensionFor(blocks[0].language))
        assertEquals("", blocks[1].language)
        assertEquals("txt", AiClient.extensionFor(blocks[1].language))
    }

    @Test
    fun keepsMultilineCodeIntact() {
        val reply = "```c\nint main(void) {\n    return 0;\n}\n```"
        val blocks = AiClient.extractCodeBlocks(reply)
        assertEquals(1, blocks.size)
        assertEquals("int main(void) {\n    return 0;\n}", blocks[0].code)
        assertEquals("c", AiClient.extensionFor(blocks[0].language))
    }

    @Test
    fun ignoresTextWithoutCode() {
        assertEquals(0, AiClient.extractCodeBlocks("只有说明文字，没有代码块。").size)
    }

    @Test
    fun mapsLanguagesToExtensions() {
        assertEquals("py", AiClient.extensionFor("python"))
        assertEquals("cpp", AiClient.extensionFor("c++"))
        assertEquals("kt", AiClient.extensionFor("kotlin"))
        assertEquals("md", AiClient.extensionFor("markdown"))
        assertEquals("yml", AiClient.extensionFor("yaml"))
        assertEquals("txt", AiClient.extensionFor("brainfuck"))
    }
}
