package dev.walcott.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The policy store must never save an edit made to the empty stand-in it hands out when the stored
 * rules cannot be read. On a parent that edit was the start-up seeding, and it went to every child
 * as a family with no rules. Run against a real DataStore on a temporary file, because what has to
 * hold is what is on disk afterwards, including after a restart.
 */
class SettingsStoreGuardTest {

    @TempDir
    lateinit var dir: File

    private val scopes = mutableListOf<CoroutineScope>()
    private val policyKey = stringPreferencesKey("policy_json")
    private val asideKey = stringPreferencesKey("policy_json_unreadable")

    @AfterEach
    fun tearDown() = scopes.forEach { it.cancel() }

    /** One process's view of the file; a second call after [restart] is the next process. */
    private fun open(): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) { File(dir, "policy.preferences_pb") }
    }

    private fun restart() {
        scopes.forEach { it.cancel() }
        scopes.clear()
        // Let the cancelled store let go of the file before the next process opens it.
        Thread.sleep(50)
    }

    @Test
    fun `an edit is refused while the stored rules cannot be read`() = runBlocking {
        val data = open()
        data.edit { it[policyKey] = "{ this is not a policy" }
        val store = SettingsStore(data)

        assertTrue(store.current().fallback)
        assertThrows<PolicyUnreadableException> {
            runBlocking { store.update { it.copy(familyName = "Seeded over nothing") } }
        }
        assertEquals("{ this is not a policy", data.data.first()[policyKey], "nothing written over the original")
    }

    @Test
    fun `a wholesale replacement goes through and sets the unreadable blob aside`() = runBlocking {
        val data = open()
        data.edit { it[policyKey] = "{ this is not a policy" }
        val store = SettingsStore(data)

        store.update(overUnreadable = true) { PolicySettings(familyName = "From the parent") }

        assertEquals("From the parent", store.current().familyName)
        assertFalse(store.current().fallback)
        assertEquals("{ this is not a policy", data.data.first()[asideKey])
    }

    @Test
    fun `a file started over is still known to be lost after a restart`() {
        runBlocking {
            // What the corruption handler leaves on disk.
            open().edit { it[WalcottDataStores.REPLACED] = true }
        }
        restart()
        runBlocking {
            val store = SettingsStore(open())
            // Not a fresh install: a child must keep its restrictions and adopt the parent's next
            // snapshot whatever its version.
            assertTrue(store.current().fallback)
            assertTrue(store.consumeCorruption())
            assertThrows<PolicyUnreadableException> { runBlocking { store.update { it } } }
        }
    }

    @Test
    fun `the first real write clears the mark`() {
        runBlocking {
            val store = SettingsStore(open().also { d -> d.edit { it[WalcottDataStores.REPLACED] = true } })
            store.update(overUnreadable = true) { PolicySettings(familyName = "Adopted") }
        }
        restart()
        runBlocking {
            val store = SettingsStore(open())
            assertFalse(store.current().fallback)
            assertEquals("Adopted", store.current().familyName)
        }
    }

    @Test
    fun `a fresh install is an ordinary empty policy that can be edited`() = runBlocking {
        val store = SettingsStore(open())
        assertFalse(store.current().fallback)
        store.update { it.copy(familyName = "New family") }
        assertEquals("New family", store.current().familyName)
    }

    @Test
    fun `an unreadable blob after a good one in the same process keeps editing the good one`() = runBlocking {
        val data = open()
        val store = SettingsStore(data)
        store.update { it.copy(familyName = "Good") }
        assertEquals("Good", store.current().familyName)
        data.edit { it[policyKey] = "{ broken" }

        // The last good policy stands in (it is a real one), so an edit still has something to edit.
        store.update { it.copy(familyName = "Edited") }
        assertEquals("Edited", store.current().familyName)
        assertEquals("{ broken", data.data.first()[asideKey])
    }
}
