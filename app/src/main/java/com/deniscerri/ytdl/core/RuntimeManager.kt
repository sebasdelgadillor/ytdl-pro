package com.deniscerri.ytdl.core

import android.content.Context
import android.os.Build
import android.os.Environment
import com.deniscerri.ytdl.App
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.core.models.ExecuteException
import com.deniscerri.ytdl.core.models.ExecuteResponse
import com.deniscerri.ytdl.core.models.YTDLRequest
import com.deniscerri.ytdl.core.packages.Aria2c
import com.deniscerri.ytdl.core.packages.Deno
import com.deniscerri.ytdl.core.packages.FFmpeg
import com.deniscerri.ytdl.core.packages.NodeJS
import com.deniscerri.ytdl.core.packages.PackageBase
import com.deniscerri.ytdl.core.packages.Python
import com.deniscerri.ytdl.core.packages.QuickJS
import com.deniscerri.ytdl.core.stream.StreamGobbler
import com.deniscerri.ytdl.core.stream.StreamProcessExtractor
import com.deniscerri.ytdl.database.models.PackageItem
import com.deniscerri.ytdl.util.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.set
import kotlin.concurrent.Volatile

object RuntimeManager {
    val idProcessMap = Collections.synchronizedMap(HashMap<String, Process>())
    lateinit var pythonLocation: PackageBase.PackageLocation
    lateinit var ffmpegLocation: PackageBase.PackageLocation
    lateinit var aria2Location: PackageBase.PackageLocation
    lateinit var nodeLocation : PackageBase.PackageLocation
    lateinit var denoLocation : PackageBase.PackageLocation
    lateinit var quickJsLocation : PackageBase.PackageLocation
    var ytdlpPath: File? = null

    @Volatile
    var initialized = false
        private set

    @Volatile
    var initError: Throwable? = null
        private set

    // first launch extracts every package, which can take well over 30 seconds on slow devices
    @Volatile
    private var initInProgress = false

    private var initLatch = CountDownLatch(1)
    private val initLock = Any()

    private var updateLatch = CountDownLatch(1)
    private val updateLock = Any()

    const val BASENAME = "ytdlnis"
    const val ytdlpDirName = "yt-dlp"
    const val ytdlpBin = "yt-dlp"

    private var ENV_LD_LIBRARY_PATH: String? = null
    private var PATH: String? = null
    private var ENV_SSL_CERT_FILE: String? = null
    private var OPEN_SSL_CONF: String? = null
    private var ENV_PYTHONHOME: String? = null
    private var TMPDIR: String = ""

    private var NPM_CONFIG_PREFIX: String = ""
    private var NPM_CONFIG_CACHE: String = ""
    private var NPM_CLI_PATH: String = ""
    private var NODE_OPTIONS: String = ""
    private var NODE_HOME: String = ""

    val packages: List<PackageItem> = listOf(
        PackageItem("Python", Python),
        PackageItem("FFmpeg", FFmpeg),
        PackageItem("NodeJS", NodeJS),
        PackageItem("Deno", Deno),
        PackageItem("Aria2c", Aria2c)
    )

