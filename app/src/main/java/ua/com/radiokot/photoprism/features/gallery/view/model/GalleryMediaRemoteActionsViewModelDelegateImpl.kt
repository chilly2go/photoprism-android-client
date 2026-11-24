package ua.com.radiokot.photoprism.features.gallery.view.model

import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum
import ua.com.radiokot.photoprism.env.data.storage.SessionCapabilitiesRepository
import ua.com.radiokot.photoprism.features.gallery.data.model.GalleryMedia
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.logic.ApproveGalleryMediaUseCase
import ua.com.radiokot.photoprism.features.gallery.logic.RestoreGalleryMediaUseCase
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaRemoteActionsViewModel.StatusChange
import ua.com.radiokot.photoprism.features.gallery.logic.AddGalleryMediaToAlbumUseCase
import ua.com.radiokot.photoprism.features.gallery.logic.ArchiveGalleryMediaUseCase
import ua.com.radiokot.photoprism.features.gallery.logic.DeleteGalleryMediaUseCase
import ua.com.radiokot.photoprism.features.gallery.logic.RemoveGalleryMediaFromAlbumUseCase
import ua.com.radiokot.photoprism.features.viewer.logic.UpdateGalleryMediaAttributesUseCase

class GalleryMediaRemoteActionsViewModelDelegateImpl(
    private val archiveGalleryMediaUseCase: ArchiveGalleryMediaUseCase,
    private val restoreGalleryMediaUseCase: RestoreGalleryMediaUseCase,
    private val approveGalleryMediaUseCase: ApproveGalleryMediaUseCase,
    private val deleteGalleryMediaUseCase: DeleteGalleryMediaUseCase,
    private val galleryMediaRepositoryFactory: SimpleGalleryMediaRepository.Factory,
    private val sessionCapabilitiesRepository: SessionCapabilitiesRepository?,
    private val updateGalleryMediaAttributesUseCase: UpdateGalleryMediaAttributesUseCase,
    private val addGalleryMediaToAlbumUseCase: AddGalleryMediaToAlbumUseCase,
    private val removeGalleryMediaFromAlbumUseCase: RemoveGalleryMediaFromAlbumUseCase,
) : ViewModel(),
    GalleryMediaRemoteActionsViewModelDelegate {

    private val log = kLogger("GalleryMediaRemoteActionsVMDI")

    override val galleryMediaRemoteActionsEvents: PublishSubject<GalleryMediaRemoteActionsViewModel.Event> =
        PublishSubject.create()

    private var doOnDeletingConfirmed = { }
    private var doOnRestoringConfirmed = { }
    private var doOnUndo: (() -> Unit)? = null
    private var doOnAlbumForAddingSelected = { _: DestinationAlbum -> }

    override fun deleteGalleryMedia(
        mediaUids: Collection<String>,
        currentMediaRepository: SimpleGalleryMediaRepository,
        onStarted: () -> Unit,
    ) {
        doOnDeletingConfirmed = {
            changeStatus(
                change = StatusChange.DELETED,
                mediaUids = mediaUids,
                currentMediaRepository = currentMediaRepository,
                onStarted = onStarted,
                action = {
                    deleteGalleryMediaUseCase(
                        mediaUids = mediaUids,
                        currentGalleryMediaRepository = currentMediaRepository,
                    )
                },
            )
        }

        galleryMediaRemoteActionsEvents.onNext(
            GalleryMediaRemoteActionsViewModel.Event.OpenDeletingConfirmationDialog(
                count = mediaUids.size,
            )
        )
    }

    override fun archiveGalleryMedia(
        mediaUids: Collection<String>,
        currentMediaRepository: SimpleGalleryMediaRepository,
        onStarted: () -> Unit,
    ) = changeStatus(
        change = StatusChange.ARCHIVED,
        mediaUids = mediaUids,
        currentMediaRepository = currentMediaRepository,
        onStarted = onStarted,
        action = {
            archiveGalleryMediaUseCase(
                mediaUids = mediaUids,
                currentGalleryMediaRepository = currentMediaRepository,
            )
        },
        undoAction = {
            restoreGalleryMediaUseCase(
                mediaUids = mediaUids,
                currentGalleryMediaRepository = currentMediaRepository,
            )
        },
    )

    override fun restoreGalleryMedia(
        mediaUids: Collection<String>,
        currentMediaRepository: SimpleGalleryMediaRepository,
        onStarted: () -> Unit,
    ) {
        doOnRestoringConfirmed = {
            changeStatus(
                change = StatusChange.RESTORED,
                mediaUids = mediaUids,
                currentMediaRepository = currentMediaRepository,
                onStarted = onStarted,
                action = {
                    restoreGalleryMediaUseCase(
                        mediaUids = mediaUids,
                        currentGalleryMediaRepository = currentMediaRepository,
                    )
                },
                undoAction = {
                    archiveGalleryMediaUseCase(
                        mediaUids = mediaUids,
                        currentGalleryMediaRepository = currentMediaRepository,
                    )
                },
            )
        }

        galleryMediaRemoteActionsEvents.onNext(
            GalleryMediaRemoteActionsViewModel.Event.OpenRestoringConfirmationDialog(
                count = mediaUids.size,
            )
        )
    }

    override fun approveGalleryMedia(
        mediaUids: Collection<String>,
        currentMediaRepository: SimpleGalleryMediaRepository,
        onStarted: () -> Unit,
    ) = changeStatus(
        change = StatusChange.APPROVED,
        mediaUids = mediaUids,
        currentMediaRepository = currentMediaRepository,
        onStarted = onStarted,
        action = {
            approveGalleryMediaUseCase(
                mediaUids = mediaUids,
                currentGalleryMediaRepository = currentMediaRepository,
            )
        },
        // The server can't put photos back into review.
        undoAction = null,
    )

    /**
     * Changes the status of the items, reports how it went
     * and remembers how to undo it, if possible.
     *
     * @param undoAction the opposite action, after which the items
     * are put back to where they were in the list.
     */
    private fun changeStatus(
        change: StatusChange,
        mediaUids: Collection<String>,
        currentMediaRepository: SimpleGalleryMediaRepository,
        onStarted: () -> Unit,
        action: () -> Completable,
        undoAction: (() -> Completable)? = null,
    ) {
        val uidSet = mediaUids.toSet()
        // Remember where the items are, as they may leave the list.
        val itemsByIndex: Map<Int, GalleryMedia> =
            currentMediaRepository.itemsList
                .withIndex()
                .filter { it.value.uid in uidSet }
                .associate { it.index to it.value }

        action()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe {
                log.debug {
                    "changeStatus(): start_changing:" +
                            "\nchange=$change," +
                            "\nitems=${mediaUids.size}"
                }

                onStarted()
            }
            .subscribeBy(
                onError = { error ->
                    log.error(error) {
                        "changeStatus(): failed_changing:" +
                                "\nchange=$change"
                    }

                    galleryMediaRemoteActionsEvents.onNext(
                        GalleryMediaRemoteActionsViewModel.Event.ShowFloatingFailedMessage(
                            change = change,
                        )
                    )
                },
                onComplete = {
                    log.debug {
                        "changeStatus(): successfully_changed:" +
                                "\nchange=$change"
                    }

                    onStatusChanged(change, currentMediaRepository)

                    doOnUndo = undoAction?.let {
                        {
                            undoStatusChange(
                                change = change,
                                currentMediaRepository = currentMediaRepository,
                                itemsByIndex = itemsByIndex,
                                undoAction = undoAction,
                            )
                        }
                    }

                    galleryMediaRemoteActionsEvents.onNext(
                        GalleryMediaRemoteActionsViewModel.Event.ShowFloatingDoneMessage(
                            change = change,
                            count = mediaUids.size,
                            canUndo = undoAction != null,
                        )
                    )
                }
            )
            .autoDispose(this)
    }

    private fun undoStatusChange(
        change: StatusChange,
        currentMediaRepository: SimpleGalleryMediaRepository,
        itemsByIndex: Map<Int, GalleryMedia>,
        undoAction: () -> Completable,
    ) {
        undoAction()
            .doOnComplete {
                currentMediaRepository.putBackLocally(itemsByIndex)
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onError = { error ->
                    log.error(error) {
                        "undoStatusChange(): failed_undoing:" +
                                "\nchange=$change"
                    }

                    galleryMediaRemoteActionsEvents.onNext(
                        GalleryMediaRemoteActionsViewModel.Event.ShowFloatingFailedMessage(
                            change = when (change) {
                                StatusChange.ARCHIVED -> StatusChange.RESTORED
                                StatusChange.RESTORED -> StatusChange.ARCHIVED
                                else -> change
                            },
                        )
                    )
                },
                onComplete = {
                    log.debug {
                        "undoStatusChange(): successfully_undone:" +
                                "\nchange=$change"
                    }

                    onStatusChanged(change, currentMediaRepository)
                }
            )
            .autoDispose(this)
    }

    private fun onStatusChanged(
        change: StatusChange,
        currentMediaRepository: SimpleGalleryMediaRepository,
    ) {
        // Other lists may now miss or have extra items,
        // like the library after restoring from the archive.
        if (change != StatusChange.APPROVED) {
            galleryMediaRepositoryFactory.invalidateAllCachedExcept(currentMediaRepository)
        }
        // Refresh the review and archive counts.
        sessionCapabilitiesRepository?.update()
    }

    override fun addGalleryMediaToAlbum(
        mediaUids: Collection<String>,
        onStarted: () -> Unit,
    ) {
        fun doAddToAlbum(album: DestinationAlbum) {
            addGalleryMediaToAlbumUseCase
                .invoke(
                    mediaUids = mediaUids,
                    destinationAlbum = album,
                )
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe {
                    log.debug {
                        "addGalleryMediaToAlbum::doAddToAlbum(): start_adding:" +
                                "\nitems=${mediaUids.size}," +
                                "\nalbum=$album"
                    }

                    onStarted()
                }
                .subscribeBy(
                    onError = { error ->
                        log.error(error) {
                            "addGalleryMediaToAlbum::doAddToAlbum(): failed_adding"
                        }
                    },
                    onComplete = {
                        log.debug {
                            "addGalleryMediaToAlbum::doAddToAlbum(): successfully_added"
                        }

                        galleryMediaRemoteActionsEvents.onNext(
                            GalleryMediaRemoteActionsViewModel.Event.ShowFloatingAddedToAlbumMessage(
                                albumTitle = album.title,
                            )
                        )
                    }
                )
                .autoDispose(this)
        }

        doOnAlbumForAddingSelected = ::doAddToAlbum

        galleryMediaRemoteActionsEvents.onNext(
            GalleryMediaRemoteActionsViewModel.Event.OpenAlbumForAddingSelection
        )
    }

    override fun removeGalleryMediaFromAlbum(
        mediaUids: Collection<String>,
        albumUid: String,
        onStarted: () -> Unit,
    ) {
        removeGalleryMediaFromAlbumUseCase
            .invoke(
                mediaUids = mediaUids,
                albumUid = albumUid,
            )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe {
                log.debug {
                    "removeGalleryMediaFromAlbum(): start_removing:" +
                            "\nitems=${mediaUids.size}," +
                            "\nalbumUid=$albumUid"
                }

                onStarted()
            }
            .subscribeBy(
                onError = { error ->
                    log.error(error) {
                        "removeGalleryMediaFromAlbum(): failed_removing"
                    }
                },
                onComplete = {
                    log.debug {
                        "removeGalleryMediaFromAlbum(): physically_removed"
                    }
                }
            )
            .autoDispose(this)
    }

    override fun updateGalleryMediaAttributes(
        mediaUids: Collection<String>,
        currentMediaRepository: SimpleGalleryMediaRepository,
        isFavorite: Boolean?,
        isPrivate: Boolean?,
        onStarted: () -> Unit,
        onUpdated: () -> Unit,
    ) {
        updateGalleryMediaAttributesUseCase
            .invoke(
                mediaUids = mediaUids,
                isFavorite = isFavorite,
                isPrivate = isPrivate,
                currentGalleryMediaRepository = currentMediaRepository,
            )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe {
                log.debug {
                    "updateGalleryMediaAttributes(): start_updating:" +
                            "\nitems=${mediaUids.size}," +
                            "\nisFavorite=$isFavorite," +
                            "\nisPrivate=$isPrivate"
                }

                onStarted()
            }
            .subscribeBy(
                onError = { error ->
                    log.error(error) {
                        "updateGalleryMediaAttributes(): failed_updating"
                    }
                },
                onComplete = {
                    log.debug {
                        "updateGalleryMediaAttributes(): successfully_updated"
                    }

                    onUpdated()
                }
            )
            .autoDispose(this)
    }

    override fun onDeletingGalleryMediaConfirmed() =
        doOnDeletingConfirmed()

    override fun onRestoringGalleryMediaConfirmed() =
        doOnRestoringConfirmed()

    override fun onFloatingMessageUndoClicked() {
        val undo = doOnUndo
            ?: return
        doOnUndo = null
        undo()
    }

    override fun onAlbumForAddingGalleryMediaSelected(selectedAlbum: DestinationAlbum) =
        doOnAlbumForAddingSelected(selectedAlbum)
}
