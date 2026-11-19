package ua.com.radiokot.photoprism.features.importt.view

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.importt.queue.logic.ImportQueueProcessor
import ua.com.radiokot.photoprism.features.importt.queue.view.ImportQueueActivity

class ImportNotificationsManager(
    private val context: Context,
) {
    private val log = kLogger("ImportNotificationsManager")
    private val notificationsManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }

    private val areNotificationsEnabled: Boolean
        get() = notificationsManager.areNotificationsEnabled()

    private val openQueueIntent: PendingIntent
        get() = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ImportQueueActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * @param progress current progress, if known
     * @param pauseIntent intent to stop working through the queue, keeping its content
     */
    fun getQueueProgressNotification(
        progress: ImportQueueProcessor.Progress?,
        pauseIntent: PendingIntent,
    ): Notification {
        ensureChannel()

        val builder = NotificationCompat.Builder(
            context,
            CHANNEL_ID
        )
            .setColor(ContextCompat.getColor(context, R.color.md_theme_light_primary))
            // White icon is used for Android 5 compatibility.
            .setSmallIcon(R.drawable.ic_upload_white)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openQueueIntent)
            .addAction(R.drawable.ic_close, context.getString(R.string.import_queue_pause), pauseIntent)

        if (progress == null) {
            builder
                .setContentTitle(context.getString(R.string.import_notification_progress_title))
                .setProgress(100, 0, true)
        } else {
            builder
                .setContentTitle(
                    context.getString(
                        R.string.template_import_queue_progress_title,
                        progress.batchNumber,
                        progress.batchCount,
                    )
                )
                .setContentText(
                    context.getString(
                        when (progress.phase) {
                            ImportQueueProcessor.Phase.UPLOADING ->
                                R.string.template_import_queue_progress_uploading

                            ImportQueueProcessor.Phase.IMPORTING ->
                                R.string.template_import_queue_progress_importing
                        },
                        progress.finishedInBatch,
                        progress.totalInBatch,
                    )
                )
                .setProgress(
                    progress.totalInBatch.coerceAtLeast(1),
                    progress.finishedInBatch,
                    false
                )
        }

        return builder.build()
    }

    fun notifyQueueFinished(result: ImportQueueProcessor.Result) {
        val notification = NotificationCompat.Builder(
            context,
            CHANNEL_ID
        )
            .setContentTitle(
                context.getString(
                    if (result.failedCount > 0)
                        R.string.import_queue_notification_done_with_errors_title
                    else
                        R.string.import_queue_notification_done_title
                )
            )
            .setContentText(
                context.getString(
                    R.string.template_import_queue_result,
                    result.importedCount,
                    result.alreadyInLibraryCount,
                    result.failedCount,
                )
            )
            .setColor(ContextCompat.getColor(context, R.color.md_theme_light_primary))
            // White icon is used for Android 5 compatibility.
            .setSmallIcon(
                if (result.failedCount > 0)
                    R.drawable.ic_upload_white
                else
                    R.drawable.ic_awesome_white
            )
            .setAutoCancel(true)
            .setContentIntent(openQueueIntent)
            .build()

        notify(QUEUE_RESULT_NOTIFICATION_ID, notification)
    }

    fun notifyQueueInterrupted() {
        val notification = NotificationCompat.Builder(
            context,
            CHANNEL_ID
        )
            .setContentTitle(context.getString(R.string.import_queue_notification_interrupted_title))
            .setContentText(context.getString(R.string.import_queue_notification_interrupted_text))
            .setColor(ContextCompat.getColor(context, R.color.md_theme_light_primary))
            // White icon is used for Android 5 compatibility.
            .setSmallIcon(R.drawable.ic_upload_white)
            .setAutoCancel(true)
            .setContentIntent(openQueueIntent)
            .build()

        notify(QUEUE_RESULT_NOTIFICATION_ID, notification)
    }

    private fun notify(id: Int, notification: Notification) {
        ensureChannel()

        @SuppressLint("MissingPermission")
        if (areNotificationsEnabled) {
            notificationsManager.notify(id, notification)
        } else {
            log.debug {
                "notify(): skip_notify_as_disabled:" +
                        "\nid=$id"
            }
        }
    }

    private fun ensureChannel() {
        notificationsManager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
                .setName(context.getString(R.string.import_notification_channel_name))
                .setDescription(context.getString(R.string.import_notification_channel_description))
                .build()
        )
    }

    companion object {
        private const val CHANNEL_ID = "import"
        const val QUEUE_PROGRESS_NOTIFICATION_ID = 1_001_001
        private const val QUEUE_RESULT_NOTIFICATION_ID = 1_001_002
    }
}
