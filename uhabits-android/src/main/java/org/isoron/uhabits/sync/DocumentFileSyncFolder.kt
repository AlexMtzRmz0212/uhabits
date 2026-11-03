package org.isoron.uhabits.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.isoron.uhabits.core.sync.SyncFolder
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The sync folder, stored in a `LoopSync` subfolder of the folder the user picked, so the sync
 * files never sit next to the `.db` backups. Files are gzip-compressed on disk.
 */
class DocumentFileSyncFolder(private val context: Context, treeUri: Uri) : SyncFolder {
    private val root: DocumentFile = (
        if (treeUri.scheme == "content") {
            DocumentFile.fromTreeUri(context, treeUri)
        } else {
            DocumentFile.fromFile(File(treeUri.path!!))
        }
        ) ?: throw IOException("Cannot open sync folder: $treeUri")

    private val dir: DocumentFile by lazy {
        root.findFile(SUBFOLDER)?.takeIf { it.isDirectory }
            ?: root.createDirectory(SUBFOLDER)
            ?: throw IOException("Cannot create $SUBFOLDER in the sync folder")
    }

    override fun listFiles(): List<String> {
        refreshListing()
        return dir.listFiles().filter { it.isFile }.mapNotNull { it.name }
    }

    /** Asks the storage provider (Google Drive) to re-fetch the folder instead of serving a cached listing. */
    private fun refreshListing() {
        try {
            if (dir.uri.scheme != "content") return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                dir.uri,
                DocumentsContract.getDocumentId(dir.uri)
            )
            context.contentResolver.refresh(children, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot refresh the listing", e)
        }
    }

    override fun read(name: String): String? {
        val file = dir.findFile(name) ?: return null
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                GZIPInputStream(input).bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: IOException) {
            // Most likely the other device is still writing the file. Try again next round.
            Log.w(TAG, "Cannot read $name", e)
            null
        }
    }

    override fun describe(name: String): String? {
        val file = dir.findFile(name) ?: return "not found"
        val modified = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(file.lastModified())
        return "${file.length()} bytes, modified $modified"
    }

    override fun write(name: String, text: String) {
        var file = dir.findFile(name)
        if (file == null) {
            file = dir.createFile("application/octet-stream", name)
                ?: throw IOException("Cannot create $name")
            if (file.name != name) file.renameTo(name)
        }
        val output = context.contentResolver.openOutputStream(file.uri, "wt")
            ?: throw IOException("Cannot write $name")
        output.use { GZIPOutputStream(it).use { gz -> gz.write(text.toByteArray(Charsets.UTF_8)) } }
    }

    companion object {
        const val SUBFOLDER = "LoopSync"
        private const val TAG = "SyncFolder"
    }
}
