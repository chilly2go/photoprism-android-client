package ua.com.radiokot.photoprism.env.data.storage

import android.content.SharedPreferences
import androidx.core.content.edit
import com.fasterxml.jackson.databind.ObjectMapper
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import ua.com.radiokot.photoprism.api.config.service.PhotoPrismClientConfigService
import ua.com.radiokot.photoprism.env.data.model.SessionCapabilities
import ua.com.radiokot.photoprism.extension.kLogger
import io.reactivex.rxjava3.core.Single

/**
 * Knows what the current account may do in the library.
 * The last known capabilities are remembered,
 * so they are there right away and when the library can't be reached.
 */
class SessionCapabilitiesRepository(
    private val configService: PhotoPrismClientConfigService,
    private val preferences: SharedPreferences,
    private val jsonObjectMapper: ObjectMapper,
    libraryRootUrl: String,
) {
    private val log = kLogger("SessionCapabilitiesRepo")
    private val preferenceKey = "session_capabilities_$libraryRootUrl"
    private val subject = BehaviorSubject.createDefault(readRemembered())
    private var updateDisposable: Disposable? = null

    val capabilities: Observable<SessionCapabilities> = subject.distinctUntilChanged()

    /**
     * The latest known capabilities, assumed ones if the library hasn't been asked yet.
     */
    val current: SessionCapabilities
        get() = subject.value!!

    /**
     * Asks the library in the background, unless already asking.
     */
    @Synchronized
    fun update() {
        if (updateDisposable?.isDisposed == false) {
            return
        }

        updateDisposable = Single
            .fromCallable { SessionCapabilities.fromConfig(configService.getClientConfig()) }
            .subscribeOn(Schedulers.io())
            .subscribeBy(
                onSuccess = { capabilities ->
                    log.debug {
                        "update(): updated:" +
                                "\ncapabilities=$capabilities"
                    }
                    remember(capabilities)
                    subject.onNext(capabilities)
                },
                onError = { error ->
                    log.warn(error) { "update(): failed" }
                },
            )
    }

    /**
     * Forgets the remembered capabilities, for example, when disconnecting,
     * so the next account doesn't get them.
     */
    @Synchronized
    fun clear() {
        updateDisposable?.dispose()
        preferences.edit {
            remove(preferenceKey)
        }
        subject.onNext(SessionCapabilities.ASSUMED)
    }

    private fun readRemembered(): SessionCapabilities =
        try {
            preferences.getString(preferenceKey, null)
                ?.let { jsonObjectMapper.readValue(it, SessionCapabilities::class.java) }
        } catch (error: Exception) {
            log.warn(error) { "readRemembered(): failed" }
            null
        } ?: SessionCapabilities.ASSUMED

    private fun remember(capabilities: SessionCapabilities) =
        preferences.edit {
            putString(preferenceKey, jsonObjectMapper.writeValueAsString(capabilities))
        }
}
