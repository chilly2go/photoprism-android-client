package ua.com.radiokot.photoprism.features.gallery.view

import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener
import com.google.android.material.navigation.NavigationView
import com.google.android.material.navigationrail.NavigationRailView
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.extension.barsAndCutout
import ua.com.radiokot.photoprism.features.gallery.search.view.GallerySearchBarView
import ua.com.radiokot.photoprism.features.gallery.view.model.GalleryViewModel
import android.content.Context
import android.content.Intent
import ua.com.radiokot.photoprism.features.importt.queue.view.ImportQueueActivity
import ua.com.radiokot.photoprism.features.gallery.data.storage.SimpleGalleryMediaRepository

class GalleryNavigationView(
    private val viewModel: GalleryViewModel,
) {
    private var closeDrawer: (() -> Unit)? = null
    private val menus = mutableListOf<Menu>()
    private var isPeopleVisible = false
    val backPressedCallback: OnBackPressedCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeDrawer?.invoke()
            }
        }

    fun initWithDrawer(
        drawerLayout: DrawerLayout,
        navigationView: NavigationView,
        searchBarView: GallerySearchBarView,
    ) {
        initMenu(
            navigationMenu = navigationView.menu,
            context = navigationView.context,
            onItemClicked = drawerLayout::close,
        )

        searchBarView.addNavigationMenuIcon {
            drawerLayout.openDrawer(navigationView, true)
        }

        // Close the drawer on back press
        // if a significant part of it is visible.
        closeDrawer = drawerLayout::closeDrawers
        drawerLayout.addDrawerListener(object : SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                backPressedCallback.isEnabled = slideOffset >= 0.4f
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(navigationView) { _, insets ->
            val safeContent = insets.barsAndCutout()
            navigationView.updatePadding(
                top = safeContent.top,
                left = safeContent.left,
                bottom = safeContent.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    fun initWithRail(
        navigationRail: NavigationRailView,
    ) {
        initMenu(
            navigationMenu = navigationRail.menu,
            context = navigationRail.context,
        )
    }

    private fun initMenu(
        navigationMenu: Menu,
        context: Context,
        onItemClicked: () -> Unit = {},
    ) {
        menus += navigationMenu

        fun getClickListener(extraAction: () -> Any) = MenuItem.OnMenuItemClickListener {
            extraAction()
            onItemClicked()
            true
        }

        navigationMenu.findItem(R.id.albums)
            .setOnMenuItemClickListener(getClickListener(viewModel::onAlbumsClicked))

        navigationMenu.findItem(R.id.favorites)
            .setOnMenuItemClickListener(getClickListener(viewModel::onFavoritesClicked))

        with(navigationMenu.findItem(R.id.places)) {
            isVisible = viewModel.canSeePlaces
            setOnMenuItemClickListener(getClickListener(viewModel::onPlacesClicked))
        }

        navigationMenu.findItem(R.id.calendar)
            .setOnMenuItemClickListener(getClickListener(viewModel::onCalendarClicked))

        navigationMenu.findItem(R.id.people)
            ?.apply { isVisible = isPeopleVisible }
            ?.setOnMenuItemClickListener(getClickListener(viewModel::onPeopleClicked))

        navigationMenu.findItem(R.id.labels)
            .setOnMenuItemClickListener(getClickListener(viewModel::onLabelsClicked))

        navigationMenu.findItem(R.id.folders)
            .setOnMenuItemClickListener(getClickListener(viewModel::onFoldersClicked))

        navigationMenu.findItem(R.id.archive)
            ?.setOnMenuItemClickListener(getClickListener {
                context.startActivity(
                    Intent(context, GallerySingleRepositoryActivity::class.java)
                        .putExtras(
                            GallerySingleRepositoryActivity.getBundle(
                                title = context.getString(R.string.archive_title),
                                repositoryParams = SimpleGalleryMediaRepository.Params(
                                    archived = true,
                                ),
                            )
                        )
                )
            })

        navigationMenu.findItem(R.id.upload_queue)
            ?.setOnMenuItemClickListener(getClickListener {
                context.startActivity(Intent(context, ImportQueueActivity::class.java))
            })

        navigationMenu.findItem(R.id.preferences)
            .setOnMenuItemClickListener(getClickListener(viewModel::onPreferencesClicked))
    }

    /**
     * Shows the people entry only to those who may see people.
     */
    fun setPeopleVisible(isVisible: Boolean) {
        isPeopleVisible = isVisible
        menus.forEach { menu ->
            menu.findItem(R.id.people)?.isVisible = isVisible
        }
    }
}
