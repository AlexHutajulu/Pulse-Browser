package com.example

import com.example.manager.CertificatePinning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for the audit fixes of 2026-10-26.
 * Source-text tests follow the existing AdBlockTest pattern: they guard
 * security defaults that must not silently regress in future AI Studio edits.
 */
class SecurityFixesTest {

    private fun findSource(relative: String): File {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("../app/src/main/java/$relative")
        )
        return candidates.firstOrNull { it.exists() }
            ?: File("app/src/main/java/$relative")
    }

    private fun findRes(relative: String): File {
        val candidates = listOf(
            File("src/main/res/$relative"),
            File("app/src/main/res/$relative"),
            File("../app/src/main/res/$relative")
        )
        return candidates.firstOrNull { it.exists() }
            ?: File("app/src/main/res/$relative")
    }

    @Test
    fun testNetworkSecurityDeniesCleartextByDefault() {
        val config = findRes("xml/network_security_config.xml")
        assertTrue("network_security_config.xml must exist", config.exists())
        val text = config.readText()
        assertTrue(
            "base-config must deny cleartext",
            text.contains("cleartextTrafficPermitted=\"false\"")
        )
        assertFalse(
            "base-config must not allow cleartext",
            text.contains("cleartextTrafficPermitted=\"true\"")
        )
    }

    @Test
    fun testScreenRecordingIsBlockedByDefault() {
        val source = findSource("com/example/manager/SettingsManager.kt").readText()
        assertTrue(
            "allowScreenRecording default must be false (FLAG_SECURE by default)",
            source.contains("getBoolean(KEY_ALLOW_SCREEN_RECORDING, false)")
        )
        assertFalse(
            "allowScreenRecording must not default to true",
            source.contains("getBoolean(KEY_ALLOW_SCREEN_RECORDING, true)")
        )
    }

    @Test
    fun testDatabaseNeverFallsBackToPlaintextFile() {
        val source = findSource("com/example/data/AppDatabase.kt").readText()
        assertFalse(
            "AppDatabase must never set PLAINTEXT_FALLBACK as a runtime status",
            source.contains("_encryptionStatus.value = DatabaseEncryptionStatus.PLAINTEXT_FALLBACK")
        )
        assertTrue(
            "AppDatabase fallback must be in-memory",
            source.contains("Room.inMemoryDatabaseBuilder")
        )
    }

    @Test
    fun testEncryptedPreferencesNeverFallBackToPlaintextFile() {
        val source = findSource("com/example/manager/EncryptedPreferencesManager.kt").readText()
        assertFalse(
            "EncryptedPreferencesManager must not fall back to a plaintext SharedPreferences file",
            source.contains("getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)")
        )
        assertTrue(
            "EncryptedPreferencesManager must use an in-memory store on Keystore failure",
            source.contains("InMemorySharedPreferences")
        )
    }

    @Test
    fun testFilterListContentValidation() {
        assertTrue(
            "Normal filter rules must pass",
            CertificatePinning.isSafeFilterListContent("||doubleclick.net^\n@@||example.com^\n")
        )
        assertFalse(
            "Filter list with a script tag must be rejected",
            CertificatePinning.isSafeFilterListContent("||example.com^\n<script>alert(1)</script>")
        )
        assertFalse(
            "Filter list with javascript: must be rejected",
            CertificatePinning.isSafeFilterListContent("||example.com^\njavascript:alert(1)")
        )
        assertFalse(
            "Filter list with data:text/html must be rejected",
            CertificatePinning.isSafeFilterListContent("data:text/html,<script>alert(1)</script>")
        )
    }

    @Test
    fun testCertificatePinnerIsExplicitWhenUnconfigured() {
        // Pins must be filled from a real network before release (see
        // CertificatePinning.kt). Until then, buildPinner() must return null
        // explicitly, not silently claim pinning is active.
        assertNull(CertificatePinning.buildPinner())
        assertEquals(0, CertificatePinning.FILTER_LIST_PINS.size)
    }

    @Test
    fun testJsBridgeBlocksExecutablesAndUaIsNotFrozenChrome124() {
        val source = findSource("com/example/ui/components/CustomWebView.kt").readText()
        assertTrue(
            "JS bridge must block executable blob downloads",
            source.contains("Blocked executable blob download via JS bridge")
        )
        assertTrue(
            "Desktop UA must be derived from the system WebView UA",
            source.contains("desktopUserAgentFromDefault")
        )
        assertFalse(
            "Hard-coded Chrome/124 UA must be gone",
            source.contains("Chrome/124.0.0.0")
        )
    }
}
