package com.notedown

import androidx.datastore.preferences.core.*
import com.notedown.data.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CleanupProfilesTest {
    private val key = stringPreferencesKey("cleanupProfiles")

    @Test fun profilesWithoutIconsKeepTheirSettingsAndUseCleanupIcon() {
        val old = """[{"id":"custom","name":"Saved profile","prompt":"Keep my instructions.","model":"saved/model","shortcut":false}]"""
        val restored = decodeProfiles(old).single()
        assertEquals(CleanupProfile("custom", "Saved profile", "Keep my instructions.", "saved/model", false), restored)
        assertEquals(CleanupProfileIcon.CLEANUP, restored.icon)
        assertEquals(CleanupProfileIcon.CLEANUP, CleanupProfile("new", "New").icon)
    }

    @Test fun iconSelectionsRoundTripAndUnknownIconsFallBackWithoutLosingProfiles() {
        val profiles = CleanupProfileIcon.entries.map { icon -> CleanupProfile(icon.name, icon.name, icon = icon) }
        assertEquals(profiles, decodeProfiles(encodeProfiles(profiles)))
        val futureIcon = """[{"id":"custom","name":"Saved profile","icon":"FUTURE_ICON"}]"""
        assertEquals(CleanupProfileIcon.CLEANUP, decodeProfiles(futureIcon).single().icon)
    }

    @Test fun checklistUpgradeFixesOnlyTheUnmodifiedStarterPrompt() = runTest {
        val original = CleanupProfile("checklist", "My tasks", LEGACY_CHECKLIST_PROMPT, "saved/model", false)
        val custom = CleanupProfile("custom", "Custom checklist", LEGACY_CHECKLIST_PROMPT, "other/model")
        val migration = MarkdownChecklistMigration()
        val before = preferencesOf(key to encodeProfiles(listOf(original, custom)))
        assertTrue(migration.shouldMigrate(before))
        val after = migration.migrate(before)
        assertEquals(listOf(original.copy(prompt = CHECKLIST_PROMPT), custom), decodeProfiles(after[key]!!))
        assertFalse(migration.shouldMigrate(after))
        val edited = preferencesOf(key to encodeProfiles(listOf(original.copy(prompt = "My custom instructions"))))
        assertFalse(migration.shouldMigrate(edited))
        assertFalse(migration.shouldMigrate(preferencesOf(key to encodeProfiles(emptyList()))))
    }

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
