package ua.com.radiokot.photoprism.features.people

import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import ua.com.radiokot.photoprism.env.data.model.EnvSession
import ua.com.radiokot.photoprism.features.gallery.search.logic.SearchPredicates
import ua.com.radiokot.photoprism.features.people.data.model.Person
import ua.com.radiokot.photoprism.features.people.data.storage.PeopleRepository
import ua.com.radiokot.photoprism.features.people.data.storage.NamedPeopleRepository
import ua.com.radiokot.photoprism.features.people.data.storage.UnknownFacesRepository
import ua.com.radiokot.photoprism.features.people.logic.NameFaceUseCase
import ua.com.radiokot.photoprism.features.people.logic.RenamePersonUseCase
import ua.com.radiokot.photoprism.features.people.logic.SetFaceHiddenUseCase
import ua.com.radiokot.photoprism.features.people.logic.SetPersonFavoriteUseCase
import ua.com.radiokot.photoprism.features.people.logic.SetPersonHiddenUseCase
import ua.com.radiokot.photoprism.features.people.view.model.PeopleSelectionViewModel
import ua.com.radiokot.photoprism.features.people.view.model.PeopleViewModel
import org.koin.core.module.dsl.factoryOf
import ua.com.radiokot.photoprism.features.viewer.people.logic.ChangePhotoFaceUseCase
import ua.com.radiokot.photoprism.features.viewer.people.logic.GetPhotoFacesUseCase
import ua.com.radiokot.photoprism.features.viewer.people.view.model.PhotoPeopleViewModel

val peopleFeatureModule = module {

    scope<EnvSession> {
        scopedOf(::PeopleRepository)

        viewModel {
            PeopleSelectionViewModel(
                peopleRepository = get(),
                searchPredicate = { person: Person, query: String ->
                    SearchPredicates.generalCondition(query, person.name)
                },
                previewUrlFactory = get(),
            )
        }

        scopedOf(::NamedPeopleRepository)
        scopedOf(::UnknownFacesRepository)
        factoryOf(::NameFaceUseCase)
        factoryOf(::RenamePersonUseCase)
        factoryOf(::SetPersonFavoriteUseCase)
        factoryOf(::SetPersonHiddenUseCase)
        factoryOf(::SetFaceHiddenUseCase)

        viewModel {
            PeopleViewModel(
                namedPeopleRepository = get(),
                unknownFacesRepository = get(),
                peopleRepository = get(),
                sessionCapabilitiesRepository = getOrNull(),
                nameFaceUseCase = get(),
                renamePersonUseCase = get(),
                setPersonFavoriteUseCase = get(),
                setPersonHiddenUseCase = get(),
                setFaceHiddenUseCase = get(),
                searchPredicate = { person: Person, query: String ->
                    SearchPredicates.generalCondition(query, person.name)
                },
                previewUrlFactory = get(),
            )
        }

        // People in the photo, for the media viewer.
        factoryOf(::GetPhotoFacesUseCase)
        factoryOf(::ChangePhotoFaceUseCase)
        viewModel {
            PhotoPeopleViewModel(
                getPhotoFacesUseCase = get(),
                changePhotoFaceUseCase = get(),
                namedPeopleRepository = get(),
                peopleRepository = get(),
                sessionCapabilitiesRepository = getOrNull(),
                previewUrlFactory = get(),
            )
        }
    }
}
