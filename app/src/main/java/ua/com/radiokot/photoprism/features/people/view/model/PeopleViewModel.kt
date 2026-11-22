package ua.com.radiokot.photoprism.features.people.view.model

import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.subjects.PublishSubject
import retrofit2.HttpException
import ua.com.radiokot.photoprism.base.data.storage.SimpleCollectionRepository
import ua.com.radiokot.photoprism.env.data.model.SessionCapabilities
import ua.com.radiokot.photoprism.env.data.storage.SessionCapabilitiesRepository
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.observeOnMain
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.logic.MediaPreviewUrlFactory
import ua.com.radiokot.photoprism.features.people.data.model.Person
import ua.com.radiokot.photoprism.features.people.data.storage.NamedPeopleRepository
import ua.com.radiokot.photoprism.features.people.data.storage.PeopleRepository
import ua.com.radiokot.photoprism.features.people.data.storage.UnknownFacesRepository
import ua.com.radiokot.photoprism.features.people.logic.NameFaceUseCase
import ua.com.radiokot.photoprism.features.people.logic.RenamePersonUseCase
import ua.com.radiokot.photoprism.features.people.logic.SetFaceHiddenUseCase
import ua.com.radiokot.photoprism.features.people.logic.SetPersonFavoriteUseCase
import ua.com.radiokot.photoprism.features.people.logic.SetPersonHiddenUseCase
import ua.com.radiokot.photoprism.features.shared.search.view.model.SearchViewViewModel
import ua.com.radiokot.photoprism.features.shared.search.view.model.SearchViewViewModelImpl

/**
 * People overview: named people and new faces to name.
 * People are shared by the whole library, so are their names.
 */
