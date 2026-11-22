package ua.com.radiokot.photoprism.features.people.view

import android.app.Dialog
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.squareup.picasso.Picasso
import org.koin.android.ext.android.getKoin
import ua.com.radiokot.photoprism.R
import ua.com.radiokot.photoprism.databinding.DialogNameFaceBinding
import ua.com.radiokot.photoprism.di.DI_SCOPE_SESSION
import ua.com.radiokot.photoprism.util.images.ImageTransformations

/**
 * "Who is this?" for an unnamed face: a name with suggestions of the known people,
 * hiding the face, or looking at its photos first.
 *
 * The choice is delivered as a fragment result for [REQUEST_KEY],
 * see [RESULT_ACTION].
 */
class NameFaceDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val faceId = args.getString(FACE_ID_KEY)!!
        val markerUid = args.getString(MARKER_UID_KEY)!!
        val thumbnailUrl = args.getString(THUMBNAIL_URL_KEY)
        val suggestions = args.getStringArrayList(SUGGESTIONS_KEY) ?: arrayListOf()

        val view = DialogNameFaceBinding.inflate(layoutInflater)

        if (thumbnailUrl != null) {
            getKoin().getScopeOrNull(DI_SCOPE_SESSION)
                ?.getOrNull<Picasso>()
                ?.load(thumbnailUrl)
                ?.placeholder(R.drawable.image_placeholder_circle)
                ?.fit()
                ?.centerCrop()
                ?.transform(ImageTransformations.circle)
                ?.into(view.imageView)
        } else {
            view.imageView.isVisible = false
        }

        view.nameEditText.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                suggestions,
            )
        )

        fun deliver(action: String) {
            setFragmentResult(
                REQUEST_KEY,
                bundleOf(
                    RESULT_ACTION to action,
                    RESULT_FACE_ID to faceId,
                    RESULT_MARKER_UID to markerUid,
                    RESULT_NAME to view.nameEditText.text?.toString()?.trim(),
                )
            )
        }

        view.showPhotosButton.setOnClickListener {
            deliver(ACTION_SHOW_PHOTOS)
            dismiss()
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.people_overview_who_is_this)
            .setView(view.root)
            .setPositiveButton(R.string.save) { _, _ ->
                deliver(ACTION_NAME)
            }
            .setNeutralButton(R.string.people_overview_hide) { _, _ ->
                deliver(ACTION_HIDE)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun updateSaveButton() {
                saveButton.isEnabled = !view.nameEditText.text.isNullOrBlank()
            }
            updateSaveButton()
            view.nameEditText.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) = updateSaveButton()
                }
            )
            view.nameEditText.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE && saveButton.isEnabled) {
                    saveButton.performClick()
                    true
                } else {
                    false
                }
            }
        }

        return dialog
    }

    companion object {
        const val TAG = "name_face"
        const val REQUEST_KEY = "name_face_result"
        const val RESULT_ACTION = "action"
        const val RESULT_FACE_ID = "face_id"
        const val RESULT_MARKER_UID = "marker_uid"
        const val RESULT_NAME = "name"
        const val ACTION_NAME = "name"
        const val ACTION_HIDE = "hide"
        const val ACTION_SHOW_PHOTOS = "show_photos"

        private const val FACE_ID_KEY = "face_id"
        private const val MARKER_UID_KEY = "marker_uid"
        private const val THUMBNAIL_URL_KEY = "thumbnail_url"
        private const val SUGGESTIONS_KEY = "suggestions"

        fun newInstance(
            faceId: String,
            markerUid: String,
            thumbnailUrl: String?,
            nameSuggestions: List<String>,
        ) = NameFaceDialogFragment().apply {
            arguments = bundleOf(
                FACE_ID_KEY to faceId,
                MARKER_UID_KEY to markerUid,
                THUMBNAIL_URL_KEY to thumbnailUrl,
                SUGGESTIONS_KEY to ArrayList(nameSuggestions),
            )
        }
    }
}