    fun init(appContext: Context) {
        if (initialized) return

        synchronized(initLock) {
            if (initialized) return

            initInProgress = true
            runBlocking(Dispatchers.IO) {
                try {
                    val baseDir = File(appContext.noBackupFilesDir, BASENAME).apply { if (!exists()) mkdir() }

                    val python = Python.getInstance()
                    val ffmpeg = FFmpeg.getInstance()
                    val aria2c = Aria2c.getInstance()
                    val nodeJS = NodeJS.getInstance()
                    val quickJS = QuickJS.getInstance()
                    val deno = Deno.getInstance()

                    coroutineScope {
                        val packages = listOf(python, ffmpeg, aria2c, nodeJS, quickJS, deno)
                        packages.map { pkg ->
                            async {
                                pkg.init(appContext)
                            }
                        }.awaitAll()
                    }

                    //find location of libraries either from bundled or downloaded paths
                    pythonLocation = python.location
                    ffmpegLocation = ffmpeg.location
                    aria2Location = aria2c.location
                    nodeLocation = nodeJS.location
                    denoLocation = deno.location
                    quickJsLocation = quickJS.location

                    val ytdlpDir = File(baseDir, ytdlpDirName)
                    ytdlpPath = File(ytdlpDir, ytdlpBin)
                    initYTDLP(appContext, ytdlpDir)

                    val locations = listOf(
                        pythonLocation,
                        ffmpegLocation,
                        aria2Location,
                        quickJsLocation,
                        denoLocation,
                        nodeLocation,
                    )

                    val ldPaths = mutableListOf<String>()
                    locations.forEach {
                        val usrLib = File(it.ldDir, "usr/lib")
                        if (usrLib.exists()) {
                            ldPaths.add(usrLib.absolutePath)
                        } else if (it.ldDir.exists()) {
                            ldPaths.add(it.ldDir.absolutePath)
                        }
                    }
                    ldPaths.add(appContext.applicationInfo.nativeLibraryDir)
                    ENV_LD_LIBRARY_PATH = ldPaths.distinct().joinToString(":")

                    val binPaths = locations.filter { it.binDir.exists() }.map { it.binDir.absolutePath }.toMutableList()
                    binPaths.add(System.getenv("PATH") ?: "/system/bin")
                    PATH = binPaths.distinct().joinToString(":")

                    ENV_SSL_CERT_FILE = listOf(pythonLocation, ffmpegLocation)
                        .map { File(it.ldDir, "usr/etc/tls/cert.pem") }
                        .firstOrNull { it.exists() }
                        ?.absolutePath
                        ?: File(pythonLocation.ldDir, "usr/etc/tls/cert.pem").absolutePath

                    OPEN_SSL_CONF = ""
                    if (nodeLocation.ldDir.exists()) {
                        OPEN_SSL_CONF = File(nodeLocation.ldDir, "usr/etc/tls/openssl.cnf").absolutePath
                    }

                    ENV_PYTHONHOME = if (pythonLocation.isDownloaded) {
                        pythonLocation.ldDir.absolutePath + "/usr"
                    } else {
                        pythonLocation.ldDir.absolutePath + "/usr"
                    }
                    TMPDIR = appContext.cacheDir.absolutePath

                    NPM_CONFIG_PREFIX = File(appContext.filesDir, ".npm-global").absolutePath
                    NPM_CONFIG_CACHE = File(appContext.filesDir, ".npm-cache").absolutePath
                    // node and npm write config/cache into HOME, give them a dedicated writable dir
                    // the python HOME points into the package folder which gets wiped on package updates
                    NODE_HOME = File(appContext.filesDir, "node_home").absolutePath
                    listOf(NODE_HOME, NPM_CONFIG_PREFIX, NPM_CONFIG_CACHE).forEach { File(it).mkdirs() }

                    if (nodeLocation.executable.exists()) {
                        NPM_CLI_PATH = File(nodeLocation.ldDir, "usr/lib/node_modules/npm/bin/npm-cli.js").absolutePath

                        // android has no resolv.conf so node's default dns resolution fails
                        val optionsFile = File(appContext.filesDir, "node_dns_setup.js")
                        optionsFile.writeText(NodeJS.getDNSSetup())
                        NODE_OPTIONS = "--require ${optionsFile.absolutePath}"
                    }

                    val ytdlpPluginsFolder = File(FileUtil.getBundledYTDLPPluginsPath(appContext))
                    copyAssetFolder(appContext, "yt_dlp_plugins", ytdlpPluginsFolder)

                    initError = null
                    initialized = true
                } catch (e: Throwable) {
                    e.printStackTrace()
                    initError = e
                    initialized = false
                } finally {
                    initInProgress = false
                    initLatch.countDown()
                    updateLatch.countDown()
                }
            }
        }
    }

    fun copyAssetFolder(context: Context, assetFolderPath: String, targetFolder: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetFolderPath) ?: return

        if (!targetFolder.exists()) {
            targetFolder.mkdirs()
        }

