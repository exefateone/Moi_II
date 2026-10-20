package com.iipauk.spider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Initial native instrumentation tests; run on an emulator/device after Gradle Sync. */
@RunWith(AndroidJUnit4::class)
class NativeShellSmokeTest {
    @Test fun workspaceRepositoryCreatesPrivateDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = WorkspaceRepository(context)
        val item = repo.create("QA workspace")
        assertTrue(item.directory.isDirectory)
        assertEquals(item.id, repo.directoryFor(item.id)?.name)
        assertTrue(repo.delete(item.id))
    }

    @Test fun providerTokenIsEncryptedWithAndroidKeystoreAndCanBeCleared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("spider-agent-provider", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = SpiderProviderConfigStore(context)
        val endpoint = "https://agent.example.com/api/agent/plan"
        val token = "device-test-client-token-at-least-24-chars"

        store.save(endpoint, token)
        assertEquals(endpoint, store.load()?.endpointUrl)
        assertEquals(token, store.load()?.clientToken)
        val ciphertext = preferences.getString("token_ciphertext", "")
        assertNotNull(ciphertext)
        assertFalse(ciphertext!!.contains(token))

        store.clear()
        assertNull(store.load())
    }
}
