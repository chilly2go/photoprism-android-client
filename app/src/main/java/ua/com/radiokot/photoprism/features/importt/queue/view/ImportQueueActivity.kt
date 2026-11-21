package ua.com.radiokot.photoprism.features.importt.queue.view

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.squareup.picasso.Picasso
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.base.view.BaseActivity
import ua.com.radiokot.photoprism.databinding.ActivityImportQueueBinding
import ua.com.radiokot.photoprism.databinding.DialogImportQueueItemBinding
import ua.com.radiokot.photoprism.databinding.ListItemImportQueueMediaBinding
import ua.com.radiokot.photoprism.databinding.ListItemImportQueueMessageBinding
import ua.com.radiokot.photoprism.databinding.ListItemImportQueueProblemBinding
import ua.com.radiokot.photoprism.databinding.ListItemImportQueueSectionBinding
import ua.com.radiokot.photoprism.databinding.ListItemImportQueueSummaryBinding
import ua.com.radiokot.photoprism.extension.barsAndCutout
import ua.com.radiokot.photoprism.extension.barsAndCutoutPadding
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueFailureReason
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemState
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueOverview
import ua.com.radiokot.photoprism.features.importt.queue.view.model.ImportQueueViewModel
import ua.com.radiokot.photoprism.features.importt.queue.view.model.ImportQueueViewModel.Filter
import ua.com.radiokot.photoprism.features.importt.queue.view.model.ImportQueueViewModel.ListItem
import ua.com.radiokot.photoprism.features.importt.queue.view.model.ImportQueueViewModel.Status
import java.text.NumberFormat
import java.util.Date

class ImportQueueActivity : BaseActivity() {

    private lateinit var view: ActivityImportQueueBinding
    private val viewModel: ImportQueueViewModel by viewModel()
    private val picasso: Picasso by inject()
    private val adapter = QueueAdapter()
    private val numberFormat = NumberFormat.getIntegerInstance()
    private var thumbnailSizePx = 0
    private var summary: ListItem.Summary? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (goToEnvConnectionIfNoSession()) {
            return
        }

        view = ActivityImportQueueBinding.inflate(layoutInflater)
        setContentView(view.root)

        setSupportActionBar(view.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        view.toolbar.barsAndCutoutPadding()

        initList()

        viewModel.listItems.observe(this) { items ->
            adapter.submitList(items)
            view.emptyLayout.isVisible = items.isEmpty()
            summary = items.firstOrNull() as? ListItem.Summary
            invalidateOptionsMenu()
        }
    }

