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
import ua.com.radiokot.photoprism.env.data.storage.SessionCapabilitiesRepository
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import java.text.NumberFormat
import com.google.android.material.R as MaterialR

class GalleryNavigationView(
    private val viewModel: GalleryViewModel,
) {
    private var closeDrawer: (() -> Unit)? = null
    private val menus = mutableListOf<Menu>()
    private var isPeopleVisible = false
    private var isReviewVisible = false
    private var isArchiveVisible = true
    private var counts = SessionCapabilitiesRepository.LibraryCounts.UNKNOWN
    private var navigationRail: NavigationRailView? = null
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
        this.navigationRail = navigationRail
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

        navigationMenu.findItem(R.id.review)
            ?.apply { isVisible = isReviewVisible }
            ?.setOnMenuItemClickListener(getClickListener {
                openScope(
                    context = context,
                    scope = SimpleGalleryMediaRepository.Params.Scope.REVIEW,
                    titleRes = R.string.review_title,
                )
            })

        navigationMenu.findItem(R.id.archive)
            ?.apply { isVisible = isArchiveVisible }
            ?.setOnMenuItemClickListener(getClickListener {
                openScope(
                    context = context,
                    scope = SimpleGalleryMediaRepository.Params.Scope.ARCHIVED,
                    titleRes = R.string.archive_title,
                )
            })

        showCounts()

        navigationMenu.findItem(R.id.upload_queue)
            ?.setOnMenuItemClickListener(getClickListener {
                context.startActivity(Intent(context, ImportQueueActivity::class.java))
            })

        navigationMenu.findItem(R.id.preferences)
            .setOnMenuItemClickListener(getClickListener(viewModel::onPreferencesClicked))
    }

    private fun openScope(
        context: Context,
        scope: SimpleGalleryMediaRepository.Params.Scope,
        @StringRes titleRes: Int,
    ) = context.startActivity(
        Intent(context, GallerySingleRepositoryActivity::class.java)
            .putExtras(
                GallerySingleRepositoryActivity.getBundle(
                    title = context.getString(titleRes),
                    repositoryParams = SimpleGalleryMediaRepository.Params(
                        scope = scope,
                    ),
                )
            )
    )

    /**
     * Shows the people entry only to those who may see people.
     */
    fun setPeopleVisible(isVisible: Boolean) {
        isPeopleVisible = isVisible
        menus.forEach { menu ->
            menu.findItem(R.id.people)?.isVisible = isVisible
        }
    }

    /**
     * Shows the review and the archive only to those who may use them.
     */
    fun setArchiveAndReviewVisible(
        isArchiveVisible: Boolean,
        isReviewVisible: Boolean,
    ) {
        this.isArchiveVisible = isArchiveVisible
        this.isReviewVisible = isReviewVisible
        menus.forEach { menu ->
            menu.findItem(R.id.archive)?.isVisible = isArchiveVisible
            menu.findItem(R.id.review)?.isVisible = isReviewVisible
        }
        showCounts()
    }

    /**
     * Shows how many photos wait in review, which asks for action,
     * and, more quietly, how many are in the archive.
     */
    fun setCounts(counts: SessionCapabilitiesRepository.LibraryCounts) {
        this.counts = counts
        showCounts()
    }

    private fun showCounts() {
        val reviewCount = counts.review?.takeIf { it > 0 && isReviewVisible }
        val archivedCount = counts.archived?.takeIf { it > 0 && isArchiveVisible }

        menus.forEach { menu ->
            menu.findItem(R.id.review)?.actionView
                ?.findViewById<TextView>(R.id.navigation_counter)
                ?.showCount(reviewCount, isProminent = true)
            menu.findItem(R.id.archive)?.actionView
                ?.findViewById<TextView>(R.id.navigation_counter)
                ?.showCount(archivedCount, isProminent = false)
        }

        // The rail has no room for counts, the review gets a badge.
        navigationRail?.also { rail ->
            if (reviewCount != null) {
                rail.getOrCreateBadge(R.id.review).number = reviewCount
            } else {
                rail.removeBadge(R.id.review)
            }
        }
    }

    private fun TextView.showCount(count: Int?, isProminent: Boolean) {
        isVisible = count != null
        if (count == null) {
            return
        }
        text = NumberFormat.getIntegerInstance().format(count)
        if (isProminent) {
            setBackgroundResource(R.drawable.navigation_counter_background)
            setTextColor(MaterialColors.getColor(this, MaterialR.attr.colorOnSecondaryContainer))
        } else {
            background = null
            setTextColor(MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant))
        }
    }
}
