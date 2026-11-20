package ua.com.radiokot.photoprism.features.importt.queue.view.model

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.WorkInfo
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemState
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueOverview
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueueRepository
import ua.com.radiokot.photoprism.features.importt.queue.logic.ImportQueueWorker
import java.util.concurrent.TimeUnit

/**
 * Shows the whole upload queue as one list of files,
 * with an overall status on top and a filter by result.
 */
class ImportQueueViewModel(
    private val repository: ImportQueueRepository,
    application: Application,
) : AndroidViewModel(application) {
    private val log = kLogger("ImportQueueVM")
    private val context = getApplication<Application>()

    private val overview = MutableLiveData<ImportQueueOverview>()
    private val filter = MutableLiveData(Filter.ALL)

    private val workState: LiveData<WorkState> =
        MediatorLiveData<WorkState>().apply {
            addSource(ImportQueueWorker.getWorkInfos(context)) { workInfos ->
                value = when {
                    workInfos.any { it.state == WorkInfo.State.RUNNING } ->
                        WorkState.RUNNING

                    workInfos.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                    } ->
                        WorkState.WAITING

                    else ->
                        WorkState.IDLE
                }
            }
        }

    /**
     * Summary on top, then the files grouped by when they were added.
     * Empty if there is nothing in the queue.
     */
    val listItems: LiveData<List<ListItem>> =
        MediatorLiveData<List<ListItem>>().apply {
            fun update() {
                val overview = overview.value
                    ?: return
                value = buildListItems(
                    overview = overview,
                    workState = workState.value ?: WorkState.IDLE,
                    filter = filter.value!!,
                )
            }
            addSource(overview) { update() }
            addSource(workState) { update() }
            addSource(filter) { update() }
        }

    init {
        repository.changes
            .throttleLatest(500, TimeUnit.MILLISECONDS, true)
            .observeOn(Schedulers.io())
            .map { repository.getOverview() }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onNext = overview::setValue,
                onError = { error ->
                    log.error(error) {
                        "init(): failed_loading_overview"
                    }
                }
            )
            .autoDispose(this)
    }

    private fun buildListItems(
        overview: ImportQueueOverview,
        workState: WorkState,
        filter: Filter,
    ): List<ListItem> {
        if (overview.items.isEmpty()) {
            return emptyList()
        }

        val waitingCount = overview.count(*Filter.PENDING.states)
        val inProgressCount = overview.count(
            ImportQueueItemState.UPLOADING,
            ImportQueueItemState.UPLOADED,
        )
        val failedCount = overview.count(ImportQueueItemState.FAILED)
        val hasWorkLeft = waitingCount > 0
        val totalCount = overview.items.size - overview.count(ImportQueueItemState.CANCELLED)

        val status = when {
            hasWorkLeft && workState == WorkState.RUNNING ->
                Status.UPLOADING

            hasWorkLeft && workState == WorkState.WAITING ->
                Status.WAITING_FOR_CONNECTION

            hasWorkLeft ->
                Status.PAUSED

            failedCount > 0 ->
                Status.DONE_WITH_PROBLEMS

            else ->
                Status.DONE
        }

        val result = mutableListOf<ListItem>()
        result += ListItem.Summary(
            status = status,
            finishedCount = totalCount - waitingCount,
            totalCount = totalCount,
            failedCount = failedCount,
            canPause = hasWorkLeft && workState != WorkState.IDLE,
            canResume = hasWorkLeft && workState == WorkState.IDLE,
            countsByFilter = Filter.values().associateWith { overview.count(*it.states) },
            filter = filter,
            // Can't be removed while being uploaded.
            hasRemovableWaiting = waitingCount > inProgressCount,
        )

        val itemsByBatch = overview.items
            .filter { it.state in filter.states }
            .groupBy(ImportQueueOverview.Item::batchId)

        overview.batches.forEach { batch ->
            val batchItems = itemsByBatch[batch.id]
                ?: return@forEach

            result += ListItem.Section(
                batch = batch,
                fileCount = batchItems.size,
            )
            batchItems.forEach { item ->
                result +=
                    if (filter == Filter.FAILED)
                        ListItem.Problem(item)
                    else
                        ListItem.Media(item)
            }
        }

        if (itemsByBatch.isEmpty()) {
            result += ListItem.NothingInFilter
        }

        return result
    }

    fun onFilterSelected(filter: Filter) {
        if (this.filter.value != filter) {
            this.filter.value = filter
        }
    }

    fun onPauseClicked() {
        log.debug { "onPauseClicked(): pausing" }
        ImportQueueWorker.pause(context)
    }

    fun onResumeClicked() {
        log.debug { "onResumeClicked(): resuming" }
        ImportQueueWorker.schedule(context)
    }

    fun onRetryAllFailedClicked() = runInBackground {
        repository.retryAllFailedItems()
        ImportQueueWorker.schedule(context)
    }

    fun onRetryItemClicked(itemId: Long) = runInBackground {
        repository.retryItem(itemId)
        ImportQueueWorker.schedule(context)
    }

    fun onRemoveItemClicked(itemId: Long) = runInBackground {
        repository.removeItem(itemId)
    }

    fun onRemoveDoneClicked() = runInBackground {
        repository.removeDoneItems()
    }

    fun onRemoveFailedClicked() = runInBackground {
        repository.removeFailedItems()
    }

    fun onRemovePendingConfirmed() = runInBackground {
        repository.removePendingItems()
    }

    private fun runInBackground(action: () -> Unit) {
        Completable.fromAction(action)
            .subscribeOn(Schedulers.io())
            .subscribeBy(
                onError = { error ->
                    log.error(error) {
                        "runInBackground(): failed"
                    }
                }
            )
            .autoDispose(this)
    }

    private enum class WorkState {
        RUNNING,

        /**
         * Scheduled but waiting for the network or for a retry.
         */
        WAITING,
        IDLE,
    }

    enum class Status {
        UPLOADING,
        WAITING_FOR_CONNECTION,
        PAUSED,
        DONE,
        DONE_WITH_PROBLEMS,
    }

    enum class Filter(vararg val states: ImportQueueItemState) {
        ALL(*ImportQueueItemState.values()),
        PENDING(
            ImportQueueItemState.PENDING,
            ImportQueueItemState.UPLOADING,
            ImportQueueItemState.UPLOADED,
        ),
        IMPORTED(ImportQueueItemState.IMPORTED),
        ALREADY_IN_LIBRARY(ImportQueueItemState.ALREADY_IN_LIBRARY),
        FAILED(ImportQueueItemState.FAILED),
        ;
    }

    sealed interface ListItem {
        data class Summary(
            val status: Status,
            val finishedCount: Int,
            val totalCount: Int,
            val failedCount: Int,
            val canPause: Boolean,
            val canResume: Boolean,
            val countsByFilter: Map<Filter, Int>,
            val filter: Filter,
            val hasRemovableWaiting: Boolean,
        ) : ListItem

        data class Section(
            val batch: ImportQueueOverview.Batch,
            val fileCount: Int,
        ) : ListItem

        data class Media(
            val item: ImportQueueOverview.Item,
        ) : ListItem

        data class Problem(
            val item: ImportQueueOverview.Item,
        ) : ListItem

        object NothingInFilter : ListItem
    }
}
