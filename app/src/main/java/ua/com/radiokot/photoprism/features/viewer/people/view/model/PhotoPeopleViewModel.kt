package ua.com.radiokot.photoprism.features.viewer.people.view.model

import android.graphics.RectF
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.subjects.PublishSubject
import retrofit2.HttpException
import ua.com.radiokot.photoprism.env.data.model.SessionCapabilities
import ua.com.radiokot.photoprism.env.data.storage.SessionCapabilitiesRepository
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.observeOnMain
import ua.com.radiokot.photoprism.features.gallery.data.model.GalleryMedia
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.logic.MediaPreviewUrlFactory
import ua.com.radiokot.photoprism.features.people.data.model.Person
import ua.com.radiokot.photoprism.features.people.data.storage.NamedPeopleRepository
import ua.com.radiokot.photoprism.features.people.data.storage.PeopleRepository
import ua.com.radiokot.photoprism.features.viewer.people.data.model.PhotoFace
import ua.com.radiokot.photoprism.features.viewer.people.data.model.PhotoFaces
import ua.com.radiokot.photoprism.features.viewer.people.logic.ChangePhotoFaceUseCase
import ua.com.radiokot.photoprism.features.viewer.people.logic.GetPhotoFacesUseCase

/**
 * "People in the photo" of the media viewer: faces framed in the photo,
 * a compact list of them, naming and correcting them.
 *
 * Names are shared by the whole library.
 */
