package io.github.hamzabhai.raftaaro.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import io.github.hamzabhai.raftaaro.core.JobKind
import io.github.hamzabhai.raftaaro.core.MediaType
import io.github.hamzabhai.raftaaro.core.Settings
import io.github.hamzabhai.raftaaro.core.VideoChoice
import io.github.hamzabhai.raftaaro.core.uniqueName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

enum class Bucket(val folder: String) { VIDEOS("Videos"), AUDIO("Audio"), FILES("Files") }

fun bucketOf(kind: JobKind, choice: VideoChoice?): Bucket = when {
    kind == JobKind.FILE -> Bucket.FILES
    choice?.media == MediaType.AUDIO -> Bucket.AUDIO
    else -> Bucket.VIDEOS
}

/** A file in shared storage: its content URI and the name it was saved under. */
data class SavedFile(val uri: String, val name: String)

/** Moves finished downloads where the user's gallery and file manager see them. */
class Saver(context: Context) {
    private val ctx = context.applicationContext
    private val resolver = ctx.contentResolver

    private fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Saves `file` (deleting it afterwards) to the bucket's folder, or to the folder picked in Settings. */
    suspend fun save(file: File, bucket: Bucket, subfolder: String?, settings: Settings): SavedFile = withContext(Dispatchers.IO) {
        val tree = when (bucket) {
            Bucket.VIDEOS -> settings.videosDir
            Bucket.AUDIO -> settings.audioDir
            Bucket.FILES -> settings.filesDir
        }
        val saved = when {
            tree != null -> saveToTree(file, Uri.parse(tree), subfolder)
            Build.VERSION.SDK_INT >= 29 -> saveToMediaStore(file, bucket, subfolder)
            else -> saveToPublicDir(file, bucket, subfolder)
        }
        file.delete()
        saved
    }

    private fun saveToMediaStore(file: File, bucket: Bucket, subfolder: String?): SavedFile {
        val path = listOfNotNull(Environment.DIRECTORY_DOWNLOADS, "Raftaaro", bucket.folder, subfolder).joinToString("/")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(file.name))
            put(MediaStore.Downloads.RELATIVE_PATH, path)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create ${file.name} in Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // MediaStore renames on a clash ("a (1).mp4"); report the name it really used
        val name = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: file.name
        return SavedFile(uri.toString(), name)
    }

    private suspend fun saveToPublicDir(file: File, bucket: Bucket, subfolder: String?): SavedFile {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), listOfNotNull("Raftaaro", bucket.folder, subfolder).joinToString("/"))
        dir.mkdirs()
        val name = uniqueName(file.name) { File(dir, it).exists() }
        val target = File(dir, name)
        file.copyTo(target)
        val uri = suspendCancellableCoroutine<Uri?> { cont ->
            MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mimeOf(name))) { _, u -> cont.resume(u) }
        } ?: throw IOException("Could not add $name to Downloads")
        return SavedFile(uri.toString(), name)
    }

    private fun saveToTree(file: File, treeUri: Uri, subfolder: String?): SavedFile {
        var dir = DocumentFile.fromTreeUri(ctx, treeUri) ?: throw IOException("The chosen folder is not available")
        if (subfolder != null) dir = dir.findFile(subfolder)?.takeIf { it.isDirectory } ?: dir.createDirectory(subfolder)
            ?: throw IOException("Could not create folder $subfolder")
        val doc = dir.createFile(mimeOf(file.name), file.name) ?: throw IOException("Could not create ${file.name}")
        resolver.openOutputStream(doc.uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        return SavedFile(doc.uri.toString(), doc.name ?: file.name)
    }

    fun open(uri: String): Intent {
        val u = Uri.parse(uri)
        return Intent(Intent.ACTION_VIEW).setDataAndType(u, resolver.getType(u)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun share(uri: String): Intent {
        val u = Uri.parse(uri)
        val send = Intent(Intent.ACTION_SEND).setType(resolver.getType(u) ?: "*/*").putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    suspend fun delete(uri: String): Boolean = withContext(Dispatchers.IO) {
        val u = Uri.parse(uri)
        runCatching {
            if (DocumentsContract.isDocumentUri(ctx, u)) DocumentsContract.deleteDocument(resolver, u)
            else resolver.delete(u, null, null) > 0
        }.getOrDefault(false)
    }
}
