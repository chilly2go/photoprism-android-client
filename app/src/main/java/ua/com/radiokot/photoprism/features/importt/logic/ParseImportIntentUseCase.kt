package ua.com.radiokot.photoprism.features.importt.logic

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore.MediaColumns
import android.webkit.MimeTypeMap
import androidx.core.database.getLongOrNull
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.importt.model.ImportableFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        // Files already described by the caller don't need to be queried.
        val knownFiles = mutableMapOf<Uri, ImportableFile>()

        // A large selection is passed as a file,
        // as the intent extras can't hold thousands of URIs.
        intent.getStringExtra(EXTRA_URI_LIST_FILE_PATH)
            ?.let(::File)
            ?.also { uriListFile ->
                uriListFile
                    .readLines()
                    .filter(String::isNotBlank)
                    .forEach { line ->
                        val parts = line.split(LIST_FIELD_SEPARATOR)
                        val uri = Uri.parse(parts[0])
                        uris += uri
                        if (parts.size >= 4) {
                            knownFiles[uri] = ImportableFile(
                                contentUri = parts[0],
                                displayName = parts[1],
                                mimeType = parts[2].takeIf(String::isNotEmpty),
                                reportedSize = parts[3].toLongOrNull()?.takeIf { it > 0 },
                                takenAtMs = parts.getOrNull(4)?.toLongOrNull()?.takeIf { it > 0 },
                            )
                        }
                    }

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
            knownFiles[uri]?.also { return@mapNotNull it }

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

            val takenAtMs = queryTakenAtMs(uri)

            ImportableFile(
                contentUri = uri.toString(),
                mimeType = mimeType,
                reportedSize = reportedSize,
                displayName = getUsableDisplayName(
                    displayName = displayName,
                    mimeType = mimeType,
                    takenAtMs = takenAtMs,
                ),
                takenAtMs = takenAtMs,
            )
        }
    }

    /**
     * @return when the file was taken, or modified if unknown, in milliseconds,
     * if the provider tells it. Media providers do, other ones mostly don't.
     */
    private fun queryTakenAtMs(uri: Uri): Long? =
        try {
            contentResolver.query(
                uri,
                arrayOf(
                    COLUMN_DATE_TAKEN,
                    MediaColumns.DATE_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToNext()) {
                    return@use null
                }

                val takenIndex = cursor.getColumnIndex(COLUMN_DATE_TAKEN)
                val modifiedIndex = cursor.getColumnIndex(MediaColumns.DATE_MODIFIED)
                (if (takenIndex != -1) cursor.getLongOrNull(takenIndex) else null)
                    ?.takeIf { it > 0 }
                    // Seconds, unlike the date taken.
                    ?: (if (modifiedIndex != -1) cursor.getLongOrNull(modifiedIndex) else null)
                        ?.takeIf { it > 0 }
                        ?.times(1000)
            }
        } catch (error: Exception) {
            // A provider which doesn't know the columns may refuse the query.
            log.debug {
                "queryTakenAtMs(): query_failed:" +
                        "\nuri=$uri," +
                        "\nerror=$error"
            }
            null
        }

    companion object {
        private const val LIST_FIELD_SEPARATOR = '\t'

        // A plain name, as the constant is only defined in newer SDKs.
        private const val COLUMN_DATE_TAKEN = "datetaken"

        private val MEANINGLESS_NAME_REGEX = Regex("^\\d+$")

        /**
         * Some apps share files under a bare number without an extension, like "19422".
         * The library can't tell the file date or even the type from such a name,
         * so it gets a name from the date taken and the type, like "IMG_20201006_220326.jpg".
         * Meaningful names are kept as is.
         */
        fun getUsableDisplayName(
            displayName: String,
            mimeType: String?,
            takenAtMs: Long?,
        ): String {
            val baseName = displayName.substringBeforeLast('.', displayName)
            val hasExtension = displayName.contains('.')
                    && displayName.substringAfterLast('.').isNotEmpty()
            val isMeaningless = MEANINGLESS_NAME_REGEX.matches(baseName)

            if (hasExtension && !isMeaningless) {
                return displayName
            }

            val extension = mimeType
                ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
                ?: displayName.substringAfterLast('.', "").takeIf(String::isNotEmpty)

            val newBaseName =
                if (isMeaningless && takenAtMs != null) {
                    val prefix = if (mimeType?.startsWith("video/") == true) "VID" else "IMG"
                    val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                        .format(Date(takenAtMs))
                    "${prefix}_$date"
                } else {
                    baseName
                }

            return if (extension != null)
                "$newBaseName.$extension"
            else
                newBaseName
        }

        /**
         * Path of a text file with one content URI per line,
         * optionally followed by tab-separated display name, MIME type and size.
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

        /**
         * Writes the [files] to a file in the [cacheDir]
         * to be passed with [EXTRA_URI_LIST_FILE_PATH],
         * so they don't have to be queried again.
         *
         * @return the file path
         */
        fun writeFileListFile(
            cacheDir: File,
            files: Collection<ImportableFile>,
        ): String {
            fun String.clean() = replace(Regex("[\\t\\r\\n]"), " ")

            val directory = File(cacheDir, "import_uri_lists").apply { mkdirs() }
            val file = File(directory, "${System.currentTimeMillis()}.txt")
            file.writeText(files.joinToString(separator = "\n") { importableFile ->
                listOf(
                    importableFile.contentUri,
                    importableFile.displayName.clean(),
                    importableFile.mimeType?.clean() ?: "",
                    importableFile.reportedSize?.toString() ?: "",
                    importableFile.takenAtMs?.toString() ?: "",
                ).joinToString(separator = LIST_FIELD_SEPARATOR.toString())
            })
            return file.path
        }
    }
}