        for (file in files) {
            val assetPath = if (assetFolderPath.isEmpty()) file else "$assetFolderPath/$file"
            val subFiles = assetManager.list(assetPath)

            if (!subFiles.isNullOrEmpty()) {
                // It's a directory -> recursively copy it
                copyAssetFolder(context, assetPath, File(targetFolder, file))
            } else {
                // It's a file -> write to internal storage
                copyAssetFileIfNeeded(context, assetPath, File(targetFolder, file))
            }
        }
    }

    private fun copyAssetFileIfNeeded(context: Context, assetPath: String, outFile: File) {
        if (!outFile.exists()) {
            copyAssetFile(context, assetPath, outFile)
            return
        }

        // Compare hashes of asset vs local file
        val assetHash = context.assets.open(assetPath).use { calculateHash(it) }
        val fileHash = outFile.inputStream().use { calculateHash(it) }

        if (assetHash != fileHash) {
            copyAssetFile(context, assetPath, outFile)
        }
    }

    private fun calculateHash(inputStream: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyAssetFile(context: Context, assetPath: String, outFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    fun reInit(context: Context) {
        synchronized(initLock) {
            initialized = false
            initLatch = CountDownLatch(1)
            init(context)
        }

    }

    fun assertInit() {
        var success = initLatch.await(30, TimeUnit.SECONDS)
        // still extracting, keep waiting instead of failing in the middle of it
        if (!success && initInProgress) {
            success = initLatch.await(5, TimeUnit.MINUTES)
        }
        if (!success) {
            val state = if (initInProgress) "still running after 5 minutes" else "it was never started"
            throw IllegalStateException("Instance not initialized: timed out waiting for initialization, $state")
        }
        if (!initialized) {
            val error = initError
            val reason = error?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown error"
            throw IllegalStateException("Instance not initialized: $reason", error)
        }
    }

    fun assertNoUpdate() {
        val completed = updateLatch.await(2, TimeUnit.MINUTES)
        if (!completed) {
            throw IllegalStateException("Update timed out or failed to complete")
        }
    }

    @Throws(ExecuteException::class)
    fun initYTDLP(appContext: Context, ytdlpDir: File) {
        if (!ytdlpDir.exists()) ytdlpDir.mkdirs()
        val ytdlpBinary = File(ytdlpDir, ytdlpBin)
        if (!ytdlpBinary.exists()) {
            try {
                val inputStream = appContext.resources.openRawResource(R.raw.ytdlp)
                FileUtils.copyInputStreamToFile(inputStream, ytdlpBinary)
            } catch (e: Exception) {
                FileUtils.deleteQuietly(ytdlpDir)
                throw ExecuteException("failed to initialize", e)
            }
        }
    }

    fun destroyProcessById(id: String): Boolean {
        if (idProcessMap.containsKey(id)) {
            val p = idProcessMap[id]
            var alive = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                alive = p!!.isAlive
            }
            if (alive) {
                // remove first so the executing thread treats the exit as a cancellation and not as a failure
                idProcessMap.remove(id)
                p?.let { destroyProcessTree(it) }
                return true
            }
        }
        return false
    }

    /**
     * Kills the process together with everything it spawned (ffmpeg, aria2c, js runtimes used by yt-dlp etc.).
     * Process.destroy() alone only kills python and leaves its children running
     */
    private fun destroyProcessTree(process: Process) {
        val pid = process.pidOrNull()
        if (pid != null) {
            descendantPids(pid).forEach { child ->
                runCatching { android.os.Process.killProcess(child) }
            }
        }
        process.destroy()
    }

    private fun Process.pidOrNull() : Int? = runCatching {
        val field = javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(this)
    }.getOrNull() ?: runCatching {
        // android's process implementation prints itself as "Process[pid=123, hasExited=false]"
        Regex("""pid=(\d+)""").find(toString())?.groupValues?.get(1)?.toInt()
    }.getOrNull()

    private fun descendantPids(rootPid: Int) : List<Int> {
        val childrenByParent = mutableMapOf<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            // format is "pid (comm) state ppid ...", comm can contain spaces so read after the last ')'
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            val ppid = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull() ?: return@forEach
            childrenByParent.getOrPut(ppid) { mutableListOf() }.add(pid)
        }

        val result = mutableListOf<Int>()
        val queue = ArrayDeque(listOf(rootPid))
        while (queue.isNotEmpty()) {
            childrenByParent[queue.removeFirst()]?.forEach {
                result.add(it)
                queue.add(it)
            }
        }
        return result
    }

    /**
     * Process ids that got cancelled before their process was registered
     */
    private val cancelledProcessIds = Collections.synchronizedSet(HashSet<String>())

    private fun registerProcess(processId: String?, process: Process) {
        if (processId == null) return
        idProcessMap[processId] = process
        if (cancelledProcessIds.contains(processId)) {
            idProcessMap.remove(processId)
            destroyProcessTree(process)
            throw CanceledException()
        }
    }

    /**
     * Runs a blocking call (like [execute] or [executeStreaming]) so that cancelling the calling coroutine
     * also kills the process it started. The block has to pass the given processId to the execute call.
     * Without this, cancelling only stops the coroutine while yt-dlp keeps running in the background
     */
    suspend fun <T> runKillable(block: (processId: String) -> T): T {
        val processId = "killable_${UUID.randomUUID()}"
        return coroutineScope {
            val work = async(Dispatchers.IO) {
                try {
                    block(processId)
                } finally {
                    cancelledProcessIds.remove(processId)
                }
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                if (!work.isCompleted) cancelledProcessIds.add(processId)
                destroyProcessById(processId)
                throw e
            }
        }
    }

    class CanceledException : Exception()
    class StalledProcessException(message: String) : ExecuteException(message)

    private fun buildYTDLCommand(
        request: YTDLRequest,
        usingCacheDir: Boolean
    ): List<String> {
        assertInit()
        assertNoUpdate()

        if (ffmpegLocation.isAvailable) {
            request.addOption("--ffmpeg-location", ffmpegLocation.executable.absolutePath)
        }

        if (nodeLocation.isAvailable) {
            request.addOption("--js-runtimes", "node:${nodeLocation.executable.absolutePath}")
        }

        if (denoLocation.isAvailable) {
            request.addOption("--js-runtimes", "deno:${denoLocation.executable.absolutePath}")
        }

        if (quickJsLocation.isAvailable) {
            request.addOption("--js-runtimes", "quickjs:${quickJsLocation.executable.absolutePath}")
        }

        if (request.buildCommand().contains("libaria2c.so")) {
            request.addOption(
                "--external-downloader-args",
                "aria2c:--ca-certificate=$ENV_SSL_CERT_FILE"
            )
        }

        if (!usingCacheDir) {
            request.addOption("--no-cache-dir")
        }

        return mutableListOf(pythonLocation.executable.absolutePath, ytdlpPath!!.absolutePath) + request.buildCommand()
    }

    fun execute(
        request: YTDLRequest,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        usingCacheDir: Boolean = false,
        stallTimeoutMs: Long = if (usingCacheDir) 60_000L else 35_000L,
        maxDurationMs: Long = if (usingCacheDir) 0L else 95_000L,
        callback: ((Float, Long, String) -> Unit)? = null
    ) : ExecuteResponse {
        val fullCommand = buildYTDLCommand(request, usingCacheDir)
        return executeImpl(
            fullCommand = fullCommand,
            processId = processId,
            redirectErrorStream = redirectErrorStream,
            stallTimeoutMs = stallTimeoutMs,
            maxDurationMs = maxDurationMs,
            callback = callback
        )
    }

    fun executePython(
        command: String,
        processId: String? = null,
        callback: ((Float, Long, String) -> Unit)? = null
    ) : ExecuteResponse {
        assertInit()

        val fullCommand = mutableListOf<String>(pythonLocation.executable.absolutePath)
        fullCommand.addAll(command.split(" "))
        return executeImpl(fullCommand, processId, true, callback = callback)
    }

    fun executeNode(
        command: String,
        processId: String? = null,
        executeDirectory: File? = null,
        callback: ((Float, Long, String) -> Unit)? = null
    ) : ExecuteResponse {
        assertInit()

        val fullCommand = mutableListOf<String>(nodeLocation.executable.absolutePath)
        fullCommand.addAll(command.split(" "))
        return executeImpl(fullCommand, processId, true, executeDirectory = executeDirectory, extraEnv = getNodeEnvironment(), callback = callback)
    }

    /**
     * Env only applied to node/npm processes. Not global, so the node yt-dlp spawns for js challenges stays untouched
     */
    fun getNodeEnvironment() : Map<String, String> {
        val env = mutableMapOf("HOME" to NODE_HOME)
        val certFile = listOf(nodeLocation, pythonLocation)
            .map { File(it.ldDir, "usr/etc/tls/cert.pem") }
            .firstOrNull { it.exists() }
            ?: ENV_SSL_CERT_FILE?.let { File(it) }?.takeIf { it.exists() }
        if (certFile != null) {
            env["SSL_CERT_FILE"] = certFile.absolutePath
            env["NODE_EXTRA_CA_CERTS"] = certFile.absolutePath
        }
        if (NODE_OPTIONS.isNotEmpty()) env["NODE_OPTIONS"] = NODE_OPTIONS
        return env
    }

    fun executeNpm(
        command: String,
        processId: String? = null,
        executeDirectory: File? = null,
        callback: ((Float, Long, String) -> Unit)? = null
    ) : ExecuteResponse {
        assertInit()

        val fullCommand = mutableListOf<String>(nodeLocation.executable.absolutePath, NPM_CLI_PATH)
        fullCommand.addAll(command.split(" "))
        return executeImpl(fullCommand, processId, true, executeDirectory = executeDirectory, extraEnv = getNodeEnvironment(), callback = callback)
    }


    fun executeDeno(
        command: String,
        processId: String? = null,
        executeDirectory: File? = null,
        callback: ((Float, Long, String) -> Unit)? = null
    ) : ExecuteResponse {
        assertInit()

        val fullCommand = mutableListOf<String>(denoLocation.executable.absolutePath)
        fullCommand.addAll(command.split(" "))
        return executeImpl(fullCommand, processId, true, executeDirectory = executeDirectory, callback = callback)
    }

    fun executeFFmpeg(
        args: List<String>,
        processId: String? = null,
        executeDirectory: File? = null,
        stallTimeoutMs: Long = 60_000L,
        maxDurationMs: Long = 0L,
        callback: ((Float, Long, String) -> Unit)? = null
    ): ExecuteResponse {
        assertInit()
        if (!ffmpegLocation.isAvailable) {
            throw ExecuteException("FFmpeg binary is not available")
        }
        val fullCommand = mutableListOf(ffmpegLocation.executable.absolutePath)
        fullCommand.addAll(args)
        return executeImpl(
            fullCommand = fullCommand,
            processId = processId,
            redirectErrorStream = true,
            executeDirectory = executeDirectory,
            stallTimeoutMs = stallTimeoutMs,
            maxDurationMs = maxDurationMs,
            callback = callback
        )
    }

    fun executeImpl(
        fullCommand: List<String>,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        executeDirectory: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        stallTimeoutMs: Long = 0L,
        maxDurationMs: Long = 0L,
        callback: ((Float, Long, String) -> Unit)? = null
    ) : ExecuteResponse {

        if (processId != null && idProcessMap.containsKey(processId)) {
            throw ExecuteException("Process ID already exists")
        }

        val startTime = System.currentTimeMillis()
        val lastActivityMs = AtomicLong(startTime)
        val stalledFlag = AtomicBoolean(false)
        val processBuilder = ProcessBuilder(fullCommand).redirectErrorStream(redirectErrorStream)

        processBuilder.environment().putAll(getEnvironment())
        processBuilder.environment().putAll(extraEnv)

        val outBuffer = StringBuffer()
        val errBuffer = StringBuffer()

        if (executeDirectory != null) {
            processBuilder.directory(executeDirectory)
        }

        val process = try {
            processBuilder.start()
        } catch (e: IOException) {
            throw ExecuteException(e)
        }
        registerProcess(processId, process)

        val watchdog = startProcessWatchdog(
            process = process,
            processId = processId,
            startTimeMs = startTime,
            lastActivityMs = lastActivityMs,
            stallTimeoutMs = stallTimeoutMs,
            maxDurationMs = maxDurationMs,
            stalledFlag = stalledFlag
        )

        return try {
            val activityCallback: (Float, Long, String) -> Unit = { p, eta, line ->
                lastActivityMs.set(System.currentTimeMillis())
                callback?.invoke(p, eta, line)
            }
            val stdOutProcessor = StreamProcessExtractor(outBuffer, process.inputStream, activityCallback)
            val stdErrProcessor = StreamGobbler(errBuffer, process.errorStream)

            stdOutProcessor.join()
            stdErrProcessor.join()

            val exitCode = process.waitFor()
            val out = outBuffer.toString()
            val err = errBuffer.toString()

            val successCodes = listOf(
                0, //Everything is successful
            )

            if (!successCodes.contains(exitCode)) {
                if (stalledFlag.get()) {
                    throw StalledProcessException("Process stalled and was terminated to save battery")
                }
                // Check if process was manually killed (removed from map)
                if (processId != null && !idProcessMap.containsKey(processId)) throw CanceledException()
                throw ExecuteException(err.ifBlank { out.takeLast(2000) })
            }

            ExecuteResponse(fullCommand, exitCode, System.currentTimeMillis() - startTime, out, err)
        } catch (e: InterruptedException) {
            process.destroy()
            throw e
        } finally {
            watchdog?.interrupt()
            if (processId != null) idProcessMap.remove(processId)
        }
    }

    private fun startProcessWatchdog(
        process: Process,
        processId: String?,
        startTimeMs: Long,
        lastActivityMs: AtomicLong,
        stallTimeoutMs: Long,
        maxDurationMs: Long,
        stalledFlag: AtomicBoolean
    ): Thread? {
        if (stallTimeoutMs <= 0L && maxDurationMs <= 0L) return null
        return Thread {
            try {
                while (isProcessAlive(process)) {
                    Thread.sleep(2_000L)
                    if (!isProcessAlive(process)) break
                    val now = System.currentTimeMillis()
                    val stalled = stallTimeoutMs > 0L && (now - lastActivityMs.get()) >= stallTimeoutMs
                    val timedOut = maxDurationMs > 0L && (now - startTimeMs) >= maxDurationMs
                    if (stalled || timedOut) {
                        stalledFlag.set(true)
                        if (processId != null) idProcessMap.remove(processId)
                        destroyProcessTree(process)
                        break
                    }
                }
            } catch (_: InterruptedException) {
                // Watchdog stopped on normal completion
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun isProcessAlive(process: Process): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.isAlive
        } else {
            runCatching {
                process.exitValue()
                false
            }.getOrDefault(true)
        }
    }

    private fun startProcess(
        fullCommand: List<String>,
        processId: String?,
        executeDirectory: File?
    ): Process {
        if (processId != null && idProcessMap.containsKey(processId)) {
            throw ExecuteException("Process ID already exists")
        }

        val processBuilder = ProcessBuilder(fullCommand)
        processBuilder.environment().putAll(getEnvironment())
        if (executeDirectory != null) {
            processBuilder.directory(executeDirectory)
        }

        val process = try {
            processBuilder.start()
        } catch (e: IOException) {
            throw ExecuteException(e)
        }
        registerProcess(processId, process)
        return process
    }

    fun <T> executeStreaming(
        request: YTDLRequest,
        processId: String? = null,
        usingCacheDir: Boolean = false,
        stallTimeoutMs: Long = 35_000L,
        maxDurationMs: Long = 95_000L,
        outputHandler: (InputStream) -> T
    ): T {
        val fullCommand = buildYTDLCommand(request, usingCacheDir)
        return executeStreamingImpl(
            fullCommand = fullCommand,
            processId = processId,
            stallTimeoutMs = stallTimeoutMs,
            maxDurationMs = maxDurationMs,
            outputHandler = outputHandler
        )
    }

    fun <T> executeStreamingImpl(
        fullCommand: List<String>,
        processId: String? = null,
        executeDirectory: File? = null,
        stallTimeoutMs: Long = 35_000L,
        maxDurationMs: Long = 95_000L,
        outputHandler: (InputStream) -> T
    ): T {
        val startTime = System.currentTimeMillis()
        val lastActivityMs = AtomicLong(startTime)
        val stalledFlag = AtomicBoolean(false)
        val process = startProcess(fullCommand, processId, executeDirectory = executeDirectory)
        val errBuffer = StringBuffer()

        val watchdog = startProcessWatchdog(
            process = process,
            processId = processId,
            startTimeMs = startTime,
            lastActivityMs = lastActivityMs,
            stallTimeoutMs = stallTimeoutMs,
            maxDurationMs = maxDurationMs,
            stalledFlag = stalledFlag
        )

        return try {
            val stdErrProcessor = StreamGobbler(errBuffer, process.errorStream)

            val trackedStream = object : FilterInputStream(process.inputStream) {
                override fun read(): Int {
                    val b = super.read()
                    if (b != -1) lastActivityMs.set(System.currentTimeMillis())
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val count = super.read(b, off, len)
                    if (count > 0) lastActivityMs.set(System.currentTimeMillis())
                    return count
                }
            }

            // Consume + fully drain stdout via the caller's handler BEFORE waitFor(),
            // to avoid deadlocking on a full stdout pipe while the process still runs.
            val result = trackedStream.use { outputHandler(it) }

            stdErrProcessor.join()
            val exitCode = process.waitFor()
            val err = errBuffer.toString()

            if (exitCode != 0) {
                if (stalledFlag.get()) {
                    throw StalledProcessException("Streaming process stalled and was terminated")
                }
                if (processId != null && !idProcessMap.containsKey(processId)) throw CanceledException()
                throw ExecuteException(err)
            }

            result
        } catch (e: InterruptedException) {
            process.destroy()
            throw e
        } finally {
            watchdog?.interrupt()
            if (processId != null) idProcessMap.remove(processId)
        }
    }

    fun getEnvironment() : Map<String, String?> {
        val env = mutableMapOf<String, String?>()

        env["LD_LIBRARY_PATH"] = ENV_LD_LIBRARY_PATH
        if (OPEN_SSL_CONF != "") {
            env["OPENSSL_CONF"] = OPEN_SSL_CONF
        }
        env["SSL_CERT_FILE"] = ENV_SSL_CERT_FILE
        env["PATH"] = PATH
        env["PYTHONHOME"] = ENV_PYTHONHOME
        env["HOME"] = ENV_PYTHONHOME
        env["TMPDIR"] = TMPDIR
        env["NPM_CONFIG_PREFIX"] = NPM_CONFIG_PREFIX
        env["NPM_CONFIG_CACHE"] = NPM_CONFIG_CACHE
        env["NPM_CLI_PATH"] = NPM_CLI_PATH
        //TODO
        //env["NODE_OPTIONS"] = NODE_OPTIONS
        env["TERM"] = "xterm-256color"

        return env
    }

    fun getEnvironmentForTerminal(): MutableMap<String, String?> {
        val env = getEnvironment().toMutableMap()
        val homePath = FileUtil.getDefaultTerminalPath()
        File(homePath).mkdirs()
        env["HOME"] = homePath
        return env
    }

    @Synchronized
    @Throws(ExecuteException::class)
    fun updateYTDL(
        appContext: Context,
        updateChannel: UpdateChannel = UpdateChannel.STABLE
    ): UpdateStatus? {
        assertInit()

        synchronized(updateLock) {
            if (updateLatch.count >= 0) {
                updateLatch = CountDownLatch(1)
            }
        }

        return try {
            YTDLUpdater.update(appContext, updateChannel)
        } catch (e: IOException) {
            throw ExecuteException("failed to update youtube-dl", e)
        } finally {
            synchronized(updateLock) {
                updateLatch.countDown()
            }
        }
    }

    fun version(appContext: Context?): String? {
        return YTDLUpdater.version(appContext)
    }

    fun versionName(appContext: Context?): String? {
        return YTDLUpdater.versionName(appContext)
    }

    enum class UpdateStatus {
        DONE, ALREADY_UP_TO_DATE
    }

    open class UpdateChannel(val apiUrl: String) {
        object STABLE : UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
        object NIGHTLY :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest")
        object MASTER :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-master-builds/releases/latest")

        companion object {
            @JvmField
            val _STABLE: STABLE = STABLE

            @JvmField
            val _NIGHTLY: NIGHTLY = NIGHTLY

            @JvmField
            val _MASTER: MASTER = MASTER
        }
    }

    @JvmStatic
    fun getInstance() = this
}