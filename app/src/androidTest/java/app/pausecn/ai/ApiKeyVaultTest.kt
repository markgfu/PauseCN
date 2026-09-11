package app.pausecn.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiKeyVaultTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun encryptedRoundTripClearAndMissingAliasNeverFallBackToPlaintext() {
        val suffix = System.nanoTime().toString()
        val preferenceName = "api-key-vault-test-$suffix"
        val alias = "pausecn.test.deepseek.$suffix"
        val secret = "sk-test-only-not-a-real-key"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val vault = ApiKeyVault(context, preferenceName, alias)

        try {
            vault.save(secret)

            assertEquals(setOf("iv", "ciphertext"), preferences.all.keys)
            assertFalse(preferences.all.values.any { it.toString().contains(secret) })
            assertEquals(secret, vault.read())
            assertTrue(keyStore.containsAlias(alias))

            keyStore.deleteEntry(alias)
            assertFalse(runCatching(vault::read).isSuccess)
            assertTrue("Ciphertext remains present until explicit clear", vault.hasKey())

            vault.clear()
            assertTrue(preferences.all.isEmpty())
            assertFalse(keyStore.containsAlias(alias))
        } finally {
            preferences.edit().clear().commit()
            keyStore.load(null)
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }
}
