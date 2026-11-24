package ua.com.radiokot.photoprism.features.gallery.view

import android.content.DialogInterface
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaRemoteActionsViewModel
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaRemoteActionsViewModel.Event
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryMediaRemoteActionsViewModel.StatusChange
import com.google.android.material.R as MaterialR

/**
 * Asks to confirm archive and review actions and tells how they went,
 * the same way on every screen.
 *
 * @param snackbarParent where to show the floating messages
 * @param snackbarAnchor what the floating messages must stay above, if anything
 */
class GalleryMediaRemoteActionsFeedbackView(
    private val viewModel: GalleryMediaRemoteActionsViewModel,
    private val snackbarParent: () -> View,
    private val snackbarAnchor: () -> View? = { null },
) {
    private val context
        get() = snackbarParent().context

    /**
     * Shows what the [event] asks for, if it is about confirming or reporting a status change.
     *
     * @return true if the event was handled
     */
    fun show(event: Event): Boolean {
        when (event) {
            is Event.OpenDeletingConfirmationDialog ->
                openDeletingConfirmation(event.count)

            is Event.OpenRestoringConfirmationDialog ->
                openRestoringConfirmation(event.count)

            is Event.ShowFloatingDoneMessage ->
                showDone(event)

            is Event.ShowFloatingFailedMessage ->
                showFailed(event.change)

            else ->
                return false
        }
        return true
    }

    private fun openDeletingConfirmation(count: Int) {
        val resources = context.resources
        MaterialAlertDialogBuilder(context)
            .setIcon(tintedIcon(R.drawable.ic_delete_forever, MaterialR.attr.colorError))
            .setTitle(resources.getQuantityString(R.plurals.archive_delete_confirmation_title, count, count))
            .setMessage(resources.getQuantityString(R.plurals.archive_delete_confirmation_message, count))
            .setPositiveButton(R.string.archive_delete_forever) { _, _ ->
                viewModel.onDeletingGalleryMediaConfirmed()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .apply {
                // Deleting forever can't be undone, which the color must warn about.
                getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(
                    MaterialColors.getColor(
                        snackbarParent(),
                        MaterialR.attr.colorError,
                    )
                )
            }
    }

    private fun openRestoringConfirmation(count: Int) {
        val resources = context.resources
        MaterialAlertDialogBuilder(context)
            .setIcon(tintedIcon(R.drawable.ic_unarchive, MaterialR.attr.colorOnSurfaceVariant))
            .setTitle(resources.getQuantityString(R.plurals.archive_restore_confirmation_title, count, count))
            .setMessage(resources.getQuantityString(R.plurals.archive_restore_confirmation_message, count))
            .setPositiveButton(R.string.restore_action) { _, _ ->
                viewModel.onRestoringGalleryMediaConfirmed()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDone(event: Event.ShowFloatingDoneMessage) {
        val messageRes = when (event.change) {
            StatusChange.ARCHIVED -> R.plurals.archive_done_archived
            StatusChange.RESTORED -> R.plurals.archive_done_restored
            StatusChange.APPROVED -> R.plurals.archive_done_approved
            StatusChange.DELETED -> R.plurals.archive_done_deleted
        }
        val message = context.resources.getQuantityString(messageRes, event.count, event.count)

        makeSnackbar(message, if (event.canUndo) UNDO_DURATION_MS else Snackbar.LENGTH_SHORT)
            .apply {
                if (event.canUndo) {
                    setAction(R.string.archive_undo) {
                        viewModel.onFloatingMessageUndoClicked()
                    }
                }
            }
            .show()
    }

    private fun showFailed(change: StatusChange) {
        val messageRes = when (change) {
            StatusChange.ARCHIVED -> R.string.archive_failed_archiving
            StatusChange.RESTORED -> R.string.archive_failed_restoring
            StatusChange.APPROVED -> R.string.archive_failed_approving
            StatusChange.DELETED -> R.string.archive_failed_deleting
        }
        makeSnackbar(context.getString(messageRes), Snackbar.LENGTH_LONG)
            .show()
    }

    private fun tintedIcon(@DrawableRes iconRes: Int, @AttrRes colorAttr: Int) =
        ContextCompat.getDrawable(context, iconRes)
            ?.mutate()
            ?.apply {
                setTint(MaterialColors.getColor(snackbarParent(), colorAttr))
            }

    private companion object {
        /**
         * Long enough to notice the message and reach the undo button.
         */
        const val UNDO_DURATION_MS = 6000
    }

    private fun makeSnackbar(message: String, duration: Int) =
        Snackbar.make(snackbarParent(), message, duration).apply {
            snackbarAnchor()?.takeIf { it.visibility == View.VISIBLE }?.also(::setAnchorView)
        }
}