class PeopleViewModel(
    private val namedPeopleRepository: NamedPeopleRepository,
    private val unknownFacesRepository: UnknownFacesRepository,
    private val peopleRepository: PeopleRepository,
    private val sessionCapabilitiesRepository: SessionCapabilitiesRepository?,
    private val nameFaceUseCase: NameFaceUseCase,
    private val renamePersonUseCase: RenamePersonUseCase,
    private val setPersonFavoriteUseCase: SetPersonFavoriteUseCase,
    private val setPersonHiddenUseCase: SetPersonHiddenUseCase,
    private val setFaceHiddenUseCase: SetFaceHiddenUseCase,
    private val searchPredicate: (person: Person, query: String) -> Boolean,
    private val previewUrlFactory: MediaPreviewUrlFactory,
) : ViewModel(),
    SearchViewViewModel by SearchViewViewModelImpl() {

    private val log = kLogger("PeopleVM")
    private val eventsSubject = PublishSubject.create<Event>()
    val events = eventsSubject.observeOnMain()
    val selectedTab = MutableLiveData(Tab.NAMED)
    val isLoading = MutableLiveData(false)
    val itemsList = MutableLiveData<List<PersonTileListItem>>()
    val mainError = MutableLiveData<Error?>(null)
    val isReadOnly = MutableLiveData(false)
    val isShowingHidden = MutableLiveData(false)
    val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = onBackPressed()
    }
    private var isInitialized = false
    private lateinit var defaultSearchConfig: SearchConfig
    private var capabilities: SessionCapabilities =
        sessionCapabilitiesRepository?.current ?: SessionCapabilities.ASSUMED

    private val currentRepository: SimpleCollectionRepository<Person>
        get() = when (selectedTab.value!!) {
            Tab.NAMED -> namedPeopleRepository
            Tab.NEW -> unknownFacesRepository
        }

    private val canEditPeople: Boolean
        get() = capabilities.canEditPeople

    private val canEditFaces: Boolean
        get() = capabilities.canEditFaces

    fun initOnce(
        defaultSearchConfig: SearchConfig,
    ) {
        if (isInitialized) {
            return
        }

        this.defaultSearchConfig = defaultSearchConfig

        subscribeToCapabilities()
        subscribeToRepository(namedPeopleRepository, Tab.NAMED)
        subscribeToRepository(unknownFacesRepository, Tab.NEW)
        subscribeToSearch()

        // The named list is also needed on the new tab, for name suggestions.
        namedPeopleRepository.updateIfNotFresh()
        unknownFacesRepository.updateIfNotFresh()

        isInitialized = true

        log.debug {
            "initOnce(): initialized"
        }
    }

    private fun subscribeToCapabilities() {
        isReadOnly.value = !canEditPeople

        sessionCapabilitiesRepository?.capabilities
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe { newCapabilities ->
                capabilities = newCapabilities
                isReadOnly.value = !canEditPeople
            }
            ?.autoDispose(this)
    }

    private fun subscribeToRepository(
        repository: SimpleCollectionRepository<Person>,
        tab: Tab,
    ) {
        repository.items
            .filter { !repository.isNeverUpdated }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                if (selectedTab.value == tab) {
                    postItems()
                }
            }
            .autoDispose(this)

        repository.errors
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { error ->
                log.error(error) {
                    "subscribeToRepository(): loading_failed:" +
                            "\ntab=$tab"
                }

                if (selectedTab.value != tab) {
                    return@subscribe
                }

                if (repository.isNeverUpdated) {
                    mainError.value = Error.LoadingFailed
                } else {
                    eventsSubject.onNext(Event.ShowFloatingLoadingFailedError)
                }
            }
            .autoDispose(this)

        repository.loading
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                isLoading.value = currentRepository.isLoading
            }
            .autoDispose(this)
    }

    private fun subscribeToSearch() {
        searchInputObservable
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { postItems() }
            .autoDispose(this)
    }

    private fun postItems() {
        val repository = currentRepository
        if (repository.isNeverUpdated) {
            itemsList.value = emptyList()
            mainError.value = null
            return
        }

        val searchQuery = currentSearchInput
        val isShowingHidden = isShowingHidden.value == true
        val people = repository.itemsList
            .filter { it.isHidden == isShowingHidden }
            .let { people ->
                if (searchQuery != null && selectedTab.value == Tab.NAMED)
                    people.filter { searchPredicate(it, searchQuery) }
                else
                    people
            }

        log.debug {
            "postItems(): posting_items:" +
                    "\ntab=${selectedTab.value}," +
                    "\ncount=${people.size}," +
                    "\nsearchQuery=$searchQuery"
        }

        itemsList.value = people.map { person ->
            PersonTileListItem(
                source = person,
                previewUrlFactory = previewUrlFactory,
            )
        }

        mainError.value =
            if (people.isEmpty())
                when {
                    isShowingHidden -> Error.NothingHidden
                    selectedTab.value == Tab.NAMED -> Error.NoPeople
                    else -> Error.NoNewFaces
                }
            else
                null
    }

    fun onTabSelected(tab: Tab) {
        if (selectedTab.value == tab) {
            return
        }

        log.debug {
            "onTabSelected(): selecting:" +
                    "\ntab=$tab"
        }

        selectedTab.value = tab
        isLoading.value = currentRepository.isLoading
        postItems()
        currentRepository.updateIfNotFresh()
    }

    fun onShowHiddenClicked() {
        isShowingHidden.value = isShowingHidden.value != true
        postItems()
    }

    fun onRetryClicked() {
        currentRepository.update()
    }

    fun onSwipeRefreshPulled() {
        sessionCapabilitiesRepository?.update()
        currentRepository.update()
    }

    fun onItemClicked(item: PersonTileListItem) {
        val person = item.source

        log.debug {
            "onItemClicked(): item_clicked:" +
                    "\nperson=$person"
        }

        if (person.isHidden && canEditPeople) {
            eventsSubject.onNext(Event.ConfirmUnhide(person))
        } else if (person.isFaceCluster && canEditFaces && person.markerUid != null) {
            eventsSubject.onNext(
                Event.ShowNameFaceDialog(
                    face = person,
                    thumbnailUrl = item.thumbnailUrl,
                    nameSuggestions = getNameSuggestions(),
                )
            )
        } else {
            openPhotosOf(person)
        }
    }

    /**
     * @return true if the long click is handled.
     */
    fun onItemLongClicked(item: PersonTileListItem): Boolean {
        val person = item.source

        return when {
            person.isHidden -> {
                onItemClicked(item)
                true
            }

            !person.isFaceCluster && canEditPeople -> {
                eventsSubject.onNext(Event.ShowPersonActions(person))
                true
            }

            person.isFaceCluster && canEditFaces -> {
                onItemClicked(item)
                true
            }

            else ->
                false
        }
    }

    fun onShowPhotosClicked(person: Person) =
        openPhotosOf(person)

    fun onShowFacePhotosClicked(faceId: String) {
        unknownFacesRepository.itemsList
            .find { it.id == faceId }
            ?.also(::openPhotosOf)
    }

    private fun openPhotosOf(person: Person) {
        eventsSubject.onNext(
            Event.OpenPhotos(
                title = person.name,
                repositoryParams = SimpleGalleryMediaRepository.Params(
                    searchConfig = defaultSearchConfig.copy(
                        personIds = setOf(person.id),
                        includePrivate = true,
                    )
                )
            )
        )
    }

    private fun getNameSuggestions(): List<String> =
        namedPeopleRepository.itemsList
            .filterNot(Person::isHidden)
            .mapNotNull(Person::name)

    fun onRenameClicked(person: Person) {
        eventsSubject.onNext(
            Event.ShowRenameDialog(
                person = person,
                nameSuggestions = getNameSuggestions(),
            )
        )
    }

    fun onRenameSubmitted(person: Person, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name == person.name) {
            return
        }

        val existing = namedPeopleRepository.findByName(name, exceptId = person.id)
        if (existing != null) {
            log.debug {
                "onRenameSubmitted(): asking_to_merge:" +
                        "\nperson=$person," +
                        "\nexisting=$existing"
            }

            eventsSubject.onNext(
                Event.ConfirmMerge(
                    person = person,
                    newName = name,
                    existingName = existing.name ?: name,
                )
            )
            return
        }

        rename(person, name, isMerge = false)
    }

    fun onMergeConfirmed(person: Person, newName: String) =
        rename(person, newName, isMerge = true)

    private fun rename(person: Person, newName: String, isMerge: Boolean) {
        renamePersonUseCase(
            personUid = person.id,
            newName = newName,
        )
            .afterChange()
            .subscribeBy(
                onComplete = {
                    eventsSubject.onNext(
                        if (isMerge)
                            Event.ShowMergedInto(newName)
                        else
                            Event.ShowRenamedTo(newName)
                    )
                },
                onError = ::onWriteFailed,
            )
            .autoDispose(this)
    }

    fun onFavoriteClicked(person: Person) {
        setPersonFavoriteUseCase(
            personUid = person.id,
            isFavorite = !person.isFavorite,
        )
            .afterChange()
            .subscribeBy(
                onError = ::onWriteFailed,
            )
            .autoDispose(this)
    }

    fun onHidePersonClicked(person: Person) =
        setPersonHidden(person, isHidden = true)

    fun onUndoHidePersonClicked(person: Person) =
        setPersonHidden(person, isHidden = false)

    private fun setPersonHidden(person: Person, isHidden: Boolean) {
        setPersonHiddenUseCase(
            personUid = person.id,
            isHidden = isHidden,
        )
            .afterChange()
            .subscribeBy(
                onComplete = {
                    if (isHidden) {
                        eventsSubject.onNext(Event.ShowPersonHidden(person))
                    }
                },
                onError = ::onWriteFailed,
            )
            .autoDispose(this)
    }

    fun onUnhideConfirmed(person: Person) {
        val unhide =
            if (person.isFaceCluster)
                setFaceHiddenUseCase(faceId = person.id, isHidden = false)
            else
                setPersonHiddenUseCase(personUid = person.id, isHidden = false)

        unhide
            .afterChange()
            .subscribeBy(
                onComplete = {
                    eventsSubject.onNext(Event.ShowUnhidden(person))
                },
                onError = ::onWriteFailed,
            )
            .autoDispose(this)
    }

    fun onFaceNameSubmitted(markerUid: String, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return
        }

        log.debug {
            "onFaceNameSubmitted(): naming:" +
                    "\nmarkerUid=$markerUid"
        }

        nameFaceUseCase(
            markerUid = markerUid,
            name = trimmedName,
        )
            .flatMap { subjectUid ->
                Completable.complete()
                    .afterChange()
                    .toSingleDefault(subjectUid)
            }
            .subscribeBy(
                onSuccess = { subjectUid ->
                    val namedPerson = namedPeopleRepository.itemsList
                        .find { it.id == subjectUid }
                    eventsSubject.onNext(
                        Event.ShowFaceNamed(
                            name = namedPerson?.name ?: trimmedName,
                            person = namedPerson,
                        )
                    )
                },
                onError = ::onWriteFailed,
            )
            .autoDispose(this)
    }

    fun onHideFaceClicked(faceId: String) =
        setFaceHidden(faceId, isHidden = true)

    fun onUndoHideFaceClicked(faceId: String) =
        setFaceHidden(faceId, isHidden = false)

    private fun setFaceHidden(faceId: String, isHidden: Boolean) {
        setFaceHiddenUseCase(
            faceId = faceId,
            isHidden = isHidden,
        )
            .afterChange()
            .subscribeBy(
                onComplete = {
                    if (isHidden) {
                        eventsSubject.onNext(Event.ShowFaceHidden(faceId))
                    }
                },
                onError = ::onWriteFailed,
            )
            .autoDispose(this)
    }

    /**
     * Reloads both lists after a change, so counts, covers and the search stay fresh,
     * and lets the people selection of the search reload as well.
     */
    private fun Completable.afterChange(): Completable =
        andThen(Completable.defer {
            peopleRepository.invalidate()
            Completable.mergeDelayError(
                listOf(
                    namedPeopleRepository.update(),
                    unknownFacesRepository.update(),
                )
            ).onErrorComplete()
        })
            .observeOn(AndroidSchedulers.mainThread())

    private fun onWriteFailed(error: Throwable) {
        log.error(error) {
            "onWriteFailed(): failed"
        }

        val code = (error as? HttpException)?.code()
        if (code == 401 || code == 403) {
            sessionCapabilitiesRepository?.update()
            eventsSubject.onNext(Event.ShowNoPermission)
        } else {
            eventsSubject.onNext(Event.ShowFailedToSave)
        }
    }

    private fun onBackPressed() {
        when {
            isSearchExpanded.value == true ->
                closeAndClearSearch()

            else ->
                eventsSubject.onNext(Event.Finish)
        }
    }

    enum class Tab {
        NAMED,
        NEW,
        ;
    }

    sealed interface Event {
        object ShowFloatingLoadingFailedError : Event
        object Finish : Event
        object ShowNoPermission : Event
        object ShowFailedToSave : Event

        class OpenPhotos(
            val title: String?,
            val repositoryParams: SimpleGalleryMediaRepository.Params,
        ) : Event

        class ShowPersonActions(
            val person: Person,
        ) : Event

        /**
         * Call [onRenameSubmitted] with the new name.
         */
        class ShowRenameDialog(
            val person: Person,
            val nameSuggestions: List<String>,
        ) : Event

        /**
         * Call [onMergeConfirmed] if confirmed.
         */
        class ConfirmMerge(
            val person: Person,
            val newName: String,
            val existingName: String,
        ) : Event

        /**
         * Call [onFaceNameSubmitted], [onHideFaceClicked] or [onShowFacePhotosClicked].
         */
        class ShowNameFaceDialog(
            val face: Person,
            val thumbnailUrl: String,
            val nameSuggestions: List<String>,
        ) : Event

        class ShowRenamedTo(val name: String) : Event
        class ShowMergedInto(val name: String) : Event

        /**
         * @param person the person the face is now assigned to, if known,
         * to offer [onShowPhotosClicked].
         */
        class ShowFaceNamed(
            val name: String,
            val person: Person?,
        ) : Event

        /**
         * Offer [onUndoHidePersonClicked].
         */
        class ShowPersonHidden(val person: Person) : Event

        /**
         * Offer [onUndoHideFaceClicked].
         */
        class ShowFaceHidden(val faceId: String) : Event

        /**
         * Call [onUnhideConfirmed] if confirmed.
         */
        class ConfirmUnhide(val person: Person) : Event

        class ShowUnhidden(val person: Person) : Event
    }

    sealed interface Error {
        object LoadingFailed : Error
        object NoPeople : Error
        object NoNewFaces : Error
        object NothingHidden : Error
    }
}
