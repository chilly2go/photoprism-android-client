package ua.com.radiokot.photoprism.features.importt.view.model

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.kotlin.toCompletable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import ua.com.radiokot.photoprism.env.data.model.EnvSession
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.isSelfPermissionGranted
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.observeOnMain
import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum
import ua.com.radiokot.photoprism.features.importt.logic.ParseImportIntentUseCase
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueueRepository
import ua.com.radiokot.photoprism.features.importt.queue.logic.ImportQueueWorker
import ua.com.radiokot.photoprism.features.importt.model.ImportableFile
import ua.com.radiokot.photoprism.features.importt.model.reportedSizeMb
import ua.com.radiokot.photoprism.features.albums.data.storage.AlbumsRepository

class ImportViewModel(
    private val parseImportIntentUseCase: ParseImportIntentUseCase,
    private val albumsRepository: AlbumsRepository,
    private val session: EnvSession,
    private val importQueueRepository: ImportQueueRepository,
    application: Application,
) : AndroidViewModel(application) {
    private val log = kLogger("ImportVM")
    private val context = getApplication<Application>()
    private var isInitialized = false
    private lateinit var files: List<ImportableFile>
    private val permissionsToCheckBeforeStart = mutableListOf<String>()
    private var albums: Set<DestinationAlbum> = emptySet()

    val summary: MutableLiveData<Summary> = MutableLiveData()
    val isNotificationPermissionRationaleVisible: MutableLiveData<Boolean> = MutableLiveData(false)
    val isMediaPermissionRationaleVisible: MutableLiveData<Boolean> = MutableLiveData(false)
    private val eventsSubject = PublishSubject.create<Event>()
    val events = eventsSubject.observeOnMain()
    val isStartButtonEnabled: MutableLiveData<Boolean> = MutableLiveData(true)

    fun initOnce(importIntent: Intent) {
        if (isInitialized) {
            return
        }

        // Querying thousands of URIs takes a while,
        // so the start is only possible once they are parsed.
        isStartButtonEnabled.value = false

        Single.fromCallable { parseImportIntentUseCase(importIntent) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onSuccess = { parsedFiles ->
                    files = parsedFiles

                    summary.value = Summary(
                        libraryRootUrl = session.envConnectionParams.rootUrl.toString(),
                        fileCount = parsedFiles.size,
                        reportedSizeMb = parsedFiles.reportedSizeMb,
                        albums = albums.map(DestinationAlbum::title),
                    )
                    isStartButtonEnabled.value = parsedFiles.isNotEmpty()

                    log.debug {
                        "initOnce(): files_parsed:" +
                                "\nsummary=${summary.value}"
                    }
                },
                onError = { error ->
                    log.error(error) {
                        "initOnce(): failed_parsing_intent"
                    }

                    eventsSubject.onNext(Event.Finish)
                }
            )
            .autoDispose(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && !context.isSelfPermissionGranted(Manifest.permission.ACCESS_MEDIA_LOCATION)
        ) {
            isMediaPermissionRationaleVisible.value = true
            permissionsToCheckBeforeStart += Manifest.permission.ACCESS_MEDIA_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && !context.isSelfPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            isNotificationPermissionRationaleVisible.value = true
            permissionsToCheckBeforeStart += Manifest.permission.POST_NOTIFICATIONS
        }

        // Update albums now for faster selection.
        albumsRepository.updateIfNotFresh()

        isInitialized = true

        log.debug {
            "initOnce(): initialized:" +
                    "\npermissionsToCheckBeforeStart=$permissionsToCheckBeforeStart"
        }
    }

    fun onStartClicked() {
        if (permissionsToCheckBeforeStart.isNotEmpty()) {
            log.debug {
                "onStartClicked(): requesting_permissions_first"
            }

            eventsSubject.onNext(Event.RequestPermissions(permissionsToCheckBeforeStart.toTypedArray()))
        } else {
            log.debug {
                "onStartClicked(): starting_import_in_background"
            }

            startImportInBackgroundAndFinishAsync()
                .subscribe()
                .autoDispose(this)
        }
    }

    fun onCancelClicked() {
        log.debug {
            "onCancelClicked(): finishing"
        }

        eventsSubject.onNext(Event.Finish)
    }

    fun onAlbumsClicked() {
        log.debug {
            "onAlbumsClicked(): opening_selection"
        }

        eventsSubject.onNext(
            Event.OpenAlbumSelectionForResult(
                currentlySelectedAlbums = albums,
            )
        )
    }

    fun onAlbumSelectionResult(selectedAlbums: Set<DestinationAlbum>) {
        log.debug {
            "onAlbumsSelected(): updating_selection:" +
                    "\nselectedAlbums=${selectedAlbums.size}"
        }

        this.albums = selectedAlbums

        summary.value = summary.value?.copy(
            albums = selectedAlbums.map(DestinationAlbum::title),
        )
    }

    fun onPermissionsResult(results: Map<String, Boolean>) {
        log.debug {
            "onPermissionsResult(): result_received:" +
                    "\nresults:${results.entries}"
        }

        log.debug {
            "onPermissionsResult(): starting_import_in_background"
        }

        startImportInBackgroundAndFinishAsync()
            .subscribe()
            .autoDispose(this)
    }

    private fun startImportInBackgroundAndFinishAsync() = {
        eventsSubject.onNext(Event.ShowStartedInBackgroundMessage)
        isStartButtonEnabled.postValue(false)

        files.forEach { file ->
            val uri = file.contentUri.toUri()

            // Allow reading the URIs after the activity is finished.
            context.grantUriPermission(
                context.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )

            // The queue may be worked through after the app process is restarted,
            // which requires a persisted permission. Not all the providers support it.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
        }

        val batchId = importQueueRepository.addBatch(
            files = files,
            albums = albums,
            libraryRootUrl = session.envConnectionParams.rootUrl.toString(),
        )

        ImportQueueWorker.schedule(context)

        log.debug {
            "startImportInBackgroundAndFinish(): finishing_after_adding_to_queue:" +
                    "\nbatchId=$batchId"
        }

        eventsSubject.onNext(Event.Finish)
    }.toCompletable().subscribeOn(Schedulers.io())

    data class Summary(
        val libraryRootUrl: String,
        val fileCount: Int,
        val reportedSizeMb: Double,
        val albums: Collection<String> = emptySet(),
    )

    sealed interface Event {
        object Finish : Event
        object ShowStartedInBackgroundMessage : Event

        /**
         * Request given [permissions] reporting the result
         * to the [onPermissionsResult] method.
         */
        class RequestPermissions(
            val permissions: Array<String>,
        ) : Event

        /**
         * Open import albums selection to get the result.
         *
         * [onAlbumSelectionResult] must be called when the result is obtained.
         */
        class OpenAlbumSelectionForResult(
            val currentlySelectedAlbums: Set<DestinationAlbum>,
        ) : Event
    }
}
