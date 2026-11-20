package ua.com.radiokot.photoprism.features.importt.picker.view

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.squareup.picasso.Picasso
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.base.view.BaseActivity
import ua.com.radiokot.photoprism.databinding.ActivityLocalMediaPickerBinding
import ua.com.radiokot.photoprism.databinding.ListItemLocalMediaBinding
import ua.com.radiokot.photoprism.databinding.ListItemLocalMediaFolderBinding
import ua.com.radiokot.photoprism.databinding.ListItemLocalMediaFolderHeaderBinding
import ua.com.radiokot.photoprism.databinding.ListItemLocalMediaFolderMoreBinding
import ua.com.radiokot.photoprism.databinding.SheetLocalMediaFoldersBinding
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.barsAndCutout
import ua.com.radiokot.photoprism.extension.barsAndCutoutPadding
import ua.com.radiokot.photoprism.features.importt.picker.view.model.LocalMediaPickerViewModel
import ua.com.radiokot.photoprism.features.importt.picker.view.model.LocalMediaPickerViewModel.Item
import ua.com.radiokot.photoprism.features.importt.picker.view.model.LocalMediaPickerViewModel.Status
import ua.com.radiokot.photoprism.features.importt.view.ImportActivity
import java.text.NumberFormat

/**
 * Shows local photos and videos to pick for upload,
 * hiding the ones which are already in the library.
 */
class LocalMediaPickerActivity : BaseActivity() {

