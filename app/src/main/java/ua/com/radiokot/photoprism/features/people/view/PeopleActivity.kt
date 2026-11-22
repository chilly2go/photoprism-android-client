package ua.com.radiokot.photoprism.features.people.view

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
import android.view.Menu
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import org.koin.androidx.viewmodel.ext.android.viewModel
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.base.view.BaseActivity
import ua.com.radiokot.photoprism.databinding.ActivityPeopleBinding
import ua.com.radiokot.photoprism.databinding.DialogNameFaceBinding
import ua.com.radiokot.photoprism.extension.barsAndCutout
import ua.com.radiokot.photoprism.extension.barsAndCutoutPadding
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.proxyOkResult
import ua.com.radiokot.photoprism.extension.subscribe
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository
import ua.com.radiokot.photoprism.features.gallery.search.extension.bindToViewModel
import ua.com.radiokot.photoprism.features.gallery.search.extension.fixCloseButtonColor
import ua.com.radiokot.photoprism.features.gallery.search.extension.hideUnderline
import ua.com.radiokot.photoprism.features.gallery.view.GallerySingleRepositoryActivity
import ua.com.radiokot.photoprism.features.people.data.model.Person
import ua.com.radiokot.photoprism.features.people.view.model.PeopleViewModel
import ua.com.radiokot.photoprism.features.people.view.model.PersonTileListItem
import ua.com.radiokot.photoprism.view.ErrorView

/**
 * People of the library: named ones and new faces to name.
 */
class PeopleActivity : BaseActivity() {

    private val log = kLogger("PeopleActivity")
    private lateinit var view: ActivityPeopleBinding
    private val viewModel: PeopleViewModel by viewModel()
    private val photosLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        this::proxyOkResult,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        view = ActivityPeopleBinding.inflate(layoutInflater)
        setContentView(view.root)

        @Suppress("DEPRECATION")
        viewModel.initOnce(
            defaultSearchConfig = intent.getParcelableExtra(DEFAULT_SEARCH_CONFIG_EXTRA)
                ?: SearchConfig.DEFAULT,
        )

        initToolbar()
        initTabs()
        view.peopleRecyclerView.doOnPreDraw {
            initList()
        }
        initErrorView()
        initSwipeRefresh()

        viewModel.isReadOnly.observe(this) { isReadOnly ->
            view.readOnlyTextView.isVisible = isReadOnly
        }

        supportFragmentManager.setFragmentResultListener(
            NameFaceDialogFragment.REQUEST_KEY,
            this,
            ::onNameFaceResult,
        )

        subscribeToEvents()

