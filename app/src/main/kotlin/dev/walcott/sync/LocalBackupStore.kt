package dev.walcott.sync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.walcott.debug.DebugLog

/**
 * The on-device copy of the family backup, in shared storage so it outlives an uninstall.
 *
 * The point of this file is one specific disaster: somebody picks up the unlocked parent phone
 * and uninstalls Walcott. App-private storage goes with it, and without a backup the children
 * enforce the last rules forever with nobody able to change them. A copy under
 * `Documents/Walcott/` survives, because MediaStore keeps entries in shared collections when the
 * app that wrote them goes away.
 *
 * It is NOT a replacement for the off-device backup: a phone that is lost, stolen or broken takes
 * this file with it. It only covers uninstall.
 *
 * Reading it back is deliberately not attempted here. Under scoped storage a `.json` is not a
 * media file, and the entries this install wrote stop being attributed to us once the app is
 * uninstalled, so a reinstalled Walcott cannot enumerate them. `MANAGE_EXTERNAL_STORAGE` would
 * allow it and is refused on purpose: it has to be granted by hand in system Settings, so it
 * would be *more* friction than the document picker it replaces, on top of being the one
 * permission that would complete this app's resemblance to spyware. Restore opens the picker at
 * [FOLDER] instead — see the restore card on the mode-select screen.
 */
object LocalBackupStore {

    /** Where the copies live. Visible in any file manager, which is the point. */
    val FOLDER = "${Environment.DIRECTORY_DOCUMENTS}/Walcott"

    /**
     * One file per rotation slot AND per family; each is overwritten in place rather than
     * accumulating. The first family keeps the bare names it has always written to — those files
     * already exist on parents' phones and are what a restore reaches for — so only the families
     * added afterwards carry their id.
     */
    fun fileNameFor(slot: BackupRotation.Slot, familyId: String = dev.walcott.data.FamilyIds.DEFAULT): String {
        val base = when (slot) {
            BackupRotation.Slot.DAILY -> "walcott-backup-daily"
            BackupRotation.Slot.WEEKLY -> "walcott-backup-weekly"
            BackupRotation.Slot.MONTHLY -> "walcott-backup-monthly"
        }
        return if (familyId == dev.walcott.data.FamilyIds.DEFAULT) "$base.json" else "$base-$familyId.json"
    }

    /**
     * Writes [content] into this install's copy of [slot], replacing what was there, and returns
     * the document it used so the caller can come straight back to it next time. Null on failure
     * rather than throwing: a backup that can't be written must never take down the caller.
     *
     * [knownUri] is the document a previous run wrote, if any. Re-using it is not an optimisation
     * — it is the only thing that keeps a reinstalled app writing at all. A fresh install cannot
     * see the previous install's entries (scoped storage; verified on a device), so it would try
     * to insert a name that MediaStore still has a row for and get a UNIQUE violation on
     * `files._data` for ever. Hence the disambiguated retry below.
     */
    fun write(
        context: Context,
        slot: BackupRotation.Slot,
        content: String,
        knownUri: String? = null,
        familyId: String = dev.walcott.data.FamilyIds.DEFAULT,
    ): Uri? {
        val resolver = context.contentResolver
        val target = knownUri?.let { runCatching { Uri.parse(it) }.getOrNull() }?.takeIf { writeTo(resolver, it, content) }
        if (target != null) return target

        val name = fileNameFor(slot, familyId)
        findOwn(context, name)?.let { if (writeTo(resolver, it, content)) return it }

        // Fresh install, or the previous document was deleted from under us. Claim a name,
        // stepping aside from any row still held by an install that is gone.
        for (attempt in 0..MAX_NAME_ATTEMPTS) {
            val candidate = if (attempt == 0) name else disambiguate(name, attempt)
            val uri = runCatching { insert(resolver, candidate) }.getOrElse {
                DebugLog.w(TAG, "local backup insert of $candidate failed", it)
                null
            } ?: continue
            if (writeTo(resolver, uri, content)) return uri
        }
        DebugLog.w(TAG, "local backup for $slot could not claim a file")
        return null
    }

    private fun insert(resolver: android.content.ContentResolver, name: String): Uri? = resolver.insert(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        },
    )

    /** "walcott-backup-daily.json" -> "walcott-backup-daily-2.json". */
    private fun disambiguate(name: String, attempt: Int): String =
        "${name.substringBeforeLast('.')}-${attempt + 1}.${name.substringAfterLast('.')}"

    private fun writeTo(resolver: android.content.ContentResolver, uri: Uri, content: String): Boolean = runCatching {
        // "wt" truncates, so a shorter backup can't leave a tail of the previous one behind and
        // produce a file that is valid JSON followed by garbage.
        val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri)
        checkNotNull(stream) { "no output stream" }.use { it.write(content.toByteArray()) }
        true
    }.getOrElse { false }

    /**
     * This install's entry for [name], or null. Only ever finds files this install wrote: after an
     * uninstall the old entries are no longer ours, which is exactly why restore uses the picker.
     */
    private fun findOwn(context: Context, name: String): Uri? = runCatching {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf("$FOLDER%", name),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
            else null
        }
    }.getOrNull()

    /** What this install can currently see in [FOLDER], for diagnostics and the restore hint. */
    fun listOwn(context: Context): List<String> = runCatching {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$FOLDER%"),
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.orEmpty()
    }.getOrElse { emptyList() }

    /** How many disambiguated names to try before giving up (see [write]). */
    private const val MAX_NAME_ATTEMPTS = 5

    private const val TAG = "LocalBackup"
}
