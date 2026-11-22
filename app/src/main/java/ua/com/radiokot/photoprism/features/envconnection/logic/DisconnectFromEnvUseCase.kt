package ua.com.radiokot.photoprism.features.envconnection.logic

import android.app.Application
import android.webkit.CookieManager
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.kotlin.toCompletable
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.base.data.storage.ObjectPersistence
import ua.com.radiokot.photoprism.env.data.model.EnvAuth
import ua.com.radiokot.photoprism.env.data.model.EnvSession
import ua.com.radiokot.photoprism.env.data.storage.EnvSessionHolder
import ua.com.radiokot.photoprism.env.data.storage.SessionCapabilitiesRepository
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.setManifestComponentEnabled
import ua.com.radiokot.photoprism.features.ext.memories.data.storage.MemoriesRepository
import ua.com.radiokot.photoprism.features.importt.picker.data.storage.LibraryHashesRepository
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueueRepository
import ua.com.radiokot.photoprism.features.importt.queue.logic.ImportQueueWorker
import ua.com.radiokot.photoprism.features.importt.view.ImportActivity
import java.io.File

/**
 * Clears the [envSessionHolder].
 * Clears all the rest storages if present if present.
 */
class DisconnectFromEnvUseCase(
    private val envSessionHolder: EnvSessionHolder,
    private val envSessionPersistence: ObjectPersistence<EnvSession>?,
    private val envAuthPersistence: ObjectPersistence<EnvAuth>?,
    private val cacheDirectories: Iterable<File>?,
    private val cookieManager: CookieManager?,
    private val memoriesRepository: MemoriesRepository?,
    private val importQueueRepository: ImportQueueRepository?,
    private val libraryHashesRepository: LibraryHashesRepository?,
    private val sessionCapabilitiesRepository: SessionCapabilitiesRepository?,
    private val application: Application,
) {
    private val log = kLogger("DisconnectFromEnvUseCase")

    operator fun invoke(): Completable = {
        // Uploads must not continue without the session
        // or end up in the account connected next.
        ImportQueueWorker.pause(application)
        importQueueRepository?.clear()?.also {
            log.debug { "invoke(): import_queue_cleared" }
        }

        libraryHashesRepository?.clear()?.also {
            log.debug { "invoke(): library_hashes_cleared" }
        }

        sessionCapabilitiesRepository?.clear()?.also {
            log.debug { "invoke(): session_capabilities_cleared" }
        }

        envSessionHolder.clear().also {
            log.debug { "invoke(): session_holder_cleared" }
        }

        envSessionPersistence?.clear()?.also {
            log.debug { "invoke(): session_persistence_cleared" }
        }

        envAuthPersistence?.clear()?.also {
            log.debug { "invoke(): auth_persistence_cleared" }
        }

        cacheDirectories?.forEach { cacheDir ->
            cacheDir.deleteRecursively()

            log.debug {
                "invoke(): cache_dir_deleted:" +
                        "\ndir=$cacheDir"
            }
        }

        cookieManager?.removeAllCookies(null)?.also {
            log.debug { "invoke(): cookie_manager_cleared" }
        }

        memoriesRepository?.clear()?.blockingAwait()?.also {
            log.debug { "invoke(): memories_cleared" }
        }

        application.setManifestComponentEnabled(
            componentClass = ImportActivity::class.java,
            isEnabled = false
        )

        log.debug { "invoke(): disabled_import" }
    }
        .toCompletable()
        .subscribeOn(Schedulers.io())
}
