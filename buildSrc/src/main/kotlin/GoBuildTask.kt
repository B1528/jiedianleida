import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

// go 工具链交叉编译 Android 产物。BuildMode.CShared 走 cgo + NDK clang，Default 走纯 Go。
abstract class GoBuildTask : DefaultTask() {

    enum class BuildMode { Default, CShared }

    @get:Inject
    abstract val execOps: ExecOperations

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val goSourceDir: DirectoryProperty

    /**
     * go.mod `replace` 指向的模块源码树（本仓是 mihomo submodule）。
     *
     * **必须声明**：`go build` 编译的绝大部分代码在 [goSourceDir] 之外，不作为输入就会让
     * rebase / 改 patch 后任务判 UP-TO-DATE，静默产出陈旧 .so——现象与「patch 根本没生效」
     * 完全一致，无法区分。宁可多收也不能漏：多收只是多重建一次，漏收是发不出信号的错误。
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val replacedModuleSources: ConfigurableFileCollection

    @get:Input
    abstract val abi: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val buildTags: ListProperty<String>

    @get:Input
    abstract val cgoEnabled: Property<Boolean>

    @get:Input
    abstract val buildMode: Property<BuildMode>

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val minSdk: Property<Int>

    @get:Input
    abstract val moduleVersionPath: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    /**
     * c-shared 模式下 go 与 .so 一并生成的头文件，被 CMake 的 target_include_directories 消费。
     * 不声明为输出就不受追踪，stale-output 清理有机会把它删掉，而报错是 CMake 的
     * `libmihomo.h: No such file`，与真实原因隔了一层。
     */
    @get:OutputFile
    @get:Optional
    abstract val headerFile: RegularFileProperty

    @TaskAction
    fun build() {
        val outFile = outputFile.get().asFile
        outFile.parentFile.mkdirs()

        val goArch = ABI_TO_GOARCH[abi.get()]
            ?: error("Unsupported ABI: ${abi.get()}; expected one of ${ABI_TO_GOARCH.keys}")

        val exeName = if (System.getProperty("os.name").lowercase().contains("windows")) "go.exe" else "go"
        val pathDirs = System.getenv("PATH")?.split(java.io.File.pathSeparator) ?: emptyList()
        val standardPaths = listOf("/usr/local/go/bin", "/opt/homebrew/bin", System.getProperty("user.home") + "/go/bin")
        val goExe = (pathDirs + standardPaths).distinct()
            .map { java.io.File(it, exeName) }
            .find { it.exists() && it.canExecute() }
            ?.absolutePath ?: exeName

        val args = mutableListOf<String>(goExe, "build")
        buildTags.get().takeIf { it.isNotEmpty() }?.let {
            args += "-tags"
            args += it.joinToString(",")
        }
        args += "-trimpath"
        // go 默认探测 VCS 状态往二进制里塞 stamp：多花时间、产物不可复现，
        // 且在没有 git 或仓库属主不匹配的容器里直接构建失败
        args += "-buildvcs=false"
        // 不设 SONAME，消费方链接器会把构建期绝对路径烙进 DT_NEEDED，运行时 dlopen 必失败
        val ldflagsBuilder = StringBuilder("-s -w -X ${moduleVersionPath.get()}=${versionName.get()}")
        if (buildMode.get() == BuildMode.CShared) {
            ldflagsBuilder.append(" -extldflags=-Wl,-soname,").append(outFile.name)
        }
        args += "-ldflags"
        args += ldflagsBuilder.toString()
        if (buildMode.get() == BuildMode.CShared) {
            args += "-buildmode=c-shared"
        }
        args += "-o"
        args += outFile.absolutePath
        args += "."

        execOps.exec {
            workingDir = goSourceDir.get().asFile
            commandLine = args

            environment("GOOS", "android")
            environment("GOARCH", goArch)
            environment("CGO_ENABLED", if (cgoEnabled.get()) "1" else "0")

            if (cgoEnabled.get()) {
                val ndk = ndkDirectory.orNull?.asFile
                    ?: error("ndkDirectory is required for cgo builds")
                val sdkLevel = minSdk.orNull
                    ?: error("minSdk is required for cgo builds")
                environment("CC", resolveClang(ndk, abi.get(), sdkLevel))
            }
        }
    }

    private fun resolveClang(ndkRoot: java.io.File, abi: String, sdkLevel: Int): String {
        val osName = System.getProperty("os.name").lowercase()
        val isWindows = osName.startsWith("windows")
        val hostTag = when {
            isWindows -> "windows-x86_64"
            osName.startsWith("mac") || osName.contains("darwin") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val toolPrefix = when (abi) {
            "arm64-v8a" -> "aarch64-linux-android"
            "armeabi-v7a" -> "armv7a-linux-androideabi"
            "x86" -> "i686-linux-android"
            "x86_64" -> "x86_64-linux-android"
            else -> error("Unsupported ABI for cgo: $abi")
        }
        val bin = ndkRoot.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
        val wrapperName = "$toolPrefix$sdkLevel-clang" + if (isWindows) ".cmd" else ""
        val wrapper = bin.resolve(wrapperName)
        if (!wrapper.exists()) {
            error(
                "NDK clang wrapper not found at ${wrapper.absolutePath}. " +
                        "Check that NDK is installed and minSdk=$sdkLevel is supported.",
            )
        }
        return wrapper.absolutePath
    }

    companion object {
        private val ABI_TO_GOARCH = mapOf(
            "arm64-v8a" to "arm64",
            "armeabi-v7a" to "arm",
            "x86" to "386",
            "x86_64" to "amd64",
        )
    }
}
