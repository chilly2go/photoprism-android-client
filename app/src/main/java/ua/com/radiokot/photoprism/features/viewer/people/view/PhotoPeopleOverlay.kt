package ua.com.radiokot.photoprism.features.viewer.people.view

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.MenuItemCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.squareup.picasso.Picasso
import org.koin.core.component.KoinComponent
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.databinding.ItemPhotoPersonChipBinding
import ua.com.radiokot.photoprism.databinding.SheetPhotoFaceActionsBinding
import ua.com.radiokot.photoprism.databinding.ViewPhotoPeopleStripBinding
import ua.com.radiokot.photoprism.di.DI_SCOPE_SESSION
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.recyclerView
import ua.com.radiokot.photoprism.extension.subscribe
import ua.com.radiokot.photoprism.features.gallery.view.GallerySingleRepositoryActivity
import ua.com.radiokot.photoprism.features.people.view.NameFaceDialogFragment
import ua.com.radiokot.photoprism.features.viewer.people.data.model.PhotoFace
import ua.com.radiokot.photoprism.features.viewer.people.view.model.PhotoPeopleViewModel
import ua.com.radiokot.photoprism.features.viewer.people.view.model.PhotoPeopleViewModel.Event
import ua.com.radiokot.photoprism.features.viewer.people.view.model.PhotoPeopleViewModel.FaceAction
import ua.com.radiokot.photoprism.features.viewer.people.view.model.PhotoPeopleViewModel.State
import ua.com.radiokot.photoprism.features.viewer.view.model.FadeEndLivePhotoViewerPage
import ua.com.radiokot.photoprism.features.viewer.view.model.ImageViewerPage
import ua.com.radiokot.photoprism.features.viewer.view.model.MediaViewerPage
import ua.com.radiokot.photoprism.features.viewer.view.model.VideoViewerPage
import ua.com.radiokot.photoprism.util.images.ImageTransformations

/**
 * "People in the photo" for the media viewer: frames over the faces of the current photo,
 * a strip of the people at the bottom, and what can be done with a face.
 *
 * Kept out of the viewer so it only needs to create this,
 * call [init], [bindMenuItem] and [toggle], and not let the swipe-to-dismiss
 * take touches while [isTouchLocked].
 */
