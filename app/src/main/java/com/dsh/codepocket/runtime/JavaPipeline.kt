package com.dsh.codepocket.runtime

import com.dsh.codepocket.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Reader

/**
 * Java toolchain that needs **no privilege at all**.
 *
 * `.java` --Janino--> `.class` --D8--> `.dex` --system dalvikvm--> output
 *
 * The first two steps run inside the app process (plain Java libraries); the last spawns
 * `dalvikvm`, which lives in /apex/com.android.art/bin — a *system* binary. Android's W^X
 * rule only forbids exec()-ing files in the app's own data directory, so running a system
 * binary that merely reads our dex is fine. Verified on device before this code existed.
 *
 * Why Janino and not ECJ: ECJ 3.46 and 3.18 both reference JDK-only
 * `javax.lang.model` / `javax.annotation.processing` classes, which Android does not ship.
 * Two real ClassNotFoundExceptions on device settled it. Janino is designed for embedding.
 *
 * Everything third-party is called **reflectively**: these are JVM libraries that were
 * never published for Android, so their exact API shape is not something to hard-code.
 * Each attempt logs which shape worked, which also makes failures self-explanatory.
 */
object JavaPipeline {

    /** d8 refuses class files newer than Java 17. */
    private const val SOURCE_LEVEL = "11"
    private const val MIN_API = "24"

    private const val HELLO = """
public class Hello {
    public static void main(String[] args) {
        System.out.println("JAVA_ON_ANDROID_OK");
        System.out.println("java.vm.name=" + System.getProperty("java.vm.name"));
        System.out.println("vm.version=" + System.getProperty("java.vm.version"));
        int sum = 0;
        for (int i = 1; i <= 10; i++) sum += i;
        System.out.println("sum(1..10)=" + sum);
        System.out.println("args=" + java.util.Arrays.toString(args));
    }
}
"""

    private fun dalvikvm(): String? = listOf(
        "/apex/com.android.art/bin/dalvikvm",
        "/system/bin/dalvikvm",
    ).firstOrNull { File(it).exists() }

