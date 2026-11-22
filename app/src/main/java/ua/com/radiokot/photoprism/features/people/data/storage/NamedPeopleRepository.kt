package ua.com.radiokot.photoprism.features.people.data.storage

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.subjects.service.PhotoPrismSubjectsService
import ua.com.radiokot.photoprism.base.data.model.DataPage
import ua.com.radiokot.photoprism.base.data.storage.SimpleCollectionRepository
import ua.com.radiokot.photoprism.extension.toSingle
import ua.com.radiokot.photoprism.features.people.data.model.Person
import ua.com.radiokot.photoprism.util.PagedCollectionLoader

/**
 * All the named people of the library, hidden ones included,
 * as renaming to the name of a hidden person merges with them as well.
 *
 * First go favorites, then the ones with more photos.
 */
class NamedPeopleRepository(
    private val photoPrismSubjectsService: PhotoPrismSubjectsService,
) : SimpleCollectionRepository<Person>() {
    private val comparator =
        compareByDescending(Person::isFavorite)
            .thenByDescending(Person::photoCount)
            .thenBy(Person::name)

    override fun getCollection(): Single<List<Person>> {
        val loader = PagedCollectionLoader(
            pageProvider = { cursor ->
                {
                    val offset = cursor?.toInt() ?: 0

                    val items = photoPrismSubjectsService.getSubjects(
                        count = PAGE_LIMIT,
                        offset = offset,
                        type = "person",
                        hidden = "yes",
                    )

                    DataPage(
                        items = items,
                        nextCursor = (PAGE_LIMIT + offset).toString(),
                        isLast = items.size < PAGE_LIMIT,
                    )
                }.toSingle()
            }
        )

        return loader
            .loadAll()
            .map { personSubjects ->
                personSubjects
                    .filter { it.type == "person" }
                    .map(::Person)
                    .filter(Person::hasName)
                    .sortedWith(comparator)
            }
            .subscribeOn(Schedulers.io())
    }

    /**
     * @return a named person, hidden ones included, other than [exceptId]
     * having the same name as the server would match it, or null.
     */
    fun findByName(name: String, exceptId: String? = null): Person? {
        val normalized = name.trim().lowercase()
        return itemsList.find { person ->
            person.id != exceptId && person.name?.trim()?.lowercase() == normalized
        }
    }

    private companion object {
        private const val PAGE_LIMIT = 100
    }
}
