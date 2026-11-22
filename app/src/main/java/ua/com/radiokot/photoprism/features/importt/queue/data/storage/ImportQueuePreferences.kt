package ua.com.radiokot.photoprism.features.importt.queue.data.storage

import android.content.SharedPreferences
import androidx.core.content.edit
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject

/**
 * Settings of the upload queue.
 */
class ImportQueuePreferences(
    private val preferences: SharedPreferences,
) {
    private val allowMobileDataSubject =
        BehaviorSubject.createDefault(preferences.getBoolean(KEY_ALLOW_MOBILE_DATA, false))

    /**
     * Whether files may be uploaded over mobile data, not only over Wi-Fi.
     * Off by default, as photos and videos take a lot of data.
     */
    var allowMobileData: Boolean
        get() = allowMobileDataSubject.value!!
        set(value) {
            preferences.edit { putBoolean(KEY_ALLOW_MOBILE_DATA, value) }
            allowMobileDataSubject.onNext(value)
        }

    val allowMobileDataChanges: Observable<Boolean> =
        allowMobileDataSubject.distinctUntilChanged()

    private companion object {
        private const val KEY_ALLOW_MOBILE_DATA = "import_queue_allow_mobile_data"
    }
}
