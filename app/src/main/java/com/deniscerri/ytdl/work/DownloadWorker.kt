package com.deniscerri.ytdl.work

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.afollestad.materialdialogs.utils.MDUtil.getStringArray
import com.deniscerri.ytdl.App
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.HistoryItem
import com.deniscerri.ytdl.database.models.LogItem
import com.deniscerri.ytdl.database.repository.DownloadRepository
import com.deniscerri.ytdl.database.repository.LogRepository
import com.deniscerri.ytdl.database.repository.ResultRepository
import com.deniscerri.ytdl.util.LocaleUtil
import com.deniscerri.ytdl.util.AlarmScheduler
import com.deniscerri.ytdl.util.Extensions.fixYtdlpPath
import com.deniscerri.ytdl.util.Extensions.getMediaDuration
import com.deniscerri.ytdl.util.Extensions.toStringDuration
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.ImageDownloadHelper
import com.deniscerri.ytdl.util.NotificationUtil
import com.deniscerri.ytdl.util.PlatformRateLimiter
import com.deniscerri.ytdl.util.ProcessCacheGuardian
import com.deniscerri.ytdl.util.WorkerEventBus
import com.deniscerri.ytdl.util.extractors.fallback.LocalFallbackEngine
import com.deniscerri.ytdl.util.extractors.ytdlp.YTDLPUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.collections.addAll
import kotlin.random.Random

