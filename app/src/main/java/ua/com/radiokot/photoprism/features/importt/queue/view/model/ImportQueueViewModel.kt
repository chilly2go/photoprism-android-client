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
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueBatch
import ua.com.radiokot.photoprism.features.importt.queue.data.model.ImportQueueItemState
import ua.com.radiokot.photoprism.features.importt.queue.data.storage.ImportQueueRepository
import ua.com.radiokot.photoprism.features.importt.queue.logic.ImportQueueWorker
import java.util.concurrent.TimeUnit

class ImportQueueViewModel(
    private val repository: ImportQueueRepository,
    application: Application,
) : AndroidViewModel(application) {
    private val log = kLogger("ImportQueueVM")
    private val context = getApplication<Application>()

    val batches: MutableLiveData<List<ImportQueueBatch>> = MutableLiveData()

    private val isWorkerActive: LiveData<Boolean> =
        MediatorLiveData<Boolean>().apply {
            addSource(ImportQueueWorker.getWorkInfos(context)) { workInfos ->
                value = workInfos.any { workInfo ->
                    workInfo.state == WorkInfo.State.RUNNING
                            || workInfo.state == WorkInfo.State.ENQUEUED
                            || workInfo.state == WorkInfo.State.BLOCKED
                }
            }
        }

    /**
     * Whether there are waiting files but the queue is not being worked through.
     */
    val isPaused: LiveData<Boolean> =
        MediatorLiveData<Boolean>().apply {
            fun update() {
                val hasPendingItems = batches.value.orEmpty().any { batch ->
                    batch.count(ImportQueueItemState.PENDING) > 0
                }
                value = hasPendingItems && isWorkerActive.value != true
            }
            addSource(batches) { update() }
            addSource(isWorkerActive) { update() }
        }

    val isWorking: LiveData<Boolean> = isWorkerActive

    init {
        repository.changes
            .throttleLatest(500, TimeUnit.MILLISECONDS, true)
            .observeOn(Schedulers.io())
            .map { repository.getBatches().reversed() }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onNext = batches::setValue,
                onError = { error ->
                    log.error(error) {
                        "init(): failed_loading_batches"
                    }
                }
            )
            .autoDispose(this)
    }

    fun onRetryFailedClicked(batchId: Long) = runInBackground {
        repository.retryFailedItems(batchId)
        ImportQueueWorker.schedule(context)
    }

    fun onCancelWaitingClicked(batchId: Long) = runInBackground {
        repository.cancelPendingItems(batchId)
    }

    fun onRemoveClicked(batchId: Long) = runInBackground {
        repository.removeBatch(batchId)
    }

    fun onRemoveFinishedClicked() = runInBackground {
        repository.removeFinishedBatches()
    }

    fun onPauseClicked() {
        log.debug { "onPauseClicked(): pausing" }
        ImportQueueWorker.pause(context)
    }

    fun onResumeClicked() {
        log.debug { "onResumeClicked(): resuming" }
        ImportQueueWorker.schedule(context)
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
}
