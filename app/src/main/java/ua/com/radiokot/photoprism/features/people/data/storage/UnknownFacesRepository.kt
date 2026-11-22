package ua.com.radiokot.photoprism.features.people.data.storage

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.faces.service.PhotoPrismFacesService
import ua.com.radiokot.photoprism.base.data.model.DataPage
import ua.com.radiokot.photoprism.base.data.storage.SimpleCollectionRepository
import ua.com.radiokot.photoprism.extension.toSingle
import ua.com.radiokot.photoprism.features.people.data.model.Person
import ua.com.radiokot.photoprism.util.PagedCollectionLoader

/**
 * Face clusters nobody is assigned to yet, the biggest first,
 * like People > New on the web. Hidden ones are included, to be shown on request.
 */
class UnknownFacesRepository(
    private val photoPrismFacesService: PhotoPrismFacesService,
) : SimpleCollectionRepository<Person>() {

    override fun getCollection(): Single<List<Person>> {
        val loader = PagedCollectionLoader(
            pageProvider = { cursor ->
                {
                    val offset = cursor?.toInt() ?: 0

                    val items = photoPrismFacesService.getFaces(
                        count = PAGE_LIMIT,
                        offset = offset,
                        markers = true,
                        unknown = true,
                        order = "samples",
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
            .map { unknownFaces ->
                unknownFaces
                    // A face without a marker thumb can't be shown nor named.
                    .filter { it.thumb.isNotEmpty() }
                    .map(::Person)
            }
            .subscribeOn(Schedulers.io())
    }

    private companion object {
        private const val PAGE_LIMIT = 60
    }
}