class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : YTDLPCoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val workNotif = NotificationUtil(App.Companion.instance).createDefaultWorkerNotification()

        return ForegroundInfo(
            1000000000,
            workNotif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }



    @OptIn(ExperimentalStdlibApi::class)
    @SuppressLint("RestrictedApi")
    override suspend fun runWork(): Result {
        val workManager = WorkManager.Companion.getInstance(context)
        if (workManager.isRunning("download") || isStopped) return Result.Failure()

        setForegroundSafely()

        val parentContext = currentCoroutineContext()
        val workerScope = CoroutineScope(
            parentContext + Dispatchers.IO + SupervisorJob(parentContext[Job.Key])
        )

        val notificationUtil = NotificationUtil(App.Companion.instance)
        val dbManager = DBManager.Companion.getInstance(context)
        val dao = dbManager.downloadDao
        val historyDao = dbManager.historyDao
        val commandTemplateDao = dbManager.commandTemplateDao
        val logRepo = LogRepository(dbManager.logDao)
        val resultRepo = ResultRepository(dbManager.resultDao, commandTemplateDao, context)
        val ytdlpUtil = YTDLPUtil(context, commandTemplateDao)
        val handler = Handler(Looper.getMainLooper())
        val alarmScheduler = AlarmScheduler(context)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val time = System.currentTimeMillis() + 6000
        val priorityItemIDs = (inputData.getLongArray("priority_item_ids") ?: longArrayOf()).toMutableList()
        val continueAfterPriorityIds = inputData.getBoolean("continue_after_priority_ids", true)
        val queuedItems = if (priorityItemIDs.isEmpty()) {
            dao.getQueuedScheduledDownloadsUntil(time)
        }else {
            dao.getQueuedScheduledDownloadsUntilWithPriority(time, priorityItemIDs)
        }

        // this is needed for observe sources call, so it wont create result items
        // [removed]
        //val createResultItem = inputData.getBoolean("createResultItem", true)

        val confTmp = Configuration(context.resources.configuration)
        confTmp.setLocale(LocaleUtil.currentLocale())
        val metrics = DisplayMetrics()
        val resources = Resources(context.assets, metrics, confTmp)

        val openQueueIntent = Intent(context, MainActivity::class.java)
        openQueueIntent.setAction(Intent.ACTION_VIEW)
        openQueueIntent.putExtra("destination", "Queue")
        val openDownloadQueue = PendingIntent.getActivity(
            context,
            1000000000,
            openQueueIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val downloadDelay = sharedPreferences.getString("download_delay", "0-0")!!
        val minDelay = downloadDelay.split("-")[0].toFloat()
        val maxDelay = downloadDelay.split("-")[1].toFloat()
        val hasDownloadDelay = minDelay > 0 || maxDelay > 0

        queuedItems.collectLatest { items ->
            if (this@DownloadWorker.isStopped) return@collectLatest

            runningYTDLInstances.clear()
            val activeDownloads = dao.getActiveDownloadsList()
            activeDownloads.forEach {
                runningYTDLInstances.add(it.id)
            }

            val running = ArrayList(runningYTDLInstances)
            val useScheduler = sharedPreferences.getBoolean("use_scheduler", false)
            if (items.isEmpty() && running.isEmpty()) {
                runCatching {
                    val protectedIds = dao.getActiveAndQueuedDownloadIDs().toSet()
                    ProcessCacheGuardian.sweepOrphanedCaches(context, protectedIds)
                }
                WorkManager.Companion.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                return@collectLatest
            }

            if (useScheduler){
                if (items.none{it.downloadStartTime > 0L} && running.isEmpty() && !alarmScheduler.isDuringTheScheduledTime()) {
                    WorkManager.Companion.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                    return@collectLatest
                }
            }

            if (priorityItemIDs.isEmpty() && !continueAfterPriorityIds) {
                WorkManager.Companion.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                return@collectLatest
            }

            var concurrentDownloads = sharedPreferences.getInt("concurrent_downloads", 1) - running.size
            if (hasDownloadDelay) {
                concurrentDownloads = 1 - running.size
            }

            val eligibleDownloads = if (priorityItemIDs.isNotEmpty()) {
                val tmp = priorityItemIDs.take(concurrentDownloads)
                items.filter { it.id !in running && tmp.contains(it.id) }
            }else{
                items.take(concurrentDownloads).filter {  it.id !in running }
            }

            eligibleDownloads.forEach{downloadItem ->
                priorityItemIDs.remove(downloadItem.id)

                val notification = notificationUtil.createDownloadServiceNotification(openDownloadQueue, downloadItem.title.ifEmpty { downloadItem.url })
                notificationUtil.notify(downloadItem.id.toInt(), notification)

                workerScope.launch {
                    val processDownloadBlock : suspend () -> Unit = processDownloadBlock@{

                        downloadItem.status = DownloadRepository.Status.Active.toString()
                        dao.update(downloadItem)

                        if (hasDownloadDelay) {
                            val delaySec = if (minDelay >= maxDelay) minDelay else Random.Default.nextFloat() * (maxDelay - minDelay) + minDelay
                            if (delaySec > 0) {

                                workerScope.launch {
                                    delay(1000L)
                                    WorkerEventBus.post(
                                        WorkerProgress(
                                            0,
                                            context.getString(R.string.waiting_download_delay, "%.2f".format(delaySec)),
                                            downloadItem.id,
                                            downloadItem.logID
                                        )
                                    )
                                }

                                delay((delaySec * 1000).toLong())
                            }
                        }

                        val waitMs = PlatformRateLimiter.awaitTurnForUrl(downloadItem.url) { pendingWaitMs ->
                            val waitSec = (pendingWaitMs + 999L) / 1000L
                            WorkerEventBus.post(
                                WorkerProgress(
                                    0,
                                    context.getString(R.string.waiting_platform_cooldown, waitSec),
                                    downloadItem.id,
                                    downloadItem.logID
                                )
                            )
                        }
                        if (waitMs > 0L && this@DownloadWorker.isStopped) return@processDownloadBlock

                        val request = if (downloadItem.type == DownloadType.image) {
                            null
                        } else {
                            ytdlpUtil.buildYTDLRequest(downloadItem)
                        }

                        // DISABLED BECAUSE YT_DLP CONSIDERS DOWNLOAD FAILURE IF -U PART FAILS, ytdlnis #1043
    //                    val updateYTDLP = sharedPreferences.getBoolean("update_ytdlp_while_downloading", false)
    //                    if (updateYTDLP) {
    //                        request.addOption("-U")
    //                    }

                        downloadItem.status = DownloadRepository.Status.Active.toString()
                        if (downloadItem.type != DownloadType.image) {
                            CoroutineScope(Dispatchers.IO).launch {
                                delay(1500)
                                //update item if its incomplete
                                resultRepo.updateDownloadItem(downloadItem)?.apply {
                                    val status = dao.checkStatus(this.id)
                                    if (status == DownloadRepository.Status.Active) {
                                        dao.updateWithoutUpsert(this)
                                    }
                                }
                            }
                        }

                        val cacheDir = FileUtil.getCacheDownloadsPath(context)
                        val tempFileDir = File(cacheDir, downloadItem.id.toString())
                        tempFileDir.delete()
                        tempFileDir.mkdirs()

                        val downloadLocation = downloadItem.downloadPath
                        val keepCache = sharedPreferences.getBoolean("keep_cache", false)
                        val logDownloads = sharedPreferences.getBoolean(
                            "log_downloads",
                            false
                        ) && !downloadItem.incognito

                        val targetDir = File(FileUtil.formatPath(downloadItem.downloadPath))
                        val cacheDownloads = sharedPreferences.getBoolean("cache_downloads", true)
                        val writtenPath = downloadItem.type == DownloadType.command && downloadItem.format.format_note.contains("-P ")
                        val writeDirectly = writtenPath || (!cacheDownloads && targetDir.canWrite())


                        val commandString = if (request != null) {
                            ytdlpUtil.parseYTDLRequestString(request)
                        } else {
                            "ImageDownloadHelper (${downloadItem.container})"
                        }
                        val initialLogDetails = "Downloading:\n" +
                                "Title: ${downloadItem.title}\n" +
                                "URL: ${downloadItem.url}\n" +
                                "Type: ${downloadItem.type}\n" +
                                "Command:\n$commandString \n\n"
                        val logString = StringBuilder(initialLogDetails)
                        val logItem = LogItem(
                            0,
                            downloadItem.title.ifBlank { downloadItem.playlistTitle.ifEmpty { downloadItem.url } },
                            logString.toString(),
                            downloadItem.format,
                            downloadItem.type,
                            System.currentTimeMillis(),
                        )


                        runBlocking {
                            if (logDownloads) logItem.id = logRepo.insert(logItem)
                            downloadItem.logID = logItem.id
                            dao.update(downloadItem)
                        }

                        if (downloadItem.type == DownloadType.image) {
                            val imgResult = ImageDownloadHelper.downloadImage(context, downloadItem, tempFileDir) { p, msg ->
                                WorkerEventBus.post(
                                    WorkerProgress(p, msg, downloadItem.id, downloadItem.logID)
                                )
                                notificationUtil.updateDownloadNotification(
                                    downloadItem.id.toInt(),
                                    msg,
                                    p,
                                    0,
                                    downloadItem.title.ifEmpty { downloadItem.url },
                                    NotificationUtil.Companion.DOWNLOAD_SERVICE_CHANNEL_ID
                                )
                            }

                            imgResult.onSuccess {
                                notificationUtil.clearNotificationThrottleState(downloadItem.id.toInt())
                                val finalPaths = withContext(Dispatchers.IO) {
                                    FileUtil.moveFile(
                                        tempFileDir.absoluteFile,
                                        context,
                                        downloadLocation,
                                        keepCache
                                    ) { p ->
                                        WorkerEventBus.post(
                                            WorkerProgress(
                                                p,
                                                "Moving file to ${FileUtil.formatPath(downloadLocation)}",
                                                downloadItem.id,
                                                downloadItem.logID
                                            )
                                        )
                                    }
                                }.distinct().toMutableList()

                                FileUtil.scanMedia(finalPaths, context)

                                if (!downloadItem.incognito && finalPaths.isNotEmpty()) {
                                    val unixTime = System.currentTimeMillis() / 1000
                                    val firstFile = File(finalPaths.first())
                                    downloadItem.format.filesize = firstFile.length()
                                    downloadItem.format.container = firstFile.extension
                                    val historyItem = HistoryItem(
                                        0,
                                        downloadItem.url,
                                        downloadItem.title,
                                        downloadItem.author,
                                        downloadItem.duration,
                                        downloadItem.thumb,
                                        downloadItem.type,
                                        unixTime,
                                        finalPaths,
                                        downloadItem.website,
                                        downloadItem.format,
                                        downloadItem.format.filesize,
                                        downloadItem.id,
                                        "ImageDownloadHelper: ${downloadItem.format.url ?: downloadItem.thumb}"
                                    )
                                    historyDao.insert(historyItem)
                                }

                                withContext(Dispatchers.Main) {
                                    notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                                    notificationUtil.createDownloadFinished(
                                        downloadItem.id,
                                        downloadItem.title.ifEmpty { downloadItem.url },
                                        downloadItem.type,
                                        finalPaths.ifEmpty { null },
                                        resources
                                    )
                                }

                                dao.delete(downloadItem.id)
                                if (logDownloads) {
                                    logRepo.update(initialLogDetails + "Image downloaded successfully.", logItem.id, true)
                                }
                            }.onFailure { err ->
                                notificationUtil.clearNotificationThrottleState(downloadItem.id.toInt())
                                withContext(Dispatchers.Main) {
                                    notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                                }
                                if (logDownloads) {
                                    logRepo.update(err.message ?: "", logItem.id)
                                } else {
                                    logString.append("${err.message ?: err.stackTraceToString()}\n")
                                    logItem.content = logString.toString()
                                    val logID = logRepo.insert(logItem)
                                    downloadItem.logID = logID
                                }
                                ProcessCacheGuardian.purgeDownloadCache(
                                    context = context,
                                    downloadId = downloadItem.id,
                                    url = downloadItem.url,
                                    keepCache = false
                                )
                                downloadItem.status = DownloadRepository.Status.Error.toString()
                                runBlocking { dao.update(downloadItem) }
                                notificationUtil.createDownloadErrored(
                                    downloadItem.id,
                                    downloadItem.title.ifEmpty { downloadItem.url },
                                    err.message,
                                    downloadItem.logID,
                                    resources
                                )
                                WorkerEventBus.post(
                                    WorkerProgress(100, err.toString(), downloadItem.id, downloadItem.logID)
                                )
                            }
                            return@processDownloadBlock
                        }

                        val activeRequest = request!!
                        runCatching {
                            RuntimeManager.getInstance().destroyProcessById(downloadItem.id.toString())
                            RuntimeManager.getInstance().execute(
                                request = activeRequest,
                                processId = downloadItem.id.toString(),
                                redirectErrorStream = true,
                                usingCacheDir = true
                            ) { progress, _, line ->
                                WorkerEventBus.post(
                                    WorkerProgress(
                                        progress.toInt(),
                                        line,
                                        downloadItem.id,
                                        downloadItem.logID
                                    )
                                )
                                val title: String = downloadItem.title.ifEmpty { downloadItem.url }
                                notificationUtil.updateDownloadNotification(
                                    downloadItem.id.toInt(),
                                    line, progress.toInt(), 0, title,
                                    NotificationUtil.Companion.DOWNLOAD_SERVICE_CHANNEL_ID
                                )
                                CoroutineScope(Dispatchers.IO).launch {
                                    if (logDownloads) {
                                        logRepo.update(line, logItem.id)
                                    }
                                    logString.append("$line\n")
                                }
                            }
                        }.onSuccess {
                            notificationUtil.clearNotificationThrottleState(downloadItem.id.toInt())
                            resultRepo.updateDownloadItem(downloadItem)?.apply {
                                dao.updateWithoutUpsert(this)
                            }
                            //val wasQuickDownloaded = resultDao.getCountInt() == 0
                            runBlocking {
                                var finalPaths = mutableListOf<String>()

                                //after move paths
                                WorkerEventBus.post(
                                    WorkerProgress(
                                        100,
                                        "Scanning Files",
                                        downloadItem.id,
                                        downloadItem.logID
                                    )
                                )
                                val outputSequence = it.out.split("\n")

                                if (writeDirectly) {
                                    val fromAfterMove = outputSequence.asSequence()
                                        .filter { o -> o.startsWith("'/storage") }
                                    val fromSplitChapters = outputSequence.asSequence()
                                        .filter { o -> o.startsWith("[SplitChapters]") && o.contains("Destination: ") }
                                        .map { o -> o.substringAfter("Destination: ") }

                                    val moveFilesRegex = Regex("""^\[MoveFiles]\s+Moving file ".*" to "(.*)"\s*$""")
                                    val fromMoveFiles = outputSequence.asSequence()
                                        .mapNotNull { moveFilesRegex.find(it)?.groupValues?.get(1) }

                                    finalPaths = (fromAfterMove + fromSplitChapters + fromMoveFiles)
                                        .map { o -> o.trim().removeSurrounding("'").removeSurrounding("\"") }
                                        .map { o -> o.fixYtdlpPath() }
                                        .filter { o -> o.isNotBlank() }
                                        .distinct()
                                        .toMutableList()
                                }

                                //move files manually if external provider
                                try {
                                    val otherPaths = withContext(Dispatchers.IO) {
                                        FileUtil.moveFile(
                                            tempFileDir.absoluteFile,
                                            context,
                                            downloadLocation,
                                            keepCache
                                        ) { p ->
                                            WorkerEventBus.post(
                                                WorkerProgress(
                                                    p,
                                                    "Moving file to ${
                                                        FileUtil.formatPath(downloadLocation)
                                                    }",
                                                    downloadItem.id,
                                                    downloadItem.logID
                                                )
                                            )
                                        }
                                    }.filter { p -> !p.matches("\\.(description)|(txt)\$".toRegex()) }
                                        .toMutableList()

                                    if (otherPaths.isNotEmpty()) {
                                        WorkerEventBus.post(
                                            WorkerProgress(
                                                100,
                                                "Moved file to ${
                                                    FileUtil.formatPath(
                                                        downloadLocation
                                                    )
                                                }",
                                                downloadItem.id,
                                                downloadItem.logID
                                            )
                                        )
                                    }

                                    finalPaths.addAll(otherPaths)

                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    if (e.message?.isNotBlank() == true) {
                                        handler.postDelayed({
                                            Toast.makeText(
                                                context,
                                                e.message,
                                                Toast.LENGTH_SHORT
                                            )
                                                .show()
                                        }, 1000)
                                    }

                                }

                                val nonMediaExtensions = mutableListOf<String>().apply {
                                    addAll(context.getStringArray(R.array.thumbnail_containers_values))
                                    addAll(
                                        context.getStringArray(R.array.sub_formats_values)
                                            .filter { it.isNotBlank() })
                                    add("description")
                                    add("txt")
                                }
                                finalPaths = finalPaths.filter { path ->
                                    !nonMediaExtensions.any {
                                        path.endsWith(it)
                                    }
                                }.distinct().toMutableList()
                                finalPaths.sortBy { p -> File(p).lastModified() }

                                FileUtil.scanMedia(finalPaths, context)

                                FileUtil.deleteConfigFiles(activeRequest)

                                //put download in history
                                if (!downloadItem.incognito) {
                                    if (activeRequest.hasOption("--download-archive") && finalPaths.isEmpty()) {
                                        handler.postDelayed({
                                            Toast.makeText(
                                                context,
                                                resources.getString(R.string.download_already_exists),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }, 100)
                                    } else {
                                        if (finalPaths.isNotEmpty()) {
                                            val unixTime = System.currentTimeMillis() / 1000
                                            finalPaths.first().apply {
                                                val file = File(this)
                                                var duration = downloadItem.duration
                                                val d = file.getMediaDuration(context)
                                                if (d > 0) duration = d.toStringDuration(Locale.US)

                                                downloadItem.format.filesize = file.length()
                                                downloadItem.format.container = file.extension
                                                downloadItem.duration = duration
                                            }

                                            val historyItem = HistoryItem(
                                                0,
                                                downloadItem.url,
                                                downloadItem.title,
                                                downloadItem.author,
                                                downloadItem.duration,
                                                downloadItem.thumb,
                                                downloadItem.type,
                                                unixTime,
                                                finalPaths,
                                                downloadItem.website,
                                                downloadItem.format,
                                                downloadItem.format.filesize,
                                                downloadItem.id,
                                                commandString
                                            )
                                            historyDao.insert(historyItem)
                                        }
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                                    notificationUtil.createDownloadFinished(
                                        downloadItem.id,
                                        downloadItem.title,
                                        downloadItem.type,
                                        if (finalPaths.isEmpty()) null else finalPaths,
                                        resources
                                    )
                                }

                                //                            if (wasQuickDownloaded && createResultItem){
                                //                                runCatching {
                                //                                    eventBus.post(WorkerProgress(100, "Creating Result Items", downloadItem.id))
                                //                                    runBlocking {
                                //                                        infoUtil.getFromYTDL(downloadItem.url).forEach { res ->
                                //                                            if (res != null) {
                                //                                                resultDao.insert(res)
                                //                                            }
                                //                                        }
                                //                                    }
                                //                                }
                                //                            }

                                dao.delete(downloadItem.id)

                                if (logDownloads) {
                                    logRepo.update(initialLogDetails + it.out, logItem.id, true)
                                }
                            }

                        }.onFailure {
                            notificationUtil.clearNotificationThrottleState(downloadItem.id.toInt())
                            FileUtil.deleteConfigFiles(activeRequest)
                            if (this@DownloadWorker.isStopped || it is RuntimeManager.CanceledException) {
                                withContext(Dispatchers.Main) {
                                    notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                                }
                                return@onFailure
                            }

                            if (PlatformRateLimiter.isRateLimitError(it.message)) {
                                PlatformRateLimiter.recordRateLimitForUrl(downloadItem.url)
                            }

                            val fallbackEnabled = sharedPreferences.getBoolean("invisible_local_fallback", true)
                            val canUseFallback = fallbackEnabled &&
                                downloadItem.type != DownloadType.command &&
                                downloadItem.url.startsWith("http", ignoreCase = true)

                            var finalError: Throwable = it
                            if (canUseFallback) {
                                logString.append("Primary extractor failed: ${it.message ?: it.javaClass.simpleName}\n")
                                tempFileDir.deleteRecursively()
                                tempFileDir.mkdirs()

                                val fallbackResult = LocalFallbackEngine.download(
                                    context = context,
                                    item = downloadItem,
                                    tempFileDir = tempFileDir,
                                    processId = downloadItem.id.toString(),
                                    isCancelled = { this@DownloadWorker.isStopped }
                                ) { p, msg ->
                                    WorkerEventBus.post(
                                        WorkerProgress(p, msg, downloadItem.id, downloadItem.logID)
                                    )
                                    notificationUtil.updateDownloadNotification(
                                        downloadItem.id.toInt(),
                                        msg,
                                        p,
                                        0,
                                        downloadItem.title.ifEmpty { downloadItem.url },
                                        NotificationUtil.Companion.DOWNLOAD_SERVICE_CHANNEL_ID
                                    )
                                }

                                if (fallbackResult.isSuccess) {
                                    val fb = fallbackResult.getOrThrow()
                                    if (downloadItem.title.isBlank() && !fb.resolvedTitle.isNullOrBlank()) {
                                        downloadItem.title = fb.resolvedTitle
                                    }
                                    if (downloadItem.author.isBlank() && !fb.resolvedAuthor.isNullOrBlank()) {
                                        downloadItem.author = fb.resolvedAuthor
                                    }
                                    if (downloadItem.thumb.isBlank() && !fb.resolvedThumb.isNullOrBlank()) {
                                        downloadItem.thumb = fb.resolvedThumb
                                    }

                                    notificationUtil.clearNotificationThrottleState(downloadItem.id.toInt())
                                    runBlocking {
                                        var finalPaths = withContext(Dispatchers.IO) {
                                            FileUtil.moveFile(
                                                tempFileDir.absoluteFile,
                                                context,
                                                downloadLocation,
                                                keepCache
                                            ) { p ->
                                                WorkerEventBus.post(
                                                    WorkerProgress(
                                                        p,
                                                        "Moving file to ${FileUtil.formatPath(downloadLocation)}",
                                                        downloadItem.id,
                                                        downloadItem.logID
                                                    )
                                                )
                                            }.filter { p -> !p.matches("\\.(description)|(txt)\$".toRegex()) }
                                                .toMutableList()
                                        }

                                        val nonMediaExtensions = mutableListOf<String>().apply {
                                            addAll(context.getStringArray(R.array.thumbnail_containers_values))
                                            addAll(
                                                context.getStringArray(R.array.sub_formats_values)
                                                    .filter { s -> s.isNotBlank() }
                                            )
                                            add("description")
                                            add("txt")
                                        }
                                        finalPaths = finalPaths.filter { path ->
                                            !nonMediaExtensions.any { ext -> path.endsWith(ext) }
                                        }.distinct().toMutableList()
                                        finalPaths.sortBy { p -> File(p).lastModified() }

                                        FileUtil.scanMedia(finalPaths, context)

                                        if (!downloadItem.incognito && finalPaths.isNotEmpty()) {
                                            val unixTime = System.currentTimeMillis() / 1000
                                            val firstFile = File(finalPaths.first())
                                            val d = firstFile.getMediaDuration(context)
                                            if (d > 0) downloadItem.duration = d.toStringDuration(Locale.US)
                                            downloadItem.format.filesize = firstFile.length()
                                            downloadItem.format.container = firstFile.extension

                                            val historyItem = HistoryItem(
                                                0,
                                                downloadItem.url,
                                                downloadItem.title,
                                                downloadItem.author,
                                                downloadItem.duration,
                                                downloadItem.thumb,
                                                downloadItem.type,
                                                unixTime,
                                                finalPaths,
                                                downloadItem.website,
                                                downloadItem.format,
                                                downloadItem.format.filesize,
                                                downloadItem.id,
                                                "$commandString\n[fallback:${fb.engineUsed}]"
                                            )
                                            historyDao.insert(historyItem)
                                        }

                                        withContext(Dispatchers.Main) {
                                            notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                                            notificationUtil.createDownloadFinished(
                                                downloadItem.id,
                                                downloadItem.title.ifEmpty { downloadItem.url },
                                                downloadItem.type,
                                                finalPaths.ifEmpty { null },
                                                resources
                                            )
                                        }

                                        dao.delete(downloadItem.id)
                                        if (logDownloads) {
                                            logRepo.update(
                                                initialLogDetails + logString.toString() + "\n[fallback:${fb.engineUsed}] completed",
                                                logItem.id,
                                                true
                                            )
                                        }
                                    }
                                    return@onFailure
                                } else {
                                    val fbErr = fallbackResult.exceptionOrNull()
                                    if (this@DownloadWorker.isStopped || fbErr is RuntimeManager.CanceledException) {
                                        withContext(Dispatchers.Main) {
                                            notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                                        }
                                        return@onFailure
                                    }
                                    if (fbErr != null) {
                                        logString.append("Fallback engine failed: ${fbErr.message ?: fbErr.stackTraceToString()}\n")
                                        finalError = it
                                    }
                                    tempFileDir.deleteRecursively()
                                }
                            }

                            withContext(Dispatchers.Main) {
                                notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                            }
                            if (finalError.message?.contains("JSONDecodeError") == true) {
                                val cachePath = FileUtil.getInfoJsonPath(context)
                                val infoJsonName = MessageDigest.getInstance("MD5")
                                    .digest(downloadItem.url.toByteArray()).toHexString()
                                FileUtil.deleteFile("${cachePath}/${infoJsonName}.info.json")
                            }

                            if (logDownloads) {
                                logRepo.update(logString.toString() + "\n" + (finalError.message ?: ""), logItem.id)
                            } else {
                                logString.append("${finalError.message ?: finalError.stackTraceToString()}\n")
                                logItem.content = logString.toString()
                                val logID = logRepo.insert(logItem)
                                downloadItem.logID = logID
                            }


                            RuntimeManager.getInstance().destroyProcessById(downloadItem.id.toString())
                            ProcessCacheGuardian.purgeDownloadCache(
                                context = context,
                                downloadId = downloadItem.id,
                                url = downloadItem.url,
                                keepCache = false
                            )

                            Log.e(TAG, context.getString(R.string.failed_download), finalError)
                            notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())

                            downloadItem.status = DownloadRepository.Status.Error.toString()
                            runBlocking {
                                dao.update(downloadItem)
                            }

                            notificationUtil.createDownloadErrored(
                                downloadItem.id,
                                downloadItem.title.ifEmpty { downloadItem.url },
                                finalError.message,
                                downloadItem.logID,
                                resources
                            )

                            WorkerEventBus.post(
                                WorkerProgress(
                                    100,
                                    finalError.toString(),
                                    downloadItem.id,
                                    downloadItem.logID
                                )
                            )
                        }
                    }

                    try {
                        if (hasDownloadDelay) {
                            downloadLock.withLock {
                                processDownloadBlock()
                            }
                        } else {
                            processDownloadBlock()
                        }
                    } finally {
                        // finished, failed, cancelled or paused: drop the cached progress
                        WorkerEventBus.clear(downloadItem.id)
                    }
                }

            }

            if (eligibleDownloads.isNotEmpty()){
                eligibleDownloads.forEach {
                    it.status = DownloadRepository.Status.Active.toString()
                    priorityItemIDs.remove(it.id)
                }
                dao.updateMultiple(eligibleDownloads)
            }
        }

        return Result.success()
    }



    companion object {
        val runningYTDLInstances: MutableList<Long> = mutableListOf()
        const val TAG = "DownloadWorker"
        private val downloadLock = Mutex()
    }

    class WorkerProgress(
        val progress: Int,
        val output: String,
        val downloadItemID: Long,
        val logItemID: Long?
    )

}