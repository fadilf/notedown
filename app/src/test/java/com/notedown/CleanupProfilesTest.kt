package com.notedown

import androidx.datastore.preferences.core.*
import com.notedown.data.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CleanupProfilesTest {
    private val key = stringPreferencesKey("cleanupProfiles")

    @Test fun upgradePreservesCustomPromptModelAndShortcutPreference() = runTest {
        val migration = CleanupProfilesMigration()
        val old = preferencesOf(
            stringPreferencesKey("cleanupModel") to "saved/model",
            stringPreferencesKey("prompt") to "Keep my custom formatting.",
            booleanPreferencesKey("cleanup") to false,
            stringPreferencesKey("model") to "speech/model"
        )
        assertTrue(migration.shouldMigrate(old))
        val migrated = migration.migrate(old)
        val profiles = decodeProfiles(migrated[key]!!)
        assertEquals(listOf("General cleanup", "List", "Checklist"), profiles.map { it.name })
        assertEquals("Keep my custom formatting.", profiles.first().prompt)
        assertFalse(profiles.first().shortcut)
        assertTrue(profiles.all { it.model == "saved/model" })
        assertEquals("speech/model", migrated[stringPreferencesKey("model")])
        assertFalse(migration.shouldMigrate(migrated))
    }

    @Test fun freshInstallHasThreeShortcutsAndDeletionDoesNotReseedThem() = runTest {
        val migration = CleanupProfilesMigration()
        val fresh = migration.migrate(emptyPreferences())
        assertTrue(decodeProfiles(fresh[key]!!).all { it.shortcut })
        val deleted = fresh.toMutablePreferences().apply { this[key] = encodeProfiles(emptyList()) }
        assertFalse(migration.shouldMigrate(deleted))
        assertTrue(decodeProfiles(deleted[key]!!).isEmpty())
    }

    @Test fun selectedProfileSnapshotIsIndependentAndPlainFinishDisablesLegacyCleanup() {
        val profile = CleanupProfile("custom", "Custom", "Format as a list.", "text/model")
        val original = Settings(model = "speech/model", language = "en", wav = true, skipSilence = true,
            cleanup = true, cleanupModel = "legacy/model", cleanupProfiles = listOf(profile))
        val queued = original.withCleanupProfile(profile)
        val edited = original.copy(cleanupProfiles = listOf(profile.copy(model = "other/model", prompt = "Different instructions")))
        assertEquals("text/model", queued.cleanupModel)
        assertEquals("Format as a list.", queued.prompt)
        assertEquals("other/model", edited.cleanupProfiles.single().model)
        assertEquals("speech/model", queued.model)
        assertEquals("en", queued.language)
        assertTrue(queued.wav && queued.skipSilence && queued.cleanup)
        val plain = original.withCleanupProfile(null)
        assertFalse(plain.cleanup)
        assertEquals("", plain.cleanupModel)
    }
}
