package com.example

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * Real Android DocumentsProvider registered under Robolectric at authority
 * "com.android.externalstorage.documents" so tests exercise 100% of the live
 * DocumentsContract SAF code paths (nested documentIds, openOutputStream("rwt"),
 * renameDocument, deleteDocument, and directory COLUMN_SIZE=4096).
 */
class TestSafDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        private const val TOTAL_CAPACITY_BYTES = 16L * 1024 * 1024 * 1024
        private const val AVAILABLE_CAPACITY_BYTES = 12L * 1024 * 1024 * 1024

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_CAPACITY_BYTES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        fun register(baseDir: File): TestSafDocumentsProvider {
            rootStorageDir = File(baseDir, "test_saf_provider_roots").apply {
                deleteRecursively()
                mkdirs()
            }
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            }
            return Robolectric.buildContentProvider(TestSafDocumentsProvider::class.java)
                .create(info)
                .get()
        }

        @Volatile
        private var rootStorageDir: File = File(System.getProperty("java.io.tmpdir"), "test_saf_provider_roots")
    }

    override fun onCreate(): Boolean = true

    private fun resolveFile(documentId: String): File {
        val parts = documentId.split(':', limit = 2)
        val vol = parts[0].ifBlank { "USB1" }
        val rel = parts.getOrNull(1).orEmpty().trimStart('/')
        val volRoot = File(rootStorageDir, vol).apply { mkdirs() }
        return if (rel.isEmpty()) volRoot else File(volRoot, rel)
    }

    private fun buildDocumentId(volume: String, file: File): String {
        val volRoot = File(rootStorageDir, volume)
        val rel = file.absolutePath.removePrefix(volRoot.absolutePath)
            .replace('\\', '/')
            .trimStart('/')
        return "$volume:$rel"
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(cols)
        val roots = rootStorageDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (dir in roots) {
            val row = cursor.newRow()
            for (col in cols) {
                when (col) {
                    DocumentsContract.Root.COLUMN_ROOT_ID -> row.add(dir.name)
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID -> row.add("${dir.name}:")
                    DocumentsContract.Root.COLUMN_TITLE -> row.add("USB Drive ${dir.name}")
                    DocumentsContract.Root.COLUMN_FLAGS -> row.add(
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                    )
                    DocumentsContract.Root.COLUMN_CAPACITY_BYTES -> row.add(TOTAL_CAPACITY_BYTES)
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> row.add(AVAILABLE_CAPACITY_BYTES)
                    else -> row.add(null)
                }
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(cols)
        val file = resolveFile(documentId)
        if (!file.exists()) {
            throw FileNotFoundException("Document not found: $documentId")
        }
        addFileRow(cursor, cols, documentId, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(cols)
        val parent = resolveFile(parentDocumentId)
        val vol = parentDocumentId.substringBefore(':')
        if (!parent.exists() || !parent.isDirectory) return cursor

        parent.listFiles()?.forEach { child ->
            val childId = buildDocumentId(vol, child)
            addFileRow(cursor, cols, childId, child)
        }
        return cursor
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = resolveFile(parentDocumentId)
        parent.mkdirs()
        val vol = parentDocumentId.substringBefore(':')
        val target = File(parent, displayName)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            target.mkdirs()
        } else {
            target.parentFile?.mkdirs()
            if (!target.exists()) target.createNewFile()
        }
        return buildDocumentId(vol, target)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = resolveFile(documentId)
        val vol = documentId.substringBefore(':')
        val dest = File(file.parentFile, displayName)
        if (!file.renameTo(dest)) {
            throw FileNotFoundException("Failed to rename $documentId to $displayName")
        }
        return buildDocumentId(vol, dest)
    }

    override fun deleteDocument(documentId: String) {
        val file = resolveFile(documentId)
        if (!file.deleteRecursively()) {
            throw FileNotFoundException("Failed to delete $documentId")
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = resolveFile(documentId)
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        if ((pfdMode and ParcelFileDescriptor.MODE_TRUNCATE) != 0 && file.exists()) {
            file.writeBytes(ByteArray(0))
        }
        return ParcelFileDescriptor.open(file, pfdMode)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parentVol = parentDocumentId.substringBefore(':')
        val childVol = documentId.substringBefore(':')
        if (parentVol != childVol) return false
        val parent = resolveFile(parentDocumentId).canonicalFile.toPath()
        val child = resolveFile(documentId).canonicalFile.toPath()
        return child.startsWith(parent)
    }

    private fun addFileRow(cursor: MatrixCursor, cols: Array<out String>, docId: String, file: File) {
        val row = cursor.newRow()
        val isDir = file.isDirectory
        val mime = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
        for (col in cols) {
            when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(file.name)
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mime)
                // Mimic real Android ExternalStorageProvider returning 4096 bytes for folders
                DocumentsContract.Document.COLUMN_SIZE -> row.add(if (isDir) 4096L else file.length())
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(file.lastModified())
                DocumentsContract.Document.COLUMN_FLAGS -> row.add(
                    DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                        DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                        DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                        if (isDir) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0
                )
                else -> row.add(null)
            }
        }
    }
}