    fun compileAndRun(filesDir: File, sourceFile: File, args: List<String> = emptyList()): String {
        val log = StringBuilder()
        val work = File(filesDir, "java_build").apply { mkdirs() }
        val classDir = File(work, "classes").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val dexDir = File(work, "dex").apply { mkdirs() }

        // ---- 1) compile: Janino ----
        if (!compileWithJanino(sourceFile, classDir, log)) {
            return log.toString()
        }
        val classFiles = classDir.walkTopDown().filter { it.extension == "class" }.toList()
        if (classFiles.isEmpty()) {
            log.append("[中止] 没有产生任何 class 文件\n")
            return log.toString()
        }

        // ---- 2) dex: D8 ----
        if (!dexWithD8(classDir, dexDir, log)) {
            return log.toString()
        }
        val dex = File(dexDir, "classes.dex")

        // ---- 3) run: system dalvikvm ----
        val vm = dalvikvm()
        if (vm == null) {
            log.append("[运行失败] 找不到 dalvikvm\n")
            return log.toString()
        }
        val mainClass = classFiles.firstOrNull { it.nameWithoutExtension == sourceFile.nameWithoutExtension }
            ?.let { relativeClassName(classDir, it) }
            ?: sourceFile.nameWithoutExtension

        val command = mutableListOf(vm, "-cp", dex.absolutePath, mainClass)
        command.addAll(args)
        Diag.log("java", "dalvikvm $mainClass (${dex.length()} B dex)")
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            log.append("[运行] $mainClass 退出码=$code\n")
            log.append(output)
            log.toString()
        } catch (t: Throwable) {
            log.append("[运行失败] ").append(rootCause(t)).append('\n')
            log.toString()
        }
    }

    // ---------------------------------------------------------------- Janino

    private fun compileWithJanino(sourceFile: File, classDir: File, log: StringBuilder): Boolean {
        return try {
            val evaluatorClass = Class.forName("org.codehaus.janino.SimpleCompiler")
            val evaluator = evaluatorClass.getDeclaredConstructor().newInstance()
            runCatching {
                evaluatorClass.getMethod("setParentClassLoader", ClassLoader::class.java)
                    .invoke(evaluator, javaClass.classLoader)
            }

            // A file name ending in .java makes Janino treat the input as a full
            // compilation unit (not a bare class body).
            val cook = evaluatorClass.methods.firstOrNull {
                it.name == "cook" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Reader::class.java
            } ?: evaluatorClass.methods.firstOrNull { it.name == "cook" && it.parameterCount == 1 }
            ?: throw IllegalStateException("SimpleCompiler 上找不到 cook 方法")

            if (cook.parameterCount == 2) {
                sourceFile.reader().use { reader -> cook.invoke(evaluator, sourceFile.name, reader) }
            } else {
                cook.invoke(evaluator, sourceFile.readText())
            }

            val bytecodes = extractBytecodes(evaluator, log)
            if (bytecodes.isEmpty()) {
                log.append("[Janino] 编译通过但无法取出字节码\n")
                return false
            }
            var written = 0
            for ((name, data) in bytecodes) {
                val path = when {
                    name.endsWith(".class") -> name
                    else -> name.replace('.', '/') + ".class"
                }
                val out = File(classDir, path)
                out.parentFile?.mkdirs()
                out.writeBytes(data)
                written++
            }
            log.append("[编译成功] Janino 写出 $written 个 class 文件\n")
            true
        } catch (t: Throwable) {
            log.append("Janino 调用失败: ").append(rootCause(t)).append('\n')
            false
        }
    }

    /**
     * Pulls the generated bytecode out of Janino. Two shapes are tried because the
     * library exposes it differently across versions; whichever works is logged.
     */
    private fun extractBytecodes(evaluator: Any, log: StringBuilder): Map<String, ByteArray> {
        for (name in listOf("getBytecodes", "getBytecode")) {
            val method = evaluator.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            if (method != null) {
                runCatching {
                    toByteMap(method.invoke(evaluator))?.let {
                        log.append("[Janino] 字节码来源: $name()\n")
                        return it
                    }
                }
            }
        }
        // Fall back to the evaluator's internal class loader, which holds the bytes.
        runCatching {
            var cls: Class<*>? = evaluator.javaClass
            while (cls != null) {
                val field = cls.declaredFields.firstOrNull {
                    ClassLoader::class.java.isAssignableFrom(it.type)
                }
                if (field != null) {
                    field.isAccessible = true
                    val loader = field.get(evaluator)
                    val method = loader?.javaClass?.methods
                        ?.firstOrNull { it.name == "getBytecodes" && it.parameterCount == 0 }
                    if (method != null) {
                        toByteMap(method.invoke(loader))?.let { bytes ->
                            log.append("[Janino] 字节码来源: ${field.name}.getBytecodes()\n")
                            return bytes
                        }
                    }
                }
                cls = cls.superclass
            }
        }
        return emptyMap()
    }

    private fun toByteMap(value: Any?): Map<String, ByteArray>? = when (value) {
        is Map<*, *> -> value.entries.mapNotNull { entry ->
            val name = entry.key as? String ?: return@mapNotNull null
            val data = entry.value as? ByteArray ?: return@mapNotNull null
            name to data
        }.toMap().takeIf { it.isNotEmpty() }

        is Array<*> -> value.filterIsInstance<ByteArray>()
            .mapIndexed { index, data -> "Class$index" to data }
            .toMap()
            .takeIf { it.isNotEmpty() }

        else -> null
    }

    // ---------------------------------------------------------------- D8

    private fun dexWithD8(classDir: File, dexDir: File, log: StringBuilder): Boolean {
        // D8's CLI rejects a directory as program input ("Unsupported source file type"),
        // so every compiled class file is passed explicitly.
        val classFiles = classDir.walkTopDown()
            .filter { it.extension == "class" }
            .map { it.absolutePath }
            .toList()
        val argv = (listOf("--min-api", MIN_API, "--output", dexDir.absolutePath) + classFiles)
            .toTypedArray()
        val d8Class = try {
            Class.forName("com.android.tools.r8.D8")
        } catch (t: Throwable) {
            log.append("找不到 D8: ").append(rootCause(t)).append('\n')
            return false
        }

        // R8 exposes several entry points and they move between releases: 9.x has no
        // static run(String[]). Try each documented shape and report which one worked.
        val attempts: List<Pair<String, () -> Unit>> = listOf(
            "run(String[])" to {
                d8Class.getMethod("run", Array<String>::class.java).invoke(null, argv)
            },
            "main(String[])" to {
                d8Class.getMethod("main", Array<String>::class.java).invoke(null, argv)
            },
            "D8Command.Builder" to {
                dexViaCommandBuilder(classDir, dexDir)
            },
        )

        for ((label, call) in attempts) {
            try {
                call()
            } catch (t: Throwable) {
                log.append("[D8] $label 失败: ").append(rootCause(t)).append('\n')
                continue
            }
            val dex = File(dexDir, "classes.dex")
            if (dex.exists()) {
                log.append("[转 dex 成功] ${dex.length() / 1024} KB（入口 $label）\n")
                return true
            }
            log.append("[D8] $label 执行了但没有产出 classes.dex\n")
        }
        log.append("[转 dex 失败] D8 的所有入口都不可用\n")
        return false
    }

    /** The programmatic API: D8Command.builder() -> build() -> D8.run(command). */
    private fun dexViaCommandBuilder(classDir: File, dexDir: File) {
        val builderClass = Class.forName("com.android.tools.r8.D8Command")
        val builder = builderClass.getMethod("builder").invoke(null)
        val outputModeClass = Class.forName("com.android.tools.r8.OutputMode")
        // The enum constant was renamed across releases.
        val dexMode = listOf("Dex", "DexIndexed").firstNotNullOfOrNull { name ->
            runCatching { outputModeClass.getField(name).get(null) }.getOrNull()
        } ?: throw IllegalStateException("OutputMode 里找不到 Dex/DexIndexed")
        val pathClass = java.nio.file.Path::class.java

        builderClass.getMethod("addProgramFile", pathClass).invoke(builder, classDir.toPath())
        builderClass.getMethod("setMinApiLevel", Int::class.javaPrimitiveType)
            .invoke(builder, MIN_API.toInt())
        builderClass.getMethod("setOutput", pathClass, outputModeClass)
            .invoke(builder, dexDir.toPath(), dexMode)
        val command = builderClass.getMethod("build").invoke(builder)
        Class.forName("com.android.tools.r8.D8").getMethod("run", builderClass).invoke(null, command)
    }

    private fun relativeClassName(root: File, file: File): String =
        file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')

    private fun rootCause(t: Throwable): String {
        var cause: Throwable = t
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    suspend fun selfTest(filesDir: File): String = withContext(Dispatchers.IO) {
        try {
            val work = File(filesDir, "java_build").apply { mkdirs() }
            val src = File(work, "Hello.java")
            src.writeText(HELLO)
            val result = compileAndRun(filesDir, src, listOf("dsh"))
            Diag.log("java", "selfTest: ${result.replace('\n', ' ').take(400)}")
            result
        } catch (t: Throwable) {
            "自检异常: ${t.javaClass.simpleName}: ${t.message}"
        }
    }
}
