package ua.com.radiokot.photoprism.features.importt.queue.logic

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Makes sure only one worker processes the queue at a time.
 *
 * WorkManager considers a stopped worker done right away,
 * while its thread may still be finishing an upload.
 * A worker started meanwhile must wait for it,
 * otherwise both would take the same files.
 */
class ImportQueueRunGuard {
    private val semaphore = Semaphore(1)

    /**
     * Waits until no other worker is processing the queue.
     *
     * @return true if the run may start and [release] must be called once it ends,
     * false if [isStopped] became true while waiting.
     */
    fun acquire(isStopped: () -> Boolean): Boolean {
        while (!isStopped()) {
            if (semaphore.tryAcquire(WAIT_STEP_MS, TimeUnit.MILLISECONDS)) {
                return true
            }
        }
        return false
    }

    fun release() =
        semaphore.release()

    private companion object {
        private const val WAIT_STEP_MS = 500L
    }
}
