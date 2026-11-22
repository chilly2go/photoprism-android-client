package ua.com.radiokot.photoprism.features.importt.queue.logic

import android.Manifest
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.scope.Scope
import ua.com.radiokot.photoprism.di.DI_SCOPE_SESSION
import ua.com.radiokot.photoprism.env.data.model.EnvSession
import ua.com.radiokot.photoprism.env.data.storage.SessionCapabilitiesRepository
import ua.com.radiokot.photoprism.extension.isSelfPermissionGranted
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.albums.data.storage.AlbumsRepository
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueueRepository
import ua.com.radiokot.photoprism.features.importt.view.ImportNotificationsManager

/**
 * Works through the upload queue of the current library, one batch after another.
 * There is only one such worker at a time, see [schedule].
 */
class ImportQueueWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams),
    KoinComponent {

    private val log = kLogger("ImportQueueWorker")
    private val sessionScope: Scope?
        get() = getKoin().getScopeOrNull(DI_SCOPE_SESSION)
    private val repository: ImportQueueRepository by inject()
    private val notificationsManager: ImportNotificationsManager by inject()
    private val runGuard: ImportQueueRunGuard by inject()

    @Volatile
    private var lastProgress: ImportQueueProcessor.Progress? = null
    private var lastProgressNotificationAt = 0L

    override fun doWork(): Result {
        val sessionScope = sessionScope

        if (sessionScope == null) {
            log.debug {
                "doWork(): skip_as_missing_session_scope"
            }

            return Result.success()
        }

        // A previous worker may still be finishing its upload.
        if (!runGuard.acquire(::isStopped)) {
            log.debug {
                "doWork(): stopped_while_waiting_for_previous_run"
            }

            return Result.success()
        }

        try {
            return doGuardedWork(sessionScope)
        } finally {
            runGuard.release()
        }
    }

    private fun doGuardedWork(sessionScope: Scope): Result {
        // No other worker runs now, so the items in progress are leftovers.
        repository.requeueInterruptedItems()

        if (!repository.hasWork()) {
            log.debug {
                "doGuardedWork(): skip_as_nothing_to_do"
            }

            return Result.success()
        }

        updateForeground(force = true)

        val processor = ImportQueueProcessor(
            repository = repository,
            contentResolver = applicationContext.contentResolver,
            sessionService = sessionScope.get(),
            uploadService = sessionScope.get(),
            filesService = sessionScope.get(),
            photosService = sessionScope.get(),
            libraryHashesRepository = get(),
            uploadProgress = get(),
            libraryRootUrl = sessionScope.get<EnvSession>().envConnectionParams.rootUrl.toString(),
            canReadOriginals = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && applicationContext.isSelfPermissionGranted(Manifest.permission.ACCESS_MEDIA_LOCATION)
            },
            // Setting the favorite is exactly the "photos update" permission.
            canUpdatePhotos = {
                sessionScope.getOrNull<SessionCapabilitiesRepository>()
                    ?.current
                    ?.canSetFavorite != false
            },
            isStopped = ::isStopped,
            onProgress = { progress ->
                lastProgress = progress
                updateForeground(force = false)
            },
        )

        val result = try {
            processor.run()
        } catch (error: ImportQueueProcessor.NotAllowedException) {
            log.warn(error) {
                "doGuardedWork(): not_allowed"
            }

            repository.requeueInterruptedItems()
            notificationsManager.notifyQueueNotAllowed()

            // Retrying won't help until the permissions are changed.
            return Result.success()
        } catch (error: Exception) {
            log.error(error) {
                "doGuardedWork(): processing_failed"
            }

            // Show the unfinished files as waiting rather than uploading.
            repository.requeueInterruptedItems()

            if (!isStopped) {
                notificationsManager.notifyQueueInterrupted()
            }

            // Retry later, the progress is kept in the queue.
            return Result.retry()
        } finally {
            repository.deleteUnneededCopies()
        }

        if (isStopped) {
            // Paused: show the unfinished files as waiting.
            repository.requeueInterruptedItems()
        }

        if (result.importedCount > 0) {
            // Update albums, as new ones may have been created by the import.
            sessionScope.getOrNull<AlbumsRepository>()?.apply {
                invalidate()
                updateIfEverUpdated()
            }
        }

        if (!isStopped && result.importedCount + result.alreadyInLibraryCount + result.failedCount > 0) {
            notificationsManager.notifyQueueFinished(result)
        }

        log.debug {
            "doGuardedWork(): completed:" +
                    "\nresult=$result," +
                    "\nisStopped=$isStopped"
        }

        return Result.success()
    }

    override fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo()

    @Synchronized
    private fun updateForeground(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressNotificationAt < PROGRESS_NOTIFICATION_INTERVAL_MS) {
            return
        }
        lastProgressNotificationAt = now

        try {
            setForegroundAsync(createForegroundInfo())
        } catch (error: Exception) {
            // Can't run in foreground, for example,
            // when started from the background on Android 12+.
            log.warn(error) {
                "updateForeground(): failed"
            }
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = notificationsManager.getQueueProgressNotification(
            progress = lastProgress,
            pauseIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(
                ImportNotificationsManager.QUEUE_PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        else
            ForegroundInfo(
                ImportNotificationsManager.QUEUE_PROGRESS_NOTIFICATION_ID,
                notification,
            )
    }

    companion object {
        const val TAG = "ImportQueue"
        private const val UNIQUE_WORK_NAME = "ImportQueue"
        private const val PROGRESS_NOTIFICATION_INTERVAL_MS = 1000L

        /**
         * Makes sure the queue is being worked through.
         * If the worker is already running, another run is appended,
         * so items added at the very end of the current run are not missed.
         *
         * @param allowMobileData whether to upload over mobile data, not only over Wi-Fi
         * @param restart whether to replace the running worker,
         * for example, to apply changed network constraints
         */
        fun schedule(
            context: Context,
            allowMobileData: Boolean,
            restart: Boolean = false,
        ) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    if (restart)
                        ExistingWorkPolicy.REPLACE
                    else
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<ImportQueueWorker>()
                        .addTag(TAG)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(
                                    if (allowMobileData)
                                        NetworkType.CONNECTED
                                    else
                                        NetworkType.UNMETERED
                                )
                                .build()
                        )
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                )
        }

        /**
         * Stops working through the queue, keeping its content.
         */
        fun pause(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        fun getWorkInfos(context: Context) =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(UNIQUE_WORK_NAME)
    }
}