    private fun initList() {
        val listWidthPx = resources.displayMetrics.widthPixels -
                2 * resources.getDimensionPixelSize(R.dimen.import_queue_list_padding)
        val spanCount = (listWidthPx /
                resources.getDimensionPixelSize(R.dimen.import_queue_min_thumbnail_size))
            .coerceAtLeast(3)
        thumbnailSizePx = listWidthPx / spanCount

        view.recyclerView.layoutManager = GridLayoutManager(this, spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.getItemViewType(position) == VIEW_TYPE_MEDIA)
                        1
                    else
                        spanCount
            }
        }
        view.recyclerView.adapter = adapter
        // Item changes are frequent while uploading,
        // the default change animation makes the grid blink.
        view.recyclerView.itemAnimator = null

        val listPadding = view.recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view.root) { _, insets ->
            view.recyclerView.updatePadding(bottom = listPadding + insets.barsAndCutout().bottom)
            insets
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_queue, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val counts = summary?.countsByFilter.orEmpty()
        menu.findItem(R.id.remove_done)?.isVisible =
            (counts[Filter.IMPORTED] ?: 0) + (counts[Filter.ALREADY_IN_LIBRARY] ?: 0) > 0
        menu.findItem(R.id.remove_failed)?.isVisible =
            (counts[Filter.FAILED] ?: 0) > 0
        menu.findItem(R.id.remove_pending)?.isVisible =
            summary?.hasRemovableWaiting == true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.remove_done ->
                viewModel.onRemoveDoneClicked()

            R.id.remove_failed ->
                viewModel.onRemoveFailedClicked()

            R.id.remove_pending ->
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.import_queue_remove_pending_confirmation)
                    .setPositiveButton(R.string.import_queue_remove_pending) { _, _ ->
                        viewModel.onRemovePendingConfirmed()
                    }
                    .setNegativeButton(R.string.import_queue_close, null)
                    .show()

            android.R.id.home ->
                finish()

            else ->
                return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showItemDialog(item: ImportQueueOverview.Item) {
        val dialogView = DialogImportQueueItemBinding.inflate(layoutInflater)

        with(dialogView) {
            picasso
                .load(item.contentUri)
                .resize(DIALOG_THUMBNAIL_SIZE_PX, DIALOG_THUMBNAIL_SIZE_PX)
                .centerCrop()
                .onlyScaleDown()
                .placeholder(R.drawable.image_placeholder)
                .error(R.drawable.image_error)
                .into(imageView)

            nameTextView.text = item.displayName
            stateTextView.setText(getStateText(item.state))

            val isFailed = item.state == ImportQueueItemState.FAILED
            reasonTextView.isVisible = isFailed
            if (isFailed) {
                reasonTextView.setText(getFailureDetailsText(item.failureReason))
            }

            detailsToggleTextView.isVisible = isFailed && item.error != null
            detailsTextView.text = item.error
            detailsToggleTextView.setOnClickListener {
                detailsTextView.isVisible = !detailsTextView.isVisible
            }
        }

        // Back and tapping outside close the dialog,
        // so a close button is only added when there is nothing else to do.
        MaterialAlertDialogBuilder(this)
            .setView(dialogView.root)
            .apply {
                if (item.state == ImportQueueItemState.FAILED) {
                    setPositiveButton(R.string.import_queue_retry) { _, _ ->
                        viewModel.onRetryItemClicked(item.id)
                    }
                } else {
                    setPositiveButton(R.string.import_queue_close, null)
                }
                if (!item.isInProgress) {
                    setNegativeButton(R.string.import_queue_remove) { _, _ ->
                        viewModel.onRemoveItemClicked(item.id)
                    }
                }
            }
            .show()
    }

    private fun loadThumbnail(item: ImportQueueOverview.Item, imageView: ImageView) {
        picasso
            .load(item.contentUri)
            .resize(thumbnailSizePx, thumbnailSizePx)
            .centerCrop()
            .placeholder(R.drawable.image_placeholder)
            .error(R.drawable.image_error)
            .into(imageView)
    }

    private fun formatAddedAt(date: Date): String {
        val day = when {
            DateUtils.isToday(date.time) ->
                getString(R.string.import_queue_today)

            DateUtils.isToday(date.time + DateUtils.DAY_IN_MILLIS) ->
                getString(R.string.import_queue_yesterday)

            else ->
                DateFormat.getMediumDateFormat(this).format(date)
        }
        return "$day, ${DateFormat.getTimeFormat(this).format(date)}"
    }

    private fun getStateText(state: ImportQueueItemState): Int = when (state) {
        ImportQueueItemState.PENDING ->
            R.string.import_queue_item_pending

        ImportQueueItemState.UPLOADING ->
            R.string.import_queue_item_uploading

        ImportQueueItemState.UPLOADED ->
            R.string.import_queue_item_uploaded

        ImportQueueItemState.IMPORTED ->
            R.string.import_queue_item_imported

        ImportQueueItemState.ALREADY_IN_LIBRARY ->
            R.string.import_queue_item_already_in_library

        ImportQueueItemState.FAILED ->
            R.string.import_queue_item_failed

        ImportQueueItemState.CANCELLED ->
            R.string.import_queue_item_cancelled
    }

    private fun getFailureText(reason: ImportQueueFailureReason?): Int = when (reason) {
        ImportQueueFailureReason.CANT_READ ->
            R.string.import_queue_reason_cant_read

        ImportQueueFailureReason.TOO_LARGE ->
            R.string.import_queue_reason_too_large

        ImportQueueFailureReason.UPLOAD_FAILED ->
            R.string.import_queue_reason_upload_failed

        ImportQueueFailureReason.IMPORT_REJECTED ->
            R.string.import_queue_reason_import_rejected

        ImportQueueFailureReason.NOT_FOUND_AFTER_IMPORT ->
            R.string.import_queue_reason_not_found_after_import

        null ->
            R.string.import_queue_reason_unknown
    }

    private fun getFailureDetailsText(reason: ImportQueueFailureReason?): Int = when (reason) {
        ImportQueueFailureReason.CANT_READ ->
            R.string.import_queue_reason_cant_read_details

        ImportQueueFailureReason.TOO_LARGE ->
            R.string.import_queue_reason_too_large_details

        ImportQueueFailureReason.UPLOAD_FAILED ->
            R.string.import_queue_reason_upload_failed_details

        ImportQueueFailureReason.IMPORT_REJECTED ->
            R.string.import_queue_reason_import_rejected_details

        ImportQueueFailureReason.NOT_FOUND_AFTER_IMPORT ->
            R.string.import_queue_reason_not_found_after_import_details

        null ->
            R.string.import_queue_reason_unknown
    }

    private inner class QueueAdapter : ListAdapter<ListItem, RecyclerView.ViewHolder>(ItemDiffCallback) {

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is ListItem.Summary -> VIEW_TYPE_SUMMARY
            is ListItem.Section -> VIEW_TYPE_SECTION
            is ListItem.Media -> VIEW_TYPE_MEDIA
            is ListItem.Problem -> VIEW_TYPE_PROBLEM
            ListItem.NothingInFilter -> VIEW_TYPE_MESSAGE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                VIEW_TYPE_SUMMARY ->
                    SummaryViewHolder(ListItemImportQueueSummaryBinding.inflate(inflater, parent, false))

                VIEW_TYPE_SECTION ->
                    SectionViewHolder(ListItemImportQueueSectionBinding.inflate(inflater, parent, false))

                VIEW_TYPE_MEDIA ->
                    MediaViewHolder(ListItemImportQueueMediaBinding.inflate(inflater, parent, false))

                VIEW_TYPE_PROBLEM ->
                    ProblemViewHolder(ListItemImportQueueProblemBinding.inflate(inflater, parent, false))

                else ->
                    object : RecyclerView.ViewHolder(
                        ListItemImportQueueMessageBinding.inflate(inflater, parent, false).root
                    ) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is ListItem.Summary -> (holder as SummaryViewHolder).bind(item)
                is ListItem.Section -> (holder as SectionViewHolder).bind(item)
                is ListItem.Media -> (holder as MediaViewHolder).bind(item)
                is ListItem.Problem -> (holder as ProblemViewHolder).bind(item.item)
                ListItem.NothingInFilter -> {}
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            when (holder) {
                is MediaViewHolder -> holder.unbind()
                is ProblemViewHolder -> holder.unbind()
            }
        }
    }

    private inner class SummaryViewHolder(
        private val view: ListItemImportQueueSummaryBinding,
    ) : RecyclerView.ViewHolder(view.root) {
        private val chipsByFilter = mapOf(
            Filter.ALL to (view.chipAll to R.string.import_queue_filter_all),
            Filter.PENDING to (view.chipPending to R.string.import_queue_filter_pending),
            Filter.IMPORTED to (view.chipImported to R.string.import_queue_filter_imported),
            Filter.ALREADY_IN_LIBRARY to
                    (view.chipAlreadyInLibrary to R.string.import_queue_filter_already_in_library),
            Filter.FAILED to (view.chipFailed to R.string.import_queue_filter_failed),
        )

        init {
            chipsByFilter.forEach { (filter, chipAndText) ->
                chipAndText.first.setOnClickListener {
                    viewModel.onFilterSelected(filter)
                }
            }
        }

        fun bind(summary: ListItem.Summary) = with(view) {
            val context = root.context

            val (statusIcon, statusText) = when (summary.status) {
                Status.UPLOADING ->
                    R.drawable.ic_upload to
                            context.getString(R.string.import_queue_status_uploading)

                Status.IMPORTING ->
                    R.drawable.ic_cloud_upload to
                            context.getString(R.string.import_queue_status_importing)

                Status.WAITING_FOR_CONNECTION ->
                    R.drawable.ic_schedule to
                            context.getString(R.string.import_queue_status_waiting_for_connection)

                Status.PAUSED ->
                    R.drawable.ic_pause to
                            context.getString(R.string.import_queue_status_paused)

                Status.DONE ->
                    R.drawable.ic_cloud_done to
                            context.getString(R.string.import_queue_status_done)

                Status.DONE_WITH_PROBLEMS ->
                    R.drawable.ic_priority_high to
                            context.resources.getQuantityString(
                                R.plurals.import_queue_status_done_with_problems,
                                summary.failedCount,
                                summary.failedCount,
                            )
            }
            statusImageView.setImageResource(statusIcon)
            statusImageView.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    statusImageView,
                    if (summary.status == Status.DONE_WITH_PROBLEMS)
                        com.google.android.material.R.attr.colorError
                    else
                        com.google.android.material.R.attr.colorPrimary
                )
            )
            statusTextView.text = statusText

            progressTextView.text = context.getString(
                R.string.template_import_queue_overall_progress,
                numberFormat.format(summary.finishedCount),
                numberFormat.format(summary.totalCount),
            )
            progressIndicator.max = summary.progressMax.coerceAtLeast(1)
            progressIndicator.setProgressCompat(summary.progress, true)

            pauseResumeButton.isVisible = summary.canPause || summary.canResume
            if (summary.canPause) {
                pauseResumeButton.setText(R.string.import_queue_pause)
                pauseResumeButton.setIconResource(R.drawable.ic_pause)
                pauseResumeButton.setOnClickListener { viewModel.onPauseClicked() }
            } else {
                pauseResumeButton.setText(R.string.import_queue_resume)
                pauseResumeButton.setIconResource(R.drawable.ic_play)
                pauseResumeButton.setOnClickListener { viewModel.onResumeClicked() }
            }

            retryFailedButton.isVisible = summary.failedCount > 0
            retryFailedButton.text = context.getString(
                R.string.template_import_queue_retry_failed,
                summary.failedCount,
            )
            retryFailedButton.setOnClickListener { viewModel.onRetryAllFailedClicked() }

            buttonsLayout.isVisible = pauseResumeButton.isVisible || retryFailedButton.isVisible

            chipsByFilter.forEach { (filter, chipAndText) ->
                val (chip: Chip, textRes) = chipAndText
                val count = summary.countsByFilter[filter] ?: 0
                val isSelected = filter == summary.filter
                chip.text = "${context.getString(textRes)}  ${numberFormat.format(count)}"
                chip.isVisible = count > 0 || isSelected || filter == Filter.ALL
                chip.isChecked = isSelected
            }

            // Keep the selected filter in sight, as the chips may not fit the screen.
            val selectedChip = chipsByFilter.getValue(summary.filter).first
            filterScrollView.post {
                val visibleLeft = filterScrollView.scrollX
                val visibleRight = visibleLeft + filterScrollView.width
                if (selectedChip.left < visibleLeft || selectedChip.right > visibleRight) {
                    filterScrollView.scrollTo(selectedChip.left, 0)
                }
            }
        }
    }

    private inner class SectionViewHolder(
        private val view: ListItemImportQueueSectionBinding,
    ) : RecyclerView.ViewHolder(view.root) {

        fun bind(section: ListItem.Section) = with(view) {
            val context = root.context
            titleTextView.text = buildString {
                append(
                    context.getString(
                        R.string.template_import_queue_batch_added,
                        formatAddedAt(section.batch.createdAt),
                    )
                )
                append(" · ")
                append(
                    context.resources.getQuantityString(
                        R.plurals.import_queue_batch_files,
                        section.fileCount,
                        section.fileCount,
                    )
                )
            }
            albumsTextView.isVisible = section.batch.albums.isNotEmpty()
            albumsTextView.text = context.getString(
                R.string.template_import_queue_batch_albums,
                section.batch.albums.joinToString { it.title },
            )
        }
    }

    private inner class MediaViewHolder(
        private val view: ListItemImportQueueMediaBinding,
    ) : RecyclerView.ViewHolder(view.root) {
        private var boundItemId: Long? = null

        fun bind(media: ListItem.Media) = with(view) {
            val item = media.item
            if (boundItemId != item.id) {
                boundItemId = item.id
                loadThumbnail(item, imageView)
            }

            bindUploadProgress(media.uploadPercent)

            videoImageView.isVisible = item.isVideo
            // Files which are not in the library yet are faded,
            // so the grid gets brighter as the upload progresses.
            imageView.alpha = when (item.state) {
                ImportQueueItemState.PENDING,
                ImportQueueItemState.UPLOADING,
                ImportQueueItemState.UPLOADED,
                ImportQueueItemState.CANCELLED ->
                    0.55f

                else ->
                    1f
            }

            bindBadge(item.state)

            root.contentDescription = "${item.displayName}, ${getString(getStateText(item.state))}"
            root.setOnClickListener {
                showItemDialog(item)
            }
        }

        private fun bindUploadProgress(percent: Int?) = with(view) {
            uploadProgressIndicator.isVisible = percent != null
            if (percent == null) {
                return@with
            }

            val isKnown = percent >= 0
            if (uploadProgressIndicator.isIndeterminate == isKnown) {
                // Changing the mode of a shown indicator is not allowed.
                uploadProgressIndicator.isVisible = false
                uploadProgressIndicator.isIndeterminate = !isKnown
                uploadProgressIndicator.isVisible = true
            }
            if (isKnown) {
                uploadProgressIndicator.setProgressCompat(percent, true)
            }
        }

        private fun bindBadge(state: ImportQueueItemState) = with(view) {
            val (icon, colorAttr) = when (state) {
                ImportQueueItemState.PENDING ->
                    R.drawable.ic_schedule to null

                ImportQueueItemState.UPLOADING ->
                    R.drawable.ic_upload to com.google.android.material.R.attr.colorPrimary

                ImportQueueItemState.UPLOADED ->
                    R.drawable.ic_cloud_upload to com.google.android.material.R.attr.colorPrimary

                ImportQueueItemState.IMPORTED ->
                    R.drawable.ic_done to com.google.android.material.R.attr.colorPrimary

                ImportQueueItemState.ALREADY_IN_LIBRARY ->
                    R.drawable.ic_cloud_done to com.google.android.material.R.attr.colorOutline

                ImportQueueItemState.FAILED ->
                    R.drawable.ic_priority_high to com.google.android.material.R.attr.colorError

                ImportQueueItemState.CANCELLED ->
                    R.drawable.ic_close to null
            }
            badgeImageView.setImageResource(icon)
            badgeLayout.backgroundTintList = ColorStateList.valueOf(
                if (colorAttr != null)
                    MaterialColors.getColor(badgeLayout, colorAttr)
                else
                    BADGE_NEUTRAL_COLOR
            )
        }

        fun unbind() {
            boundItemId = null
            picasso.cancelRequest(view.imageView)
        }
    }

    private inner class ProblemViewHolder(
        private val view: ListItemImportQueueProblemBinding,
    ) : RecyclerView.ViewHolder(view.root) {
        private var boundItemId: Long? = null

        fun bind(item: ImportQueueOverview.Item) = with(view) {
            if (boundItemId != item.id) {
                boundItemId = item.id
                loadThumbnail(item, imageView)
            }

            nameTextView.text = item.displayName
            reasonTextView.setText(getFailureText(item.failureReason))
            retryButton.setOnClickListener {
                viewModel.onRetryItemClicked(item.id)
            }
            root.setOnClickListener {
                showItemDialog(item)
            }
        }

        fun unbind() {
            boundItemId = null
            picasso.cancelRequest(view.imageView)
        }
    }

    private object ItemDiffCallback : DiffUtil.ItemCallback<ListItem>() {
        override fun areItemsTheSame(oldItem: ListItem, newItem: ListItem): Boolean = when {
            oldItem is ListItem.Summary && newItem is ListItem.Summary ->
                true

            oldItem is ListItem.Section && newItem is ListItem.Section ->
                oldItem.batch.id == newItem.batch.id

            oldItem is ListItem.Media && newItem is ListItem.Media ->
                oldItem.item.id == newItem.item.id

            oldItem is ListItem.Problem && newItem is ListItem.Problem ->
                oldItem.item.id == newItem.item.id

            else ->
                oldItem === newItem
        }

        @Suppress("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: ListItem, newItem: ListItem) =
            oldItem == newItem
    }

    private companion object {
        private const val VIEW_TYPE_SUMMARY = 0
        private const val VIEW_TYPE_SECTION = 1
        private const val VIEW_TYPE_MEDIA = 2
        private const val VIEW_TYPE_PROBLEM = 3
        private const val VIEW_TYPE_MESSAGE = 4
        private const val DIALOG_THUMBNAIL_SIZE_PX = 800
        private const val BADGE_NEUTRAL_COLOR = 0x99000000.toInt()
    }
}
