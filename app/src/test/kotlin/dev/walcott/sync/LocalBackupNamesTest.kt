package dev.walcott.sync

import dev.walcott.data.FamilyIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalBackupNamesTest {

    @Test
    fun `the first family keeps the names its files already have`() {
        // These files exist on parents' phones today and are what a restore reaches for; a
        // family id in the name would orphan every copy written before multi-family existed.
        assertEquals("walcott-backup-daily.json", LocalBackupStore.fileNameFor(BackupRotation.Slot.DAILY))
        assertEquals("walcott-backup-weekly.json", LocalBackupStore.fileNameFor(BackupRotation.Slot.WEEKLY))
        assertEquals("walcott-backup-monthly.json", LocalBackupStore.fileNameFor(BackupRotation.Slot.MONTHLY))
    }

    @Test
    fun `every later family writes to files of its own`() {
        // Otherwise the nightly copies of two families would overwrite each other and only the
        // last one written would be recoverable.
        val names = BackupRotation.Slot.entries.map { LocalBackupStore.fileNameFor(it, "ab12cd34") }
        assertEquals(names.size, names.distinct().size)
        assertTrue(names.all { it.contains("ab12cd34") && it.endsWith(".json") })
        assertTrue(
            BackupRotation.Slot.entries.none {
                LocalBackupStore.fileNameFor(it, "ab12cd34") == LocalBackupStore.fileNameFor(it, FamilyIds.DEFAULT)
            },
        )
    }
}