        onBackPressedDispatcher.addCallback(viewModel.backPressedCallback)
    }

    private fun initToolbar() {
        setSupportActionBar(view.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        view.toolbar.barsAndCutoutPadding()
    }

    private fun initTabs() = with(view.tabLayout) {
        val tabs = PeopleViewModel.Tab.entries
        getTabAt(tabs.indexOf(viewModel.selectedTab.value!!))?.select()

        addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                viewModel.onTabSelected(tabs[tab.position])
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun initList() {
        val peopleAdapter = ItemAdapter<PersonTileListItem>()

        viewModel.itemsList.observe(this, peopleAdapter::setNewList)

        with(view.peopleRecyclerView) {
            ViewCompat.getRootWindowInsets(this)?.barsAndCutout()?.also { safe ->
                updatePadding(
                    left = paddingLeft + safe.left,
                    right = paddingRight + safe.right,
                    bottom = paddingBottom + safe.bottom,
                )
            }

            val listWidth = measuredWidth
                .takeIf { it > 0 }
                ?: FALLBACK_LIST_SIZE
            val minItemWidthPx =
                resources.getDimensionPixelSize(R.dimen.people_overview_tile_min_width)
            val spanCount = (listWidth / minItemWidthPx).coerceAtLeast(2)

            adapter = FastAdapter.with(peopleAdapter).apply {
                stateRestorationPolicy =
                    RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

                onClickListener = { _, _, item: PersonTileListItem, _ ->
                    viewModel.onItemClicked(item)
                    true
                }

                onLongClickListener = { _, _, item: PersonTileListItem, _ ->
                    viewModel.onItemLongClicked(item)
                }
            }

            layoutManager = object : GridLayoutManager(context, spanCount) {
                val rowSpacing: Int =
                    resources.getDimensionPixelSize(R.dimen.list_item_collection_margin_end)

                override fun generateLayoutParams(
                    c: Context,
                    attrs: AttributeSet
                ): RecyclerView.LayoutParams {
                    return super.generateLayoutParams(c, attrs).apply {
                        width = RecyclerView.LayoutParams.MATCH_PARENT
                        bottomMargin = rowSpacing
                    }
                }
            }
        }
    }

    private fun initErrorView() {
        view.errorView.replaces(view.peopleRecyclerView)
        viewModel.mainError.observe(this) { mainError ->
            when (mainError) {
                PeopleViewModel.Error.LoadingFailed ->
                    view.errorView.showError(
                        ErrorView.Error.General(
                            context = view.errorView.context,
                            messageRes = R.string.failed_to_load_people,
                            retryButtonTextRes = R.string.try_again,
                            retryButtonClickListener = viewModel::onRetryClicked
                        )
                    )

                PeopleViewModel.Error.NoPeople ->
                    view.errorView.showError(
                        ErrorView.Error.EmptyView(
                            context = view.errorView.context,
                            messageRes = R.string.no_people_found,
                        )
                    )

                PeopleViewModel.Error.NoNewFaces ->
                    view.errorView.showError(
                        ErrorView.Error.EmptyView(
                            context = view.errorView.context,
                            messageRes = R.string.people_overview_no_new_faces,
                        )
                    )

                null ->
                    view.errorView.hide()
            }
        }
    }

    private fun initSwipeRefresh() = with(view.swipeRefreshLayout) {
        setOnRefreshListener(viewModel::onSwipeRefreshPulled)
        viewModel.isLoading.observe(this@PeopleActivity, ::setRefreshing)
    }

    private fun subscribeToEvents() = viewModel.events.subscribe(this) { event ->
        log.debug {
            "subscribeToEvents(): received_new_event:" +
                    "\nevent=$event"
        }

        when (event) {
            PeopleViewModel.Event.ShowFloatingLoadingFailedError ->
                showSnackbar(R.string.failed_to_load_people)
                    .setAction(R.string.try_again) { viewModel.onRetryClicked() }
                    .show()

            PeopleViewModel.Event.Finish ->
                finish()

            PeopleViewModel.Event.ShowNoPermission ->
                showSnackbar(R.string.people_overview_no_permission).show()

            PeopleViewModel.Event.ShowFailedToSave ->
                showSnackbar(R.string.people_overview_failed_to_save).show()

            is PeopleViewModel.Event.OpenPhotos ->
                openPhotos(event.title, event.repositoryParams)

            is PeopleViewModel.Event.ShowPersonActions ->
                showPersonActions(event.person)

            is PeopleViewModel.Event.ShowRenameDialog ->
                showRenameDialog(event.person, event.nameSuggestions)

            is PeopleViewModel.Event.ConfirmMerge ->
                confirmMerge(event)

            is PeopleViewModel.Event.ShowNameFaceDialog ->
                NameFaceDialogFragment.newInstance(
                    faceId = event.face.id,
                    markerUid = event.face.markerUid!!,
                    thumbnailUrl = event.thumbnailUrl,
                    nameSuggestions = event.nameSuggestions,
                ).show(supportFragmentManager, NameFaceDialogFragment.TAG)

            is PeopleViewModel.Event.ShowRenamedTo ->
                showSnackbar(getString(R.string.people_overview_renamed_to, event.name)).show()

            is PeopleViewModel.Event.ShowMergedInto ->
                showSnackbar(getString(R.string.people_overview_merged_into, event.name)).show()

            is PeopleViewModel.Event.ShowFaceNamed ->
                showSnackbar(getString(R.string.people_overview_named_as, event.name))
                    .apply {
                        val person = event.person
                        if (person != null) {
                            setAction(R.string.people_overview_show_photos) {
                                viewModel.onShowPhotosClicked(person)
                            }
                        }
                    }
                    .show()

            is PeopleViewModel.Event.ShowPersonHidden ->
                showSnackbar(
                    getString(
                        R.string.people_overview_hidden,
                        event.person.name ?: ""
                    )
                )
                    .setAction(R.string.people_overview_undo) {
                        viewModel.onUndoHidePersonClicked(event.person)
                    }
                    .show()

            is PeopleViewModel.Event.ShowFaceHidden ->
                showSnackbar(R.string.people_overview_face_hidden)
                    .setAction(R.string.people_overview_undo) {
                        viewModel.onUndoHideFaceClicked(event.faceId)
                    }
                    .show()
        }
    }

    private fun showSnackbar(messageRes: Int) =
        showSnackbar(getString(messageRes))

    private fun showSnackbar(message: String) =
        Snackbar.make(view.swipeRefreshLayout, message, Snackbar.LENGTH_LONG)

    private fun showPersonActions(person: Person) {
        val actions = listOf(
            getString(R.string.people_overview_show_photos) to {
                viewModel.onShowPhotosClicked(person)
            },
            getString(R.string.people_overview_rename) to {
                viewModel.onRenameClicked(person)
            },
            getString(
                if (person.isFavorite)
                    R.string.people_overview_remove_from_favorites
                else
                    R.string.people_overview_add_to_favorites
            ) to {
                viewModel.onFavoriteClicked(person)
            },
            getString(R.string.people_overview_hide) to {
                viewModel.onHidePersonClicked(person)
            },
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(person.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second.invoke()
            }
            .show()
    }

    private fun showRenameDialog(
        person: Person,
        nameSuggestions: List<String>,
    ) {
        val dialogView = DialogNameFaceBinding.inflate(layoutInflater)
        dialogView.imageView.isVisible = false
        dialogView.showPhotosButton.isVisible = false
        with(dialogView.nameEditText) {
            setText(person.name)
            setSelection(text?.length ?: 0)
            setAdapter(
                ArrayAdapter(
                    context,
                    android.R.layout.simple_dropdown_item_1line,
                    nameSuggestions.filter { it != person.name },
                )
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.people_overview_rename)
            .setView(dialogView.root)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.onRenameSubmitted(
                    person = person,
                    newName = dialogView.nameEditText.text?.toString() ?: "",
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmMerge(event: PeopleViewModel.Event.ConfirmMerge) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.people_overview_merge_title, event.existingName))
            .setMessage(
                getString(
                    R.string.people_overview_merge_message,
                    event.existingName,
                    event.person.name ?: "",
                )
            )
            .setPositiveButton(R.string.people_overview_merge) { _, _ ->
                viewModel.onMergeConfirmed(event.person, event.existingName)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onNameFaceResult(requestKey: String, result: Bundle) {
        val faceId = result.getString(NameFaceDialogFragment.RESULT_FACE_ID)
            ?: return
        val markerUid = result.getString(NameFaceDialogFragment.RESULT_MARKER_UID)
            ?: return

        when (result.getString(NameFaceDialogFragment.RESULT_ACTION)) {
            NameFaceDialogFragment.ACTION_NAME ->
                viewModel.onFaceNameSubmitted(
                    markerUid = markerUid,
                    name = result.getString(NameFaceDialogFragment.RESULT_NAME) ?: "",
                )

            NameFaceDialogFragment.ACTION_HIDE ->
                viewModel.onHideFaceClicked(faceId)

            NameFaceDialogFragment.ACTION_SHOW_PHOTOS ->
                viewModel.onShowFacePhotosClicked(faceId)
        }
    }

    private fun openPhotos(
        title: String?,
        repositoryParams: SimpleGalleryMediaRepository.Params,
    ) = photosLauncher.launch(
        Intent(this, GallerySingleRepositoryActivity::class.java)
            .setAction(intent.action)
            .putExtras(intent.extras ?: Bundle())
            .putExtras(
                GallerySingleRepositoryActivity.getBundle(
                    title = title ?: getString(R.string.people),
                    repositoryParams = repositoryParams,
                )
            )
    )

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.people_overview, menu)

        with(menu?.findItem(R.id.search_view)?.actionView as SearchView) {
            queryHint = getString(R.string.enter_the_query)
            fixCloseButtonColor()
            hideUnderline()
            bindToViewModel(viewModel, this@PeopleActivity)
        }

        return super.onCreateOptionsMenu(menu)
    }

    companion object {
        private const val FALLBACK_LIST_SIZE = 100
        private const val DEFAULT_SEARCH_CONFIG_EXTRA = "default_search_config"

        fun getBundle(
            defaultSearchConfig: SearchConfig,
        ) = Bundle().apply {
            putParcelable(DEFAULT_SEARCH_CONFIG_EXTRA, defaultSearchConfig)
        }
    }
}
