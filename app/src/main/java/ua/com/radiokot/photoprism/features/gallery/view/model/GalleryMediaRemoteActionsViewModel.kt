package ua.com.radiokot.photoprism.features.gallery.view.model

import io.reactivex.rxjava3.subjects.PublishSubject
import ua.com.radiokot.photoprism.features.albums.data.model.DestinationAlbum

interface GalleryMediaRemoteActionsViewModel {

    val galleryMediaRemoteActionsEvents: PublishSubject<Event>

    fun onDeletingGalleryMediaConfirmed()
    fun onRestoringGalleryMediaConfirmed()
    fun onAlbumForAddingGalleryMediaSelected(selectedAlbum: DestinationAlbum)

    /**
     * Undoes the action reported by the last [Event.ShowFloatingDoneMessage]
     * which has [Event.ShowFloatingDoneMessage.canUndo].
     */
    fun onFloatingMessageUndoClicked()

    /**
     * What happened to the items in the library.
     */
    enum class StatusChange {
        ARCHIVED,
        RESTORED,
        APPROVED,
        DELETED,
        ;
    }

    sealed interface Event {
        /**
         * Show items deletion confirmation, reporting the choice
         * to the [onDeletingGalleryMediaConfirmed] method.
         */
        class OpenDeletingConfirmationDialog(
            val count: Int,
        ) : Event

        /**
         * Show confirmation of bringing items back from the archive,
         * reporting the choice to the [onRestoringGalleryMediaConfirmed] method.
         */
        class OpenRestoringConfirmationDialog(
            val count: Int,
        ) : Event

        /**
         * Open destination album selection screen, reporting the choice
         * to the [onAlbumForAddingGalleryMediaSelected] method.
         */
        object OpenAlbumForAddingSelection : Event

        class ShowFloatingAddedToAlbumMessage(
            val albumTitle: String,
        ) : Event

        /**
         * Tell that the items changed, offering to undo it if [canUndo],
         * which is reported to the [onFloatingMessageUndoClicked] method.
         */
        class ShowFloatingDoneMessage(
            val change: StatusChange,
            val count: Int,
            val canUndo: Boolean,
        ) : Event

        /**
         * Tell that the items couldn't be changed.
         */
        class ShowFloatingFailedMessage(
            val change: StatusChange,
        ) : Event
    }
}
