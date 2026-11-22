package ua.com.radiokot.photoprism.features.people.view.model

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import com.squareup.picasso.Picasso
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.inject
import org.koin.core.scope.Scope
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.databinding.ListItemPersonTileBinding
import ua.com.radiokot.photoprism.di.DI_SCOPE_SESSION
import ua.com.radiokot.photoprism.features.gallery.logic.MediaPreviewUrlFactory
import ua.com.radiokot.photoprism.features.people.data.model.Person
import ua.com.radiokot.photoprism.util.images.ImageTransformations

/**
 * A round face tile of the people overview,
 * either a named person or an unnamed face cluster.
 */
class PersonTileListItem(
    val name: String?,
    val count: Int,
    val isFavorite: Boolean,
    val thumbnailUrl: String,
    val source: Person,
) : AbstractItem<PersonTileListItem.ViewHolder>() {

    constructor(
        source: Person,
        previewUrlFactory: MediaPreviewUrlFactory,
    ) : this(
        name = source.name,
        count = source.photoCount,
        isFavorite = source.isFavorite,
        thumbnailUrl = previewUrlFactory.getThumbnailUrl(
            thumbnailHash = source.thumbnailHash,
            sizePx = 320,
        ),
        source = source,
    )

    override val type: Int =
        R.layout.list_item_person_tile

    override val layoutRes: Int =
        R.layout.list_item_person_tile

    override var identifier: Long =
        source.hashCode().toLong()

    override fun getViewHolder(v: View): ViewHolder =
        ViewHolder(v)

    class ViewHolder(
        itemView: View,
    ) : FastAdapter.ViewHolder<PersonTileListItem>(itemView),
        KoinScopeComponent {
        override val scope: Scope
            get() = getKoin().getScope(DI_SCOPE_SESSION)

        private val view = ListItemPersonTileBinding.bind(itemView)
        private val picasso: Picasso by inject()

        override fun bindView(item: PersonTileListItem, payloads: List<Any>) {
            val context = view.root.context

            view.imageView.contentDescription = item.name

            picasso
                .load(item.thumbnailUrl)
                .placeholder(R.drawable.image_placeholder_circle)
                .fit()
                .centerCrop()
                .transform(ImageTransformations.circle)
                .into(view.imageView)

            view.favoriteImageView.isVisible = item.isFavorite

            with(view.nameTextView) {
                text = item.name
                isVisible = item.name != null
            }

            view.countTextView.text =
                if (item.source.isFaceCluster)
                    context.resources.getQuantityString(
                        R.plurals.people_overview_matches,
                        item.count,
                        item.count,
                    )
                else
                    context.resources.getQuantityString(
                        R.plurals.people_overview_photos,
                        item.count,
                        item.count,
                    )

            ViewCompat.setTooltipText(view.root, item.name)
        }

        override fun unbindView(item: PersonTileListItem) {
            picasso.cancelRequest(view.imageView)
        }
    }
}
