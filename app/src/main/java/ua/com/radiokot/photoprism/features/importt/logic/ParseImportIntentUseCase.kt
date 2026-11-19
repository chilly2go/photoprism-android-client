package ua.com.radiokot.photoprism.features.importt.logic

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore.MediaColumns
import androidx.core.database.getLongOrNull
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.importt.model.ImportableFile
import java.io.File

class ParseImportIntentUseCase(
    private val contentResolver: ContentResolver,
) {
    private val log = kLogger("ParseImportIntentUseCase")

    operator fun invoke(
        intent: Intent,
    ): List<ImportableFile> {
        check(intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            "The intent has unsupported action ${intent.action}"
        }

        val uris = mutableSetOf<Uri>()
        intent.data?.also(uris::add)

        // A large selection is passed as a file,
        // as the intent extras can't hold thousands of URIs.
        intent.getStringExtra(EXTRA_URI_LIST_FILE_PATH)
            ?.let(::File)
            ?.also { uriListFile ->
                uriListFile
                    .readLines()
                    .filter(String::isNotBlank)
                    .mapTo(uris, Uri::parse)

                uriListFile.delete()
            }

        @Suppress("DEPRECATION")
        when (intent.action) {
            Intent.ACTION_SEND ->
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.also(uris::add)

            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.also(uris::addAll)
        }

        log.debug {
            "invoke(): parsing_uris:" +
                    "\nuris=${uris.size}"
        }

        check(uris.isNotEmpty()) {
            "The intent has no URIs"
        }

        return uris.mapNotNull { uri ->
            val mimeType: String? = contentResolver.getType(uri)
            val reportedSize: Long?
            val displayName: String

            val queryCursor = contentResolver.query(
                uri,
                arrayOf(
                    MediaColumns.SIZE,
                    MediaColumns.DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )

            if (queryCursor == null) {
                log.warn {
                    "invoke(): uri_query_failed:" +
                            "\nuri=$uri"
                }

                return@mapNotNull null
            }

            queryCursor.use { cursor ->
                if (!cursor.moveToNext()) {
                    log.warn {
                        "uri_query_is_empty:" +
                                "\nuri=$uri"
                    }

                    return@mapNotNull null
                }

                val sizeColumnIndex = cursor.getColumnIndex(MediaColumns.SIZE)
                reportedSize =
                    if (sizeColumnIndex != -1)
                        cursor.getLongOrNull(sizeColumnIndex)
                    else
                        null

                displayName =
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME))
            }

            ImportableFile(
                contentUri = uri.toString(),
                mimeType = mimeType,
                reportedSize = reportedSize,
                displayName = displayName,
            )
        }
    }

    companion object {
        /**
         * Path of a text file with one content URI per line.
         * The file is deleted once read.
         */
        const val EXTRA_URI_LIST_FILE_PATH = "uri_list_file_path"

        /**
         * Writes the [uris] to a file in the [cacheDir]
         * to be passed with [EXTRA_URI_LIST_FILE_PATH].
         *
         * @return the file path
         */
        fun writeUriListFile(
            cacheDir: File,
            uris: Collection<Uri>,
        ): String {
            val directory = File(cacheDir, "import_uri_lists").apply { mkdirs() }
            val file = File(directory, "${System.currentTimeMillis()}.txt")
            file.writeText(uris.joinToString(separator = "\n"))
            return file.path
        }
    }
}
