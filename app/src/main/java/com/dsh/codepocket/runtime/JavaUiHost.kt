package com.dsh.codepocket.runtime

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.codepocket.Diag
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Reader

/**
 * Java programs with a real Android interface.
 *
 * Java takes a different route from C/C++/Rust. Those must be native code, so they are
 * compiled to a shared library and loaded — but Java compiles to **dex**, and a dex can be
 * loaded straight into this app's process with `DexClassLoader`. That is the whole trick:
 * inside the app process the user's class has a `Context`, so it can build genuine Android
 * views (`TextView`, `Button`, `LinearLayout`, …) instead of drawing pixels.
 *
 * Contract — the class must match the file name (Java requires that anyway) and expose one of:
 *
 *     public static View build(Context ctx)   // preferred: the returned View fills the screen
 *     public static void main(Context ctx)    // context-only entry point
 *     public static void main()               // plain console entry point
 *
 * Loading dex from app-private storage needs no permission at API 28, which this app targets.
 */
object JavaUiHost {

    /** What the editor needs to hand to the UI screen. */
    data class Prepared(val dex: File, val className: String)

    /** Result of compiling: the dex plus the class name to look for. */
    suspend fun prepare(context: Context, source: File): Result<Prepared> =
        withContext(Dispatchers.IO) {
            runCatching {
                val work = File(context.filesDir, "javaui").apply { mkdirs() }
                val classDir = File(work, "classes").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val dexDir = File(work, "dex").apply { mkdirs(); listFiles()?.forEach { it.delete() } }

                compileWithJanino(source, classDir)
                val classFiles = classDir.walkTopDown().filter { it.extension == "class" }.toList()
                check(classFiles.isNotEmpty()) { "编译没有产生 class 文件" }

                dexWithD8(classFiles.map { it.absolutePath }, dexDir)
                val dex = File(dexDir, "classes.dex")
                check(dex.exists()) { "D8 没有产出 classes.dex" }

                val className = source.nameWithoutExtension
                Diag.log("javaui", "prepared $className (${dex.length()} B dex)")
                Prepared(dex, className)
            }
        }

    /**
     * Loads the dex into this process and invokes the user's entry point.
     * Must be called on the main thread: it creates Android views.
     */
    fun invoke(context: Context, dex: File, className: String): View {
        val optimized = File(context.filesDir, "javaui/oat").apply { mkdirs() }
        val loader = DexClassLoader(
            dex.absolutePath,
            optimized.absolutePath,
            null,
            context.classLoader,
        )
        val clazz = loader.loadClass(className)
        Diag.log("javaui", "loaded $className from dex")

        // 1) static View build(Context)
        val build = clazz.methods.firstOrNull {
            it.name == "build" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Context::class.java &&
                View::class.java.isAssignableFrom(it.returnType)
        }
        if (build != null) {
            val receiver = if (java.lang.reflect.Modifier.isStatic(build.modifiers)) null else newInstance(clazz)
            return build.invoke(receiver, context) as View
        }

        // 2) static void main(Context)
        val mainWithContext = clazz.methods.firstOrNull {
            it.name == "main" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Context::class.java
        }
        if (mainWithContext != null) {
            val receiver = if (java.lang.reflect.Modifier.isStatic(mainWithContext.modifiers)) null else newInstance(clazz)
            val returned = mainWithContext.invoke(receiver, context)
            if (returned is View) return returned
            return messageView(context, "$className.main(Context) 已执行（没有返回 View，屏幕上没有内容可显示）")
        }

        // 3) plain main()
        val plainMain = clazz.methods.firstOrNull {
            it.name == "main" && it.parameterTypes.isEmpty()
        }
        if (plainMain != null) {
            val receiver = if (java.lang.reflect.Modifier.isStatic(plainMain.modifiers)) null else newInstance(clazz)
            val returned = plainMain.invoke(receiver)
            if (returned is View) return returned
            return messageView(context, "$className.main() 已执行。\n要显示界面，请添加：public static View build(Context ctx)")
        }

        throw NoSuchMethodException(
            "$className 里找不到 build(Context) / main(Context) / main()。" +
                "窗口化需要其中之一，例如：public static android.view.View build(android.content.Context ctx)",
        )
    }