class PhotoPeopleViewModel(
    private val getPhotoFacesUseCase: GetPhotoFacesUseCase,
    private val changePhotoFaceUseCase: ChangePhotoFaceUseCase,
    private val namedPeopleRepository: NamedPeopleRepository,
    private val peopleRepository: PeopleRepository,
    private val sessionCapabilitiesRepository: SessionCapabilitiesRepository?,
    private val previewUrlFactory: MediaPreviewUrlFactory,
) : ViewModel() {

    private val log = kLogger("PhotoPeopleVM")
    private val eventsSubject = PublishSubject.create<Event>()
    val events = eventsSubject.observeOnMain()

    /**
     * Whether the people are shown, it stays on while swiping through the media.
     */
    val isEnabled = MutableLiveData(false)
    val state = MutableLiveData<State>(State.Off)

    /**
     * Whether a frame for a missed face is being placed.
     */
    val isAddingFace = MutableLiveData(false)

    /**
     * The face to draw attention to, like the one picked from the list.
     */
    val highlightedFaceUid = MutableLiveData<String?>(null)

    private val capabilities: SessionCapabilities
        get() = sessionCapabilitiesRepository?.current ?: SessionCapabilities.ASSUMED

    val canSeePeople: Boolean
        get() = capabilities.canSeePeople

    val canEdit: Boolean
        get() = capabilities.canEditFaces

    private var currentMedia: GalleryMedia? = null
    private val cache = mutableMapOf<String, PhotoFaces>()
    private var loadingDisposable: Disposable? = null

    private val currentFaces: PhotoFaces?
        get() = (state.value as? State.Loaded)?.photoFaces

    fun onToggleClicked() {
        val enable = isEnabled.value != true
        log.debug {
            "onToggleClicked(): toggling:" +
                    "\nenable=$enable"
        }
        isEnabled.value = enable
        isAddingFace.value = false
        highlightedFaceUid.value = null

        if (enable) {
            // For the name suggestions.
            namedPeopleRepository.updateIfNotFresh()
                .onErrorComplete()
                .subscribe()
                .autoDispose(this)
            load(force = false)
        } else {
            loadingDisposable?.dispose()
            state.value = State.Off
        }
    }

    fun onMediaShown(media: GalleryMedia?) {
        if (media?.uid == currentMedia?.uid) {
            return
        }

        log.debug {
            "onMediaShown(): media_changed:" +
                    "\nmedia=$media"
        }

        currentMedia = media
        isAddingFace.value = false
        highlightedFaceUid.value = null

        if (isEnabled.value == true) {
            load(force = false)
        }
    }

    private fun load(force: Boolean) {
        val media = currentMedia
        loadingDisposable?.dispose()

        if (media == null) {
            state.value = State.NotAvailable
            return
        }

        val cached = cache[media.uid]
        if (cached != null && !force) {
            state.value = State.Loaded(cached)
            return
        }

        // Keep showing the stale faces while reloading the same photo.
        if (currentFaces?.photoUid != media.uid) {
            state.value = State.Loading
        }

        loadingDisposable = getPhotoFacesUseCase(
            photoUid = media.uid,
            previewHash = media.hash,
        )
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onSuccess = { photoFaces ->
                    cache[photoFaces.photoUid] = photoFaces
                    if (currentMedia?.uid == photoFaces.photoUid && isEnabled.value == true) {
                        log.debug {
                            "load(): loaded:" +
                                    "\nfaces=${photoFaces.faces.size}"
                        }
                        state.value = State.Loaded(photoFaces)
                    }
                },
                onError = { error ->
                    log.error(error) { "load(): failed" }
                    if (currentMedia?.uid == media.uid) {
                        state.value = State.Failed
                    }
                }
            )
            .autoDispose(this)
    }

    fun onRetryClicked() =
        load(force = true)

    fun onFaceClicked(face: PhotoFace) {
        highlightedFaceUid.value = face.markerUid

        val actions = buildList {
            if (canEdit) {
                if (face.hasName) {
                    if (face.isToReview) {
                        add(FaceAction.CONFIRM)
                    }
                    if (face.personUid != null) {
                        add(FaceAction.SHOW_PHOTOS)
                    }
                    add(FaceAction.CHANGE_NAME)
                    add(FaceAction.CLEAR_NAME)
                } else {
                    add(FaceAction.NAME)
                }
                add(FaceAction.NOT_A_FACE)
            } else if (face.personUid != null) {
                add(FaceAction.SHOW_PHOTOS)
            }
        }

        if (!canEdit && actions == listOf(FaceAction.SHOW_PHOTOS)) {
            // Nothing to choose from.
            onFaceActionClicked(face, FaceAction.SHOW_PHOTOS)
            return
        }

        eventsSubject.onNext(
            Event.OpenFaceActions(
                face = face,
                thumbnailUrl = getThumbnailUrl(face),
                actions = actions,
                isReadOnly = !canEdit,
            )
        )
    }

    fun onFaceActionsDismissed() {
        highlightedFaceUid.value = null
    }

    fun onFaceActionClicked(face: PhotoFace, action: FaceAction) {
        log.debug {
            "onFaceActionClicked(): action_clicked:" +
                    "\nface=$face," +
                    "\naction=$action"
        }

        when (action) {
            FaceAction.NAME,
            FaceAction.CHANGE_NAME ->
                eventsSubject.onNext(
                    Event.OpenNameDialog(
                        markerUid = face.markerUid,
                        currentName = face.name,
                        thumbnailUrl = getThumbnailUrl(face),
                        nameSuggestions = getNameSuggestions(),
                    )
                )

            FaceAction.CONFIRM ->
                change(changePhotoFaceUseCase.confirm(face.markerUid)) {
                    Event.ShowChanged(Message.CONFIRMED, face.name)
                }

            FaceAction.CLEAR_NAME -> {
                val previousName = face.name
                change(changePhotoFaceUseCase.clearName(face.markerUid)) {
                    Event.ShowChanged(
                        message = Message.NAME_CLEARED,
                        name = previousName,
                        undo = previousName?.let {
                            { onNameSubmitted(face.markerUid, previousName) }
                        },
                    )
                }
            }

            FaceAction.NOT_A_FACE ->
                change(changePhotoFaceUseCase.setNotAFace(face.markerUid, isNotAFace = true)) {
                    Event.ShowChanged(
                        message = Message.NOT_A_FACE,
                        name = face.name,
                        undo = {
                            change(
                                changePhotoFaceUseCase.setNotAFace(
                                    face.markerUid,
                                    isNotAFace = false
                                )
                            ) {
                                Event.ShowChanged(Message.FACE_RESTORED, face.name)
                            }
                        },
                    )
                }

            FaceAction.SHOW_PHOTOS -> {
                highlightedFaceUid.value = null
                val personUid = face.personUid ?: return
                eventsSubject.onNext(
                    Event.OpenPersonPhotos(
                        title = face.name,
                        repositoryParams = SimpleGalleryMediaRepository.Params(
                            searchConfig = SearchConfig.DEFAULT.copy(
                                personIds = setOf(personUid),
                            ),
                        ),
                    )
                )
            }
        }
    }

    fun onNameSubmitted(markerUid: String, name: String) {
        val trimmedName = name.trim()
        val face = currentFaces?.faces?.find { it.markerUid == markerUid }
        if (trimmedName.isEmpty() || face != null && face.name == trimmedName) {
            highlightedFaceUid.value = null
            return
        }

        change(changePhotoFaceUseCase.setName(markerUid, trimmedName)) {
            Event.ShowChanged(Message.NAMED, trimmedName)
        }
    }

    fun onAddFaceClicked() {
        if (!canEdit || currentFaces?.fileUid == null) {
            return
        }
        highlightedFaceUid.value = null
        isAddingFace.value = true
    }

    fun onAddFaceCancelled() {
        isAddingFace.value = false
    }

    /**
     * The area of the missed face waiting for a name.
     */
    private var pendingNewFaceArea: RectF? = null

    /**
     * @param area relative to the oriented image
     */
    fun onNewFaceAreaPlaced(area: RectF) {
        pendingNewFaceArea = RectF(area)
        eventsSubject.onNext(
            Event.OpenNewFaceNameDialog(
                nameSuggestions = getNameSuggestions(),
            )
        )
    }

    fun onNewFaceNameSubmitted(name: String) {
        val fileUid = currentFaces?.fileUid ?: return
        val area = pendingNewFaceArea ?: return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return
        }

        pendingNewFaceArea = null
        isAddingFace.value = false
        change(changePhotoFaceUseCase.add(fileUid, area, trimmedName)) {
            Event.ShowChanged(Message.ADDED, trimmedName)
        }
    }

    /**
     * Applies a change, then reloads the faces of the photo
     * and lets the people lists reload.
     */
    private fun change(
        change: Completable,
        doneEvent: () -> Event,
    ) {
        val photoUid = currentMedia?.uid
        isBusy.value = true

        change
            .doOnComplete {
                peopleRepository.invalidate()
                namedPeopleRepository.invalidate()
            }
            .andThen(
                // Fresh suggestions, the new name included.
                namedPeopleRepository.updateDeferred().onErrorComplete()
            )
            .observeOn(AndroidSchedulers.mainThread())
            .doFinally {
                isBusy.value = false
                highlightedFaceUid.value = null
            }
            .subscribeBy(
                onComplete = {
                    if (photoUid != null) {
                        cache.remove(photoUid)
                    }
                    if (photoUid == currentMedia?.uid && isEnabled.value == true) {
                        load(force = true)
                    }
                    eventsSubject.onNext(doneEvent())
                },
                onError = ::onChangeFailed,
            )
            .autoDispose(this)
    }

    /**
     * Whether a change is being saved.
     */
    val isBusy = MutableLiveData(false)

    private fun onChangeFailed(error: Throwable) {
        log.error(error) { "onChangeFailed(): failed" }

        val code = (error as? HttpException)?.code()
        if (code == 401 || code == 403) {
            sessionCapabilitiesRepository?.update()
            eventsSubject.onNext(Event.ShowFailed(isNoPermission = true))
        } else {
            eventsSubject.onNext(Event.ShowFailed(isNoPermission = false))
        }
    }

    private fun getThumbnailUrl(face: PhotoFace): String? =
        face.thumbnailHash?.let { previewUrlFactory.getThumbnailUrl(it, THUMBNAIL_SIZE_PX) }

    fun getChipThumbnailUrl(face: PhotoFace): String? =
        getThumbnailUrl(face)

    private fun getNameSuggestions(): List<String> =
        namedPeopleRepository.itemsList
            .filterNot(Person::isHidden)
            .mapNotNull(Person::name)

    enum class FaceAction {
        CONFIRM,
        NAME,
        CHANGE_NAME,
        CLEAR_NAME,
        NOT_A_FACE,
        SHOW_PHOTOS,
    }

    enum class Message {
        NAMED,
        CONFIRMED,
        NAME_CLEARED,
        NOT_A_FACE,
        FACE_RESTORED,
        ADDED,
    }

    sealed interface State {
        object Off : State
        object Loading : State
        object Failed : State

        /**
         * The current media is not a photo of the library.
         */
        object NotAvailable : State
        class Loaded(val photoFaces: PhotoFaces) : State
    }

    sealed interface Event {
        class OpenFaceActions(
            val face: PhotoFace,
            val thumbnailUrl: String?,
            val actions: List<FaceAction>,
            val isReadOnly: Boolean,
        ) : Event

        class OpenNameDialog(
            val markerUid: String,
            val currentName: String?,
            val thumbnailUrl: String?,
            val nameSuggestions: List<String>,
        ) : Event

        class OpenNewFaceNameDialog(
            val nameSuggestions: List<String>,
        ) : Event

        class OpenPersonPhotos(
            val title: String?,
            val repositoryParams: SimpleGalleryMediaRepository.Params,
        ) : Event

        class ShowChanged(
            val message: Message,
            val name: String?,
            val undo: (() -> Unit)? = null,
        ) : Event

        class ShowFailed(
            val isNoPermission: Boolean,
        ) : Event
    }

    private companion object {
        private const val THUMBNAIL_SIZE_PX = 160
    }
}