    private lateinit var view: ActivityLocalMediaPickerBinding
    private val viewModel: LocalMediaPickerViewModel by viewModel()
    private val picasso: Picasso by inject()
    private val adapter = MediaAdapter()
    private var thumbnailSizePx = 0
    private val numberFormat = NumberFormat.getIntegerInstance()

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.onPermissionsChecked() }

    private val systemPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
        this::onSystemPickerResult,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (goToEnvConnectionIfNoSession()) {
            return
        }

        view = ActivityLocalMediaPickerBinding.inflate(layoutInflater)
        setContentView(view.root)

        setSupportActionBar(view.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        view.folderSelectorLayout.setOnClickListener {
            showFoldersSheet()
        }
        view.toolbar.barsAndCutoutPadding()
        applyBottomInsets()

        val spanCount = (resources.displayMetrics.widthPixels /
                (resources.displayMetrics.density * MIN_THUMBNAIL_SIZE_DP)).toInt()
            .coerceAtLeast(3)
        thumbnailSizePx = resources.displayMetrics.widthPixels / spanCount
        view.mediaRecyclerView.layoutManager = GridLayoutManager(this, spanCount)
        view.mediaRecyclerView.adapter = adapter
        view.mediaRecyclerView.setHasFixedSize(true)

        view.continueButton.setOnClickListener {
            viewModel.onContinueClicked()
        }

        subscribeToState()

        viewModel.events
            .subscribe { event ->
                when (event) {
                    is LocalMediaPickerViewModel.Event.OpenImport -> {
                        startActivity(ImportActivity.getIntentForFiles(this, event.files))
                        finish()
                    }
                }
            }
            .autoDispose(this)

        if (savedInstanceState == null) {
            permissionsLauncher.launch(viewModel.permissionsToRequest)
        } else {
            viewModel.onPermissionsChecked()
        }
    }

    private fun applyBottomInsets() {
        val fabMargin = (view.continueButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val listPadding = view.mediaRecyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view.root) { _, insets ->
            val bottom = insets.barsAndCutout().bottom
            view.continueButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = fabMargin + bottom
            }
            view.mediaRecyclerView.updatePadding(bottom = listPadding + bottom)
            insets
        }
    }

    private fun subscribeToState() {
        viewModel.items.observe(this) { items ->
            adapter.submitList(items)
            updateEmptyState()
        }

        viewModel.selectedBucket.observe(this) { bucket ->
            view.folderNameTextView.text = bucket?.name
                ?: getString(R.string.local_media_picker_all_media)
            view.folderLocationTextView.text =
                if (bucket != null)
                    getFolderLocation(bucket, withStorage = true)
                else
                    getString(R.string.local_media_picker_all_folders_location)
        }

        viewModel.checkProgress.observe(this) { progress ->
            view.progressIndicator.isVisible = progress != null && !progress.isDone
            if (progress == null) {
                view.statusTextView.text = null
                return@observe
            }

            view.progressIndicator.max = progress.total.coerceAtLeast(1)
            view.progressIndicator.setProgressCompat(progress.checked, true)
            view.statusTextView.text = when {
                progress.isLibraryUnavailable ->
                    getString(R.string.local_media_picker_library_unavailable)

                !progress.isDone ->
                    getString(
                        R.string.template_local_media_picker_checking,
                        numberFormat.format(progress.checked),
                        numberFormat.format(progress.total),
                    )

                viewModel.isHidingInLibrary.value == true ->
                    getString(R.string.template_local_media_picker_hidden, numberFormat.format(progress.inLibrary))

                else ->
                    getString(R.string.template_local_media_picker_marked, numberFormat.format(progress.inLibrary))
            }
            updateEmptyState()
        }

        viewModel.selectedCount.observe(this) { count ->
            view.continueButton.isVisible = count > 0
            view.continueButton.text = getString(R.string.template_local_media_picker_continue, count)
        }

        viewModel.isPermissionMissing.observe(this) {
            updateEmptyState()
        }
    }

    /**
     * @return where the folder is, like "SD card · DCIM › Camera".
     */
    private fun getFolderLocation(bucket: LocalMediaPickerViewModel.Bucket, withStorage: Boolean): String {
        // Only the last folders are meaningful for long paths.
        val path = bucket.path
            ?.split('/')
            ?.let { segments ->
                if (segments.size > MAX_PATH_SEGMENTS)
                    listOf("…") + segments.takeLast(MAX_PATH_SEGMENTS)
                else
                    segments
            }
            ?.joinToString(" › ")
        val storage = getString(
            if (bucket.isOnSdCard)
                R.string.local_media_picker_sd_card_short
            else
                R.string.local_media_picker_device_short
        )
        return when {
            withStorage && path != null -> "$storage · $path"
            withStorage -> storage
            else -> path.orEmpty()
        }
    }

    private fun showFoldersSheet() {
        val buckets = viewModel.buckets.value.orEmpty()
        val selectedBucketId = viewModel.selectedBucket.value?.id
        val dialog = BottomSheetDialog(this)
        val sheetView = SheetLocalMediaFoldersBinding.inflate(layoutInflater)

        val rows = mutableListOf<FolderRow>()
        rows += FolderRow.Folder(bucket = null, isSelected = selectedBucketId == null)
        listOf(false, true).forEach { isOnSdCard ->
            val storageBuckets = buckets.filter { it.isOnSdCard == isOnSdCard }
            if (storageBuckets.isEmpty()) {
                return@forEach
            }
            rows += FolderRow.Header(isOnSdCard)

            // Small folders are collapsed not to bury the big ones,
            // they are sorted by size.
            val (shown, collapsed) = storageBuckets
                .withIndex()
                .partition { (index, bucket) ->
                    index < ALWAYS_SHOWN_FOLDERS
                            || bucket.count >= SMALL_FOLDER_SIZE
                            || bucket.id == selectedBucketId
                }
            shown.forEach { (_, bucket) ->
                rows += FolderRow.Folder(bucket, isSelected = bucket.id == selectedBucketId)
            }
            if (collapsed.isNotEmpty()) {
                rows += FolderRow.ShowMore(
                    folders = collapsed.map { (_, bucket) -> FolderRow.Folder(bucket, isSelected = false) },
                )
            }
        }

        sheetView.foldersRecyclerView.layoutManager = LinearLayoutManager(this)
        sheetView.foldersRecyclerView.adapter = FoldersAdapter(rows) { bucket ->
            dialog.dismiss()
            viewModel.onBucketSelected(bucket?.id)
            view.mediaRecyclerView.scrollToPosition(0)
        }

        dialog.setContentView(sheetView.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private sealed interface FolderRow {
        class Header(val isOnSdCard: Boolean) : FolderRow
        class Folder(
            val bucket: LocalMediaPickerViewModel.Bucket?,
            val isSelected: Boolean,
        ) : FolderRow

        class ShowMore(val folders: List<Folder>) : FolderRow
    }

    private inner class FoldersAdapter(
        rows: List<FolderRow>,
        private val onFolderClicked: (LocalMediaPickerViewModel.Bucket?) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val coverSizePx = (56 * resources.displayMetrics.density).toInt()
        private val rows = rows.toMutableList()

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is FolderRow.Header -> 0
            is FolderRow.Folder -> 1
            is FolderRow.ShowMore -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val itemView = when (viewType) {
                0 -> ListItemLocalMediaFolderHeaderBinding.inflate(inflater, parent, false).root
                1 -> ListItemLocalMediaFolderBinding.inflate(inflater, parent, false).root
                else -> ListItemLocalMediaFolderMoreBinding.inflate(inflater, parent, false).root
            }
            return object : RecyclerView.ViewHolder(itemView) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is FolderRow.ShowMore -> with(ListItemLocalMediaFolderMoreBinding.bind(holder.itemView)) {
                    textView.text = resources.getQuantityString(
                        R.plurals.local_media_picker_show_more_folders,
                        row.folders.size,
                        row.folders.size,
                    )
                    root.setOnClickListener {
                        val index = rows.indexOf(row)
                        rows.removeAt(index)
                        rows.addAll(index, row.folders)
                        notifyItemRemoved(index)
                        notifyItemRangeInserted(index, row.folders.size)
                    }
                }

                is FolderRow.Header -> with(ListItemLocalMediaFolderHeaderBinding.bind(holder.itemView)) {
                    iconImageView.setImageResource(
                        if (row.isOnSdCard) R.drawable.ic_sd_card else R.drawable.ic_smartphone
                    )
                    titleTextView.setText(
                        if (row.isOnSdCard)
                            R.string.local_media_picker_on_sd_card
                        else
                            R.string.local_media_picker_on_device
                    )
                }

                is FolderRow.Folder -> with(ListItemLocalMediaFolderBinding.bind(holder.itemView)) {
                    val bucket = row.bucket
                    if (bucket != null) {
                        picasso
                            .load(bucket.coverUri)
                            .resize(coverSizePx, coverSizePx)
                            .centerCrop()
                            .placeholder(R.drawable.image_placeholder)
                            .error(R.drawable.image_error)
                            .into(coverImageView)
                        coverImageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        coverImageView.imageTintList = null
                    } else {
                        picasso.cancelRequest(coverImageView)
                        coverImageView.setImageResource(R.drawable.ic_photo_library)
                        coverImageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        coverImageView.imageTintList = ColorStateList.valueOf(
                            MaterialColors.getColor(
                                coverImageView,
                                com.google.android.material.R.attr.colorPrimary,
                            )
                        )
                    }

                    nameTextView.text = bucket?.name
                        ?: getString(R.string.local_media_picker_all_media)
                    val location = bucket?.let { getFolderLocation(it, withStorage = false) }
                    pathTextView.isVisible = !location.isNullOrEmpty() && location != bucket?.name
                    pathTextView.text = location

                    val count = bucket?.count ?: viewModel.buckets.value.orEmpty().sumOf { it.count }
                    val missingCount = bucket?.missingCount
                    countsTextView.text = when {
                        missingCount == null ->
                            numberFormat.format(count)

                        missingCount == count ->
                            getString(
                                R.string.template_local_media_picker_folder_nothing_uploaded,
                                numberFormat.format(count),
                            )

                        missingCount == 0 ->
                            getString(
                                R.string.template_local_media_picker_folder_all_uploaded,
                                numberFormat.format(count),
                            )

                        else ->
                            getString(
                                R.string.template_local_media_picker_folder_missing,
                                numberFormat.format(count),
                                numberFormat.format(missingCount),
                            )
                    }

                    selectedImageView.visibility = if (row.isSelected) View.VISIBLE else View.INVISIBLE
                    root.setOnClickListener { onFolderClicked(bucket) }
                }
            }
        }
    }

    private fun updateEmptyState() {
        val isPermissionMissing = viewModel.isPermissionMissing.value == true
        val isEmpty = adapter.itemCount == 0
        val progress = viewModel.checkProgress.value

        view.emptyLayout.isVisible = isPermissionMissing || (isEmpty && progress != null)
        view.emptyButton.isVisible = isPermissionMissing
        view.emptyTextView.text = getString(
            when {
                isPermissionMissing ->
                    R.string.local_media_picker_permission_missing

                progress == null || progress.total == 0 ->
                    R.string.local_media_picker_no_media

                !progress.isDone ->
                    R.string.local_media_picker_checking_empty

                else ->
                    R.string.local_media_picker_nothing_missing
            }
        )
        view.emptyButton.setText(R.string.local_media_picker_grant_access)
        view.emptyButton.setOnClickListener {
            permissionsLauncher.launch(viewModel.permissionsToRequest)
        }
    }

    private fun openSystemPicker() =
        systemPickerLauncher.launch(
            PickVisualMediaRequest(
                accentColor = MaterialColors.getColor(
                    window.decorView,
                    com.google.android.material.R.attr.colorPrimary,
                ).toLong()
            )
        )

    private fun onSystemPickerResult(uris: List<@JvmSuppressWildcards Uri>) {
        if (uris.isNotEmpty()) {
            startActivity(ImportActivity.getIntent(this, uris = uris))
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.local_media_picker, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.show_in_library)?.isChecked = viewModel.isHidingInLibrary.value != true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.select_all ->
                viewModel.onSelectAllClicked()

            R.id.clear_selection ->
                viewModel.onClearSelectionClicked()

            R.id.show_in_library ->
                viewModel.onHideInLibraryChanged(item.isChecked)

            R.id.system_picker ->
                openSystemPicker()

            android.R.id.home ->
                finish()

            else ->
                return super.onOptionsItemSelected(item)
        }
        return true
    }

    private inner class MediaAdapter : ListAdapter<Item, MediaViewHolder>(ItemDiffCallback) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MediaViewHolder(
                ListItemLocalMediaBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) =
            holder.bind(getItem(position))

        override fun onViewRecycled(holder: MediaViewHolder) {
            picasso.cancelRequest(holder.view.imageView)
        }
    }

    private inner class MediaViewHolder(
        val view: ListItemLocalMediaBinding,
    ) : RecyclerView.ViewHolder(view.root) {
        private var boundMediaId: Long? = null

        fun bind(item: Item) {
            if (boundMediaId != item.media.id) {
                boundMediaId = item.media.id
                picasso
                    .load(item.media.uri)
                    .resize(thumbnailSizePx, thumbnailSizePx)
                    .centerCrop()
                    .placeholder(R.drawable.image_placeholder)
                    .error(R.drawable.image_error)
                    .into(view.imageView)
            }

            view.videoImageView.isVisible = item.media.isVideo
            view.inLibraryImageView.isVisible = item.status == Status.IN_LIBRARY
            view.imageView.alpha = if (item.status == Status.IN_LIBRARY) 0.5f else 1f
            view.selectionCheckBox.isVisible = item.isSelected
            val scale = if (item.isSelected) SELECTED_SCALE else 1f
            view.imageView.scaleX = scale
            view.imageView.scaleY = scale
            view.root.contentDescription = item.media.displayName
            view.root.setOnClickListener {
                viewModel.onItemClicked(item)
            }
        }
    }

    private object ItemDiffCallback : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item) =
            oldItem.media.id == newItem.media.id

        override fun areContentsTheSame(oldItem: Item, newItem: Item) =
            oldItem == newItem
    }

    private companion object {
        private const val ALWAYS_SHOWN_FOLDERS = 6
        private const val SMALL_FOLDER_SIZE = 20
        private const val MAX_PATH_SEGMENTS = 3
        private const val MIN_THUMBNAIL_SIZE_DP = 80
        private const val SELECTED_SCALE = 0.85f
    }
}