    private fun newInstance(clazz: Class<*>): Any =
        clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun messageView(context: Context, text: String): View =
        android.widget.TextView(context).apply {
            setText(text)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(11, 16, 32))
            textSize = 15f
            setPadding(40, 60, 40, 40)
        }

    // ---------------------------------------------------------------- 编译（Janino）

    /**
     * NOTE: this duplicates the Janino/D8 phases that JavaPipeline already performs for
     * console runs. The two should be merged into one build step; kept separate here so the
     * console path — which is already verified on device — is not disturbed by this addition.
     */
    private fun compileWithJanino(source: File, classDir: File) {
        val evaluatorClass = Class.forName("org.codehaus.janino.SimpleCompiler")
        val evaluator = evaluatorClass.getDeclaredConstructor().newInstance()
        runCatching {
            evaluatorClass.getMethod("setParentClassLoader", ClassLoader::class.java)
                .invoke(evaluator, javaClass.classLoader)
        }
        val cook = evaluatorClass.methods.firstOrNull {
            it.name == "cook" && it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == Reader::class.java
        } ?: error("SimpleCompiler 上找不到 cook 方法")
        source.reader().use { reader -> cook.invoke(evaluator, source.name, reader) }

        val bytes = extractBytecodes(evaluator)
        check(bytes.isNotEmpty()) { "Janino 编译通过但取不到字节码" }
        for ((name, data) in bytes) {
            val path = if (name.endsWith(".class")) name else name.replace('.', '/') + ".class"
            val out = File(classDir, path)
            out.parentFile?.mkdirs()
            out.writeBytes(data)
        }
    }

    private fun extractBytecodes(evaluator: Any): Map<String, ByteArray> {
        for (methodName in listOf("getBytecodes", "getBytecode")) {
            val method = evaluator.javaClass.methods
                .firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: continue
            runCatching {
                when (val value = method.invoke(evaluator)) {
                    is Map<*, *> -> {
                        val map = value.entries.mapNotNull { entry ->
                            val key = entry.key as? String ?: return@mapNotNull null
                            val data = entry.value as? ByteArray ?: return@mapNotNull null
                            key to data
                        }.toMap()
                        if (map.isNotEmpty()) return map
                    }
                }
            }
        }
        // Fall back to the evaluator's internal byte-array class loader.
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
                    val value = method.invoke(loader)
                    if (value is Map<*, *>) {
                        val map = value.entries.mapNotNull { entry ->
                            val key = entry.key as? String ?: return@mapNotNull null
                            val data = entry.value as? ByteArray ?: return@mapNotNull null
                            key to data
                        }.toMap()
                        if (map.isNotEmpty()) return map
                    }
                }
            }
            cls = cls.superclass
        }
        return emptyMap()
    }

    /** D8 again: no run(String[]) entry point in 9.x, and the CLI rejects directories. */
    private fun dexWithD8(classFilePaths: List<String>, dexDir: File) {
        val d8Class = Class.forName("com.android.tools.r8.D8")
        val argv = (listOf("--min-api", "24", "--output", dexDir.absolutePath) + classFilePaths).toTypedArray()
        val main = d8Class.getMethod("main", Array<String>::class.java)
        main.invoke(null, argv)
    }
}

/**
 * Hosts the View the user's Java class produced.
 *
 * The View is built on the main thread (creating Android views off it is asking for trouble),
 * then handed to `AndroidView`.
 */
@Composable
fun JavaUiScreen(
    dexPath: String,
    className: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("载入中…") }
    var view by remember { mutableStateOf<View?>(null) }

    LaunchedEffect(dexPath, className) {
        status = "构建 $className 的界面…"
        val started = System.currentTimeMillis()
        val result = withContext(Dispatchers.Main) {
            runCatching { JavaUiHost.invoke(context, File(dexPath), className) }
        }
        val elapsed = System.currentTimeMillis() - started
        result.fold(
            onSuccess = { built ->
                view = built
                status = "界面已载入 · $className · ${elapsed}ms"
                Diag.log("javaui", "view built in ${elapsed}ms")
            },
            onFailure = { error ->
                var cause: Throwable = error
                while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
                status = "失败：${cause.javaClass.simpleName}: ${cause.message}"
                Diag.log("javaui", "invoke failed: ${cause.message}")
            },
        )
    }

    Column(modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(status, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
        }
        Box(Modifier.weight(1f)) {
            val built = view
            if (built != null) {
                AndroidView(factory = { built }, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    status,
                    color = Color(0xFF8B98A9),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