class PhotoPeopleOverlay(
    private val activity: AppCompatActivity,
    private val viewModel: PhotoPeopleViewModel,
    private val viewPager: ViewPager2,
    private val pageAt: (position: Int) -> MediaViewerPage?,
    private val rootLayout: ConstraintLayout,
    /**
     * The view the strip goes above.
     */
    private val bottomAnchorView: View,
    private val snackbarView: View,
    private val areControlsVisible: LiveData<Boolean>,
) : KoinComponent {
    private val log = kLogger("PhotoPeopleOverlay")

    private val picasso: Picasso? by lazy {
        getKoin().getScopeOrNull(DI_SCOPE_SESSION)?.getOrNull()
    }
    private lateinit var strip: ViewPhotoPeopleStripBinding
    private var currentFramesLayout: FaceFramesLayout? = null
    private var menuItem: MenuItem? = null
    private var faceActionsDialog: BottomSheetDialog? = null

    /**
     * Whether a touch must reach the photo untouched, like when placing a frame.
     */
    val isTouchLocked: Boolean
        get() = viewModel.isAddingFace.value == true

    private val addingBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() =
            viewModel.onAddFaceCancelled()
    }

    fun init() {
        initStrip()
        subscribeToPager()
        subscribeToViewModel()
        subscribeToEvents()

        activity.onBackPressedDispatcher.addCallback(activity, addingBackPressedCallback)

        activity.supportFragmentManager.setFragmentResultListener(
            NAME_REQUEST_KEY,
            activity,
            ::onNameResult,
        )
        activity.supportFragmentManager.setFragmentResultListener(
            NEW_FACE_NAME_REQUEST_KEY,
            activity,
            ::onNewFaceNameResult,
        )
    }

    fun bindMenuItem(item: MenuItem) {
        menuItem = item
        item.isVisible = viewModel.canSeePeople
        updateMenuItem()
    }

    fun toggle() =
        viewModel.onToggleClicked()

    private fun updateMenuItem() {
        val item = menuItem ?: return
        val isEnabled = viewModel.isEnabled.value == true
        item.isChecked = isEnabled
        item.setTitle(if (isEnabled) R.string.photo_people_hide else R.string.photo_people_show)
        MenuItemCompat.setIconTintList(
            item,
            ColorStateList.valueOf(
                MaterialColors.getColor(
                    rootLayout,
                    if (isEnabled)
                        MaterialR.attr.colorPrimary
                    else
                        MaterialR.attr.colorOnSurfaceVariant
                )
            )
        )
    }

    // region Strip
    private fun initStrip() {
        strip = ViewPhotoPeopleStripBinding.inflate(LayoutInflater.from(activity))
        strip.root.id = View.generateViewId()
        strip.root.isVisible = false
        rootLayout.addView(
            strip.root,
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomToTop = bottomAnchorView.id
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
        )

        strip.addingCancelButton.setOnClickListener {
            viewModel.onAddFaceCancelled()
        }
        strip.addingDoneButton.setOnClickListener {
            val area = currentFramesLayout?.draftArea
            if (area != null) {
                viewModel.onNewFaceAreaPlaced(area)
            }
        }
    }

    private fun updateStripVisibility() {
        strip.root.isVisible = viewModel.isEnabled.value == true
                && (areControlsVisible.value == true || viewModel.isAddingFace.value == true)
    }

    private fun renderStrip() {
        val chips = strip.chipsLayout
        chips.removeAllViews()

        val isAdding = viewModel.isAddingFace.value == true
        strip.chipsScrollView.isVisible = !isAdding
        strip.addingLayout.isVisible = isAdding
        if (isAdding) {
            return
        }

        when (val state = viewModel.state.value) {
            State.Loading ->
                addStatusChip(R.string.photo_people_loading, isLoading = true)

            State.Failed ->
                addStatusChip(R.string.photo_people_failed, iconRes = R.drawable.ic_close)
                    .setOnClickListener { viewModel.onRetryClicked() }

            State.NotAvailable ->
                addStatusChip(R.string.photo_people_not_available)

            is State.Loaded -> {
                val faces = state.photoFaces.faces
                if (faces.isEmpty()) {
                    addStatusChip(R.string.photo_people_no_faces, iconRes = R.drawable.ic_face)
                }
                faces.forEach(::addFaceChip)
                if (viewModel.canEdit && state.photoFaces.fileUid != null && hasFrames()) {
                    addStatusChip(R.string.photo_people_add_face, iconRes = R.drawable.ic_plus_circle)
                        .setOnClickListener { viewModel.onAddFaceClicked() }
                }
            }

            State.Off,
            null -> {
            }
        }
    }

    private fun addFaceChip(face: PhotoFace) {
        val chip = ItemPhotoPersonChipBinding.inflate(
            LayoutInflater.from(activity),
            strip.chipsLayout,
            true
        )
        chip.nameTextView.text = getLabel(face)
        if (!face.hasName || face.isToReview) {
            chip.root.setBackgroundResource(R.drawable.photo_people_chip_unnamed_background)
        }
        loadFaceThumbnail(face, chip.imageView)
        chip.root.setOnClickListener {
            viewModel.onFaceClicked(face)
        }
    }

    private fun addStatusChip(
        textRes: Int,
        iconRes: Int? = null,
        isLoading: Boolean = false,
    ): View {
        val chip = ItemPhotoPersonChipBinding.inflate(
            LayoutInflater.from(activity),
            strip.chipsLayout,
            true
        )
        chip.nameTextView.setText(textRes)
        chip.imageView.isVisible = false
        chip.progressIndicator.isVisible = isLoading
        if (iconRes != null) {
            chip.iconView.isVisible = true
            chip.iconView.setImageResource(iconRes)
        }
        if (iconRes == null && !isLoading) {
            chip.root.setPaddingRelative(
                chip.root.paddingEnd, 0, chip.root.paddingEnd, 0
            )
            (chip.iconView.parent as View).isVisible = false
        }
        return chip.root
    }

    private fun loadFaceThumbnail(face: PhotoFace, imageView: android.widget.ImageView) {
        val url = viewModel.getChipThumbnailUrl(face)
        val picasso = picasso
        if (url == null || picasso == null) {
            imageView.setImageResource(R.drawable.image_placeholder_circle)
            return
        }
        picasso
            .load(url)
            .placeholder(R.drawable.image_placeholder_circle)
            .fit()
            .centerCrop()
            .transform(ImageTransformations.circle)
            .into(imageView)
    }

    private fun getLabel(face: PhotoFace): String = when {
        face.name != null && face.isToReview -> "${face.name}?"
        face.name != null -> face.name
        viewModel.canEdit -> activity.getString(R.string.photo_people_who_is_this)
        else -> activity.getString(R.string.photo_people_unknown)
    }
    // endregion

    // region Frames
    private fun subscribeToPager() {
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) =
                onCurrentPageMaybeChanged()
        })
        viewPager.adapter?.registerAdapterDataObserver(object :
            RecyclerView.AdapterDataObserver() {
            override fun onChanged() =
                onCurrentPageMaybeChanged()

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) =
                onCurrentPageMaybeChanged()

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
                onCurrentPageMaybeChanged()

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) =
                onCurrentPageMaybeChanged()
        })
    }

    private fun onCurrentPageMaybeChanged() {
        val page = pageAt(viewPager.currentItem)
        viewModel.onMediaShown(page?.source)
        // The view holder may not be there yet.
        viewPager.post(::attachToCurrentPage)
    }

    /**
     * Puts the frames over the photo of the current page,
     * taking them off the previous one.
     */
    private fun attachToCurrentPage() {
        val isEnabled = viewModel.isEnabled.value == true

        // Keep the strip off the video player controls.
        strip.root.translationY =
            if (pageAt(viewPager.currentItem) is VideoViewerPage)
                -VIDEO_CONTROLS_HEIGHT_DP * activity.resources.displayMetrics.density
            else
                0f
        val photoView = when (
            val viewHolder = viewPager.recyclerView
                .findViewHolderForAdapterPosition(viewPager.currentItem)
        ) {
            is ImageViewerPage.ViewHolder -> viewHolder.view.photoView
            is FadeEndLivePhotoViewerPage.ViewHolder -> viewHolder.view.photoView
            else -> null
        }

        val framesLayout =
            if (isEnabled && photoView != null)
                FaceFramesLayout.wrap(photoView)
            else
                null

        if (framesLayout !== currentFramesLayout) {
            currentFramesLayout?.apply {
                faces = emptyList()
                onFaceClicked = null
                stopAdding()
            }
            currentFramesLayout = framesLayout
        }

        framesLayout?.apply {
            onFaceClicked = viewModel::onFaceClicked
            isEditable = viewModel.canEdit
            faces = (viewModel.state.value as? State.Loaded)?.photoFaces?.faces ?: emptyList()
            highlightedFaceUid = viewModel.highlightedFaceUid.value
            if (viewModel.isAddingFace.value == true) {
                if (draftArea == null) startAdding()
            } else {
                stopAdding()
            }
        }
    }

    private fun hasFrames(): Boolean =
        currentFramesLayout != null
                || pageAt(viewPager.currentItem).let {
            it is ImageViewerPage || it is FadeEndLivePhotoViewerPage
        }
    // endregion

    private fun subscribeToViewModel() {
        viewModel.isEnabled.observe(activity) {
            updateMenuItem()
            updateStripVisibility()
            if (it) {
                onCurrentPageMaybeChanged()
            } else {
                attachToCurrentPage()
            }
        }
        areControlsVisible.observe(activity) {
            updateStripVisibility()
        }
        viewModel.state.observe(activity) {
            renderStrip()
            attachToCurrentPage()
        }
        viewModel.highlightedFaceUid.observe(activity) { uid ->
            currentFramesLayout?.highlightedFaceUid = uid
        }
        viewModel.isAddingFace.observe(activity) { isAdding ->
            addingBackPressedCallback.isEnabled = isAdding
            updateStripVisibility()
            renderStrip()
            attachToCurrentPage()
        }
        viewModel.isBusy.observe(activity) { isBusy ->
            strip.root.alpha = if (isBusy) 0.6f else 1f
            strip.addingDoneButton.isEnabled = !isBusy
        }
    }

    private fun subscribeToEvents() = viewModel.events.subscribe(activity) { event ->
        log.debug {
            "subscribeToEvents(): received_new_event:" +
                    "\nevent=$event"
        }

        when (event) {
            is Event.OpenFaceActions ->
                openFaceActions(event)

            is Event.OpenNameDialog ->
                NameFaceDialogFragment.newInstance(
                    faceId = "",
                    markerUid = event.markerUid,
                    thumbnailUrl = event.thumbnailUrl,
                    nameSuggestions = event.nameSuggestions,
                    initialName = event.currentName,
                    isForCluster = false,
                    requestKey = NAME_REQUEST_KEY,
                ).show(activity.supportFragmentManager, NameFaceDialogFragment.TAG)

            is Event.OpenNewFaceNameDialog ->
                NameFaceDialogFragment.newInstance(
                    faceId = "",
                    markerUid = "",
                    thumbnailUrl = null,
                    nameSuggestions = event.nameSuggestions,
                    isForCluster = false,
                    requestKey = NEW_FACE_NAME_REQUEST_KEY,
                ).show(activity.supportFragmentManager, NameFaceDialogFragment.TAG)

            is Event.OpenPersonPhotos ->
                activity.startActivity(
                    Intent(activity, GallerySingleRepositoryActivity::class.java)
                        .putExtras(
                            GallerySingleRepositoryActivity.getBundle(
                                title = event.title ?: activity.getString(R.string.people),
                                repositoryParams = event.repositoryParams,
                            )
                        )
                )

            is Event.ShowChanged ->
                showChanged(event)

            is Event.ShowFailed ->
                Snackbar.make(
                    snackbarView,
                    if (event.isNoPermission)
                        R.string.people_overview_no_permission
                    else
                        R.string.people_overview_failed_to_save,
                    Snackbar.LENGTH_LONG,
                ).show()
        }
    }

    private fun openFaceActions(event: Event.OpenFaceActions) {
        faceActionsDialog?.dismiss()

        val face = event.face
        val sheet = SheetPhotoFaceActionsBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity)
        var isActionChosen = false

        sheet.titleTextView.text = getLabel(face)
        sheet.subtitleTextView.setText(
            when {
                event.isReadOnly -> R.string.people_overview_read_only
                face.isToReview -> R.string.photo_people_suggestion
                !face.hasName -> R.string.photo_people_not_named_yet
                else -> R.string.people_overview_library_wide_hint
            }
        )
        val thumbnailUrl = event.thumbnailUrl
        val picasso = picasso
        if (thumbnailUrl != null && picasso != null) {
            picasso
                .load(thumbnailUrl)
                .placeholder(R.drawable.image_placeholder_circle)
                .fit()
                .centerCrop()
                .transform(ImageTransformations.circle)
                .into(sheet.imageView)
        } else {
            sheet.imageView.setImageResource(R.drawable.image_placeholder_circle)
        }

        event.actions.forEach { action ->
            val item = LayoutInflater.from(activity)
                .inflate(R.layout.item_photo_face_action, sheet.actionsLayout, false)
                    as TextView
            val (textRes, iconRes) = when (action) {
                FaceAction.CONFIRM -> R.string.photo_people_action_confirm to R.drawable.ic_done
                FaceAction.NAME -> R.string.photo_people_action_name to R.drawable.ic_pencil
                FaceAction.CHANGE_NAME -> R.string.photo_people_action_change_name to R.drawable.ic_pencil
                FaceAction.CLEAR_NAME -> R.string.photo_people_action_clear_name to R.drawable.ic_account_remove
                FaceAction.NOT_A_FACE -> R.string.photo_people_action_not_a_face to R.drawable.ic_close
                FaceAction.SHOW_PHOTOS -> R.string.photo_people_action_show_photos to R.drawable.ic_photo_library
            }
            item.text = activity.getString(textRes, face.name ?: "")
            item.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
            item.compoundDrawableTintList = ColorStateList.valueOf(
                MaterialColors.getColor(item, MaterialR.attr.colorOnSurfaceVariant)
            )
            item.setOnClickListener {
                isActionChosen = true
                dialog.dismiss()
                viewModel.onFaceActionClicked(face, action)
            }
            sheet.actionsLayout.addView(item)
        }

        dialog.setContentView(sheet.root)
        dialog.setOnDismissListener {
            if (!isActionChosen) {
                viewModel.onFaceActionsDismissed()
            }
            if (faceActionsDialog === dialog) {
                faceActionsDialog = null
            }
        }
        faceActionsDialog = dialog
        dialog.show()
    }

    private fun showChanged(event: Event.ShowChanged) {
        val name = event.name ?: ""
        val text = when (event.message) {
            PhotoPeopleViewModel.Message.NAMED ->
                activity.getString(R.string.people_overview_named_as, name)

            PhotoPeopleViewModel.Message.CONFIRMED ->
                activity.getString(R.string.photo_people_confirmed, name)

            PhotoPeopleViewModel.Message.NAME_CLEARED ->
                activity.getString(R.string.photo_people_name_cleared)

            PhotoPeopleViewModel.Message.NOT_A_FACE ->
                activity.getString(R.string.photo_people_marked_not_a_face)

            PhotoPeopleViewModel.Message.FACE_RESTORED ->
                activity.getString(R.string.photo_people_face_restored)

            PhotoPeopleViewModel.Message.ADDED ->
                activity.getString(R.string.photo_people_face_added, name)
        }

        val undo = event.undo
        Snackbar.make(snackbarView, text, Snackbar.LENGTH_LONG)
            .apply {
                if (undo != null) {
                    duration = UNDO_SNACKBAR_DURATION_MS
                    setAction(R.string.people_overview_undo) { undo() }
                }
            }
            .show()
    }

    private fun onNameResult(requestKey: String, result: Bundle) {
        val markerUid = result.getString(NameFaceDialogFragment.RESULT_MARKER_UID)
        when (result.getString(NameFaceDialogFragment.RESULT_ACTION)) {
            NameFaceDialogFragment.ACTION_NAME ->
                if (markerUid != null) {
                    viewModel.onNameSubmitted(
                        markerUid = markerUid,
                        name = result.getString(NameFaceDialogFragment.RESULT_NAME) ?: "",
                    )
                }

            else ->
                viewModel.onFaceActionsDismissed()
        }
    }

    private fun onNewFaceNameResult(requestKey: String, result: Bundle) {
        if (result.getString(NameFaceDialogFragment.RESULT_ACTION) == NameFaceDialogFragment.ACTION_NAME) {
            viewModel.onNewFaceNameSubmitted(
                name = result.getString(NameFaceDialogFragment.RESULT_NAME) ?: "",
            )
        }
    }

    private companion object {
        private const val NAME_REQUEST_KEY = "photo_people_name"
        private const val NEW_FACE_NAME_REQUEST_KEY = "photo_people_new_face_name"
        private const val UNDO_SNACKBAR_DURATION_MS = 8000
        private const val VIDEO_CONTROLS_HEIGHT_DP = 56f
    }
}
