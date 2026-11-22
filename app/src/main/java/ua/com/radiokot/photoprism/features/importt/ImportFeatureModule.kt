package ua.com.radiokot.photoprism.features.importt

import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import ua.com.radiokot.photoprism.di.APP_NO_BACKUP_PREFERENCES
import ua.com.radiokot.photoprism.env.data.model.EnvSession
import ua.com.radiokot.photoprism.features.albums.albumsFeatureModule
import ua.com.radiokot.photoprism.features.importt.logic.ParseImportIntentUseCase
import ua.com.radiokot.photoprism.features.importt.picker.logic.LibraryHashChecker
import ua.com.radiokot.photoprism.features.importt.picker.logic.LocalMediaHasher
import ua.com.radiokot.photoprism.features.importt.picker.logic.LocalMediaReader
import ua.com.radiokot.photoprism.features.importt.picker.view.model.LocalMediaPickerViewModel
import ua.com.radiokot.photoprism.features.importt.picker.data.storage.LibraryHashesRepository
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueueCopies
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueuePreferences
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueueRepository
import ua.com.radiokot.photoprism.features.importt.queue.logic.ImportQueueRunGuard
import ua.com.radiokot.photoprism.features.importt.queue.logic.ImportQueueUploadProgress
import ua.com.radiokot.photoprism.features.importt.queue.view.model.ImportQueueViewModel
import ua.com.radiokot.photoprism.features.importt.view.ImportNotificationsManager
import ua.com.radiokot.photoprism.features.importt.view.model.ImportViewModel
import java.io.File

val importFeatureModule = module {
    includes(albumsFeatureModule)

    scope<EnvSession> {
        viewModelOf(::ImportViewModel)
        viewModelOf(::ImportQueueViewModel)
        viewModelOf(::LocalMediaPickerViewModel)
        scoped {
            LibraryHashChecker(
                photosService = get(),
                libraryHashesRepository = get(),
                libraryRootUrl = get<EnvSession>().envConnectionParams.rootUrl.toString(),
            )
        } bind LibraryHashChecker::class
    }

    single {
        LocalMediaReader(
            context = androidApplication(),
        )
    } bind LocalMediaReader::class

    single {
        LocalMediaHasher(
            database = get(),
            contentResolver = androidApplication().contentResolver,
        )
    } bind LocalMediaHasher::class

    single {
        ParseImportIntentUseCase(
            contentResolver = androidApplication().contentResolver,
        )
    } bind ParseImportIntentUseCase::class

    single {
        ImportQueueCopies(
            directory = File(androidApplication().noBackupFilesDir, "import-queue-copies"),
            contentResolver = androidApplication().contentResolver,
        )
    } bind ImportQueueCopies::class

    single {
        ImportQueuePreferences(
            preferences = get(named(APP_NO_BACKUP_PREFERENCES)),
        )
    } bind ImportQueuePreferences::class

    singleOf(::ImportNotificationsManager)
    singleOf(::ImportQueueRepository)
    singleOf(::ImportQueueUploadProgress)
    singleOf(::ImportQueueRunGuard)
    singleOf(::LibraryHashesRepository)
}
