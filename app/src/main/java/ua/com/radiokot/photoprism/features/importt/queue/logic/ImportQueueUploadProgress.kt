package ua.com.radiokot.photoprism.features.importt.queue.logic

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject

/**
 * Progress of the files being uploaded right now.
 * It is only kept in memory, as it is only of interest while watching.
 */
class ImportQueueUploadProgress {
    private val percentByItemId = mutableMapOf<Long, Int>()
    private val subject = BehaviorSubject.createDefault<Map<Long, Int>>(emptyMap())

    /**
     * Upload percent by queue item ID, [UNKNOWN] while the file is being checked.
     */
    val percentsByItemId: Observable<Map<Long, Int>> = subject

    @Synchronized
    fun set(itemId: Long, percent: Int) {
        if (percentByItemId.put(itemId, percent) != percent) {
            subject.onNext(percentByItemId.toMap())
        }
    }

    @Synchronized
    fun clear(itemId: Long) {
        if (percentByItemId.remove(itemId) != null) {
            subject.onNext(percentByItemId.toMap())
        }
    }

    companion object {
        const val UNKNOWN = -1
    }
}
