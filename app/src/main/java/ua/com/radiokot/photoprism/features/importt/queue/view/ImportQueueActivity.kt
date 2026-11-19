package ua.com.radiokot.photoprism.features.importt.queue.view

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koin.androidx.viewmodel.ext.android.viewModel
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.base.view.BaseActivity
import ua.com.radiokot.photoprism.databinding.ActivityImportQueueBinding
import ua.com.radiokot.photoprism.databinding.ListItemImportQueueBatchBinding
import ua.com.radiokot.photoprism.extension.barsAndCutoutPadding
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueBatch
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemState
import ua.com.radiokot.photoprism.features.importt.queue.view.model.ImportQueueViewModel

class ImportQueueActivity : BaseActivity() {

    private lateinit var view: ActivityImportQueueBinding
    private val viewModel: ImportQueueViewModel by viewModel()
    private val adapter = BatchesAdapter()

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

        view.batchesRecyclerView.adapter = adapter

        viewModel.batches.observe(this) { batches ->
            adapter.submitList(batches)
            view.emptyTextView.isVisible = batches.isEmpty()
        }
        viewModel.isWorking.observe(this) {
            adapter.isWorking = it
            invalidateOptionsMenu()
        }
        viewModel.isPaused.observe(this) {
            adapter.isPaused = it
            invalidateOptionsMenu()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_queue, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.pause)?.isVisible = viewModel.isWorking.value == true
        menu.findItem(R.id.resume)?.isVisible = viewModel.isPaused.value == true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.pause ->
                viewModel.onPauseClicked()

            R.id.resume ->
                viewModel.onResumeClicked()

            R.id.remove_finished ->
                viewModel.onRemoveFinishedClicked()

            android.R.id.home ->
                finish()

            else ->
                return super.onOptionsItemSelected(item)
        }
        return true
    }

    private inner class BatchesAdapter :
        ListAdapter<ImportQueueBatch, BatchViewHolder>(BatchDiffCallback) {

        var isWorking = false
            set(value) {
                if (field != value) {
                    field = value
                    @Suppress("NotifyDataSetChanged")
                    notifyDataSetChanged()
                }
            }

        var isPaused = false
            set(value) {
                if (field != value) {
                    field = value
                    @Suppress("NotifyDataSetChanged")
                    notifyDataSetChanged()
                }
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            BatchViewHolder(
                ListItemImportQueueBatchBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        override fun onBindViewHolder(holder: BatchViewHolder, position: Int) =
            holder.bind(getItem(position), isWorking, isPaused)
    }

    private inner class BatchViewHolder(
        private val itemView: ListItemImportQueueBatchBinding,
    ) : RecyclerView.ViewHolder(itemView.root) {

        fun bind(
            batch: ImportQueueBatch,
            isWorking: Boolean,
            isPaused: Boolean,
        ) = with(itemView) {
            val context = root.context
            val pendingCount = batch.count(
                ImportQueueItemState.PENDING,
                ImportQueueItemState.UPLOADING,
                ImportQueueItemState.UPLOADED,
            )
            val failedCount = batch.count(ImportQueueItemState.FAILED)
            val isInProgress = batch.isInProgress && isWorking

            titleTextView.text = buildString {
                append(DateFormat.getMediumDateFormat(context).format(batch.createdAt))
                append(", ")
                append(DateFormat.getTimeFormat(context).format(batch.createdAt))
            }

            val stateText = context.getString(
                when {
                    isInProgress ->
                        R.string.import_queue_state_in_progress

                    pendingCount > 0 && isPaused ->
                        R.string.import_queue_paused

                    pendingCount > 0 ->
                        R.string.import_queue_state_waiting

                    batch.count(ImportQueueItemState.CANCELLED) == batch.totalCount ->
                        R.string.import_queue_state_cancelled

                    failedCount > 0 ->
                        R.string.import_queue_state_done_with_errors

                    else ->
                        R.string.import_queue_state_done
                }
            )
            stateTextView.text =
                if (batch.albums.isEmpty())
                    stateText
                else
                    "$stateText · ${batch.albums.joinToString { it.title }}"

            progressIndicator.max = batch.totalCount.coerceAtLeast(1)
            progressIndicator.setProgressCompat(batch.finishedCount, true)

            countsTextView.text = buildString {
                append(
                    context.getString(
                        R.string.template_import_queue_batch_progress,
                        batch.finishedCount,
                        batch.totalCount,
                    )
                )
                append('\n')
                append(
                    context.getString(
                        R.string.template_import_queue_result,
                        batch.count(ImportQueueItemState.IMPORTED),
                        batch.count(ImportQueueItemState.ALREADY_IN_LIBRARY),
                        failedCount,
                    )
                )
                val cancelledCount = batch.count(ImportQueueItemState.CANCELLED)
                if (cancelledCount > 0) {
                    append(
                        context.getString(
                            R.string.template_import_queue_cancelled_count,
                            cancelledCount,
                        )
                    )
                }
            }

            failedTitleTextView.isVisible = batch.failedItems.isNotEmpty()
            failedFilesTextView.isVisible = batch.failedItems.isNotEmpty()
            failedFilesTextView.text = buildString {
                batch.failedItems.take(MAX_FAILED_ITEMS_SHOWN).forEachIndexed { i, item ->
                    if (i > 0) {
                        append('\n')
                    }
                    append(item.displayName)
                    if (item.error != null) {
                        append(" – ")
                        append(item.error)
                    }
                }
                val notShownCount = batch.failedItems.size - MAX_FAILED_ITEMS_SHOWN
                if (notShownCount > 0) {
                    append('\n')
                    append(
                        context.getString(
                            R.string.template_import_queue_more_failed_files,
                            notShownCount,
                        )
                    )
                }
            }

            retryFailedButton.isVisible = failedCount > 0
            retryFailedButton.setOnClickListener {
                viewModel.onRetryFailedClicked(batch.id)
            }

            cancelWaitingButton.isVisible = batch.count(ImportQueueItemState.PENDING) > 0
            cancelWaitingButton.setOnClickListener {
                viewModel.onCancelWaitingClicked(batch.id)
            }

            removeButton.isVisible = !isInProgress
            removeButton.setOnClickListener {
                viewModel.onRemoveClicked(batch.id)
            }
        }
    }

    private object BatchDiffCallback : DiffUtil.ItemCallback<ImportQueueBatch>() {
        override fun areItemsTheSame(oldItem: ImportQueueBatch, newItem: ImportQueueBatch) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ImportQueueBatch, newItem: ImportQueueBatch) =
            oldItem == newItem
    }

    private companion object {
        private const val MAX_FAILED_ITEMS_SHOWN = 5
    }
}
