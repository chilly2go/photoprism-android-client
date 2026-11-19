package ua.com.radiokot.photoprism.features.importt.picker.view

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.squareup.picasso.Picasso
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.base.view.BaseActivity
import ua.com.radiokot.photoprism.databinding.ActivityLocalMediaPickerBinding
import ua.com.radiokot.photoprism.databinding.ListItemLocalMediaBinding
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.barsAndCutout
import ua.com.radiokot.photoprism.extension.barsAndCutoutPadding
import ua.com.radiokot.photoprism.features.importt.picker.view.model.LocalMediaPickerViewModel
import ua.com.radiokot.photoprism.features.importt.picker.view.model.LocalMediaPickerViewModel.Item
import ua.com.radiokot.photoprism.features.importt.picker.view.model.LocalMediaPickerViewModel.Status
import ua.com.radiokot.photoprism.features.importt.view.ImportActivity

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
        view.toolbar.barsAndCutoutPadding()
        applyBottomInsets()

        val spanCount = (resources.displayMetrics.widthPixels /
                (resources.displayMetrics.density * MIN_THUMBNAIL_SIZE_DP)).toInt()
            .coerceAtLeast(3)
        thumbnailSizePx = resources.displayMetrics.widthPixels / spanCount
        view.mediaRecyclerView.layoutManager = GridLayoutManager(this, spanCount)
        view.mediaRecyclerView.adapter = adapter
        view.mediaRecyclerView.setHasFixedSize(true)

        view.hideInLibrarySwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.onHideInLibraryChanged(isChecked)
        }
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

        viewModel.buckets.observe(this) { buckets ->
            val chipGroup = view.bucketsChipGroup
            chipGroup.setOnCheckedStateChangeListener(null)
            chipGroup.removeAllViews()
            chipGroup.addView(createChip(getString(R.string.local_media_picker_all_folders), null))
            buckets.forEach { bucket ->
                chipGroup.addView(
                    createChip(
                        getString(
                            R.string.template_local_media_picker_bucket,
                            if (bucket.isOnSdCard)
                                getString(R.string.template_local_media_picker_sd_card_bucket, bucket.name)
                            else
                                bucket.name,
                            bucket.count
                        ),
                        bucket.id,
                    )
                )
            }
            chipGroup.check(chipGroup.getChildAt(0).id)
            chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
                val bucketId = checkedIds.firstOrNull()
                    ?.let { group.findViewById<Chip>(it).tag as String? }
                viewModel.onBucketSelected(bucketId)
                view.mediaRecyclerView.scrollToPosition(0)
            }
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
                    getString(R.string.template_local_media_picker_checking, progress.checked, progress.total)

                else ->
                    getString(R.string.template_local_media_picker_checked, progress.inLibrary, progress.total)
            }
            updateEmptyState()
        }

        viewModel.selectedCount.observe(this) { count ->
            view.continueButton.isVisible = count > 0
            view.continueButton.text = getString(R.string.template_local_media_picker_continue, count)
            supportActionBar?.subtitle =
                if (count > 0)
                    getString(R.string.template_local_media_picker_selected, count)
                else
                    null
        }

        viewModel.isPermissionMissing.observe(this) {
            updateEmptyState()
        }
    }

    private fun createChip(text: String, bucketId: String?) =
        (layoutInflater.inflate(
            R.layout.chip_local_media_bucket,
            view.bucketsChipGroup,
            false
        ) as Chip).apply {
            id = ViewGroup.generateViewId()
            this.text = text
            tag = bucketId
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.select_all ->
                viewModel.onSelectAllClicked()

            R.id.clear_selection ->
                viewModel.onClearSelectionClicked()

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
        private const val MIN_THUMBNAIL_SIZE_DP = 80
        private const val SELECTED_SCALE = 0.85f
    }
}
