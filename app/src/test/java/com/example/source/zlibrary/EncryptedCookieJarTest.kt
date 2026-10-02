package com.example.source.zlibrary

import androidx.test.core.app.ApplicationProvider
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EncryptedCookieJarTest {

    @Test
    fun testSaveAndReloadAfterAppRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = ZLibraryCredentialStorage(context.getSharedPreferences("credential-test", android.content.Context.MODE_PRIVATE))
        storage.clear()

        val cookieJar1 = EncryptedCookieJar(storage)
        val rawCookieStr = "remix_userid=112233; remix_userkey=key123456789; session=abc"
        cookieJar1.syncFromRawCookieString(rawCookieStr, "1lib.sk")

        // Simulate App Restart by creating a new EncryptedCookieJar with same storage
        val cookieJar2 = EncryptedCookieJar(storage)

        val targetUrl = "https://1lib.sk/search".toHttpUrl()
        val loadedCookies = cookieJar2.loadForRequest(targetUrl)

        assertEquals(3, loadedCookies.size)
        assertTrue(loadedCookies.any { it.name == "remix_userid" && it.value == "112233" })
        assertTrue(loadedCookies.any { it.name == "remix_userkey" && it.value == "key123456789" })
        assertTrue(loadedCookies.any { it.name == "session" && it.value == "abc" })
    }

    @Test
    fun testDomainIsolationDoesNotLeakCookies() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = ZLibraryCredentialStorage(context.getSharedPreferences("credential-test", android.content.Context.MODE_PRIVATE))
        storage.clear()

        val cookieJar = EncryptedCookieJar(storage)
        cookieJar.syncFromRawCookieString("remix_userid=999; remix_userkey=secretkey", "1lib.sk")

        // Request to matching domain 1lib.sk
        val zlibUrl = "https://1lib.sk/book/123".toHttpUrl()
        val zlibCookies = cookieJar.loadForRequest(zlibUrl)
        assertFalse(zlibCookies.isEmpty())

        // Request to completely unrelated domain
        val maliciousUrl = "https://evil-site.com/steal".toHttpUrl()
        val maliciousCookies = cookieJar.loadForRequest(maliciousUrl)
        assertTrue("Cookies should not leak to external domain", maliciousCookies.isEmpty())
    }

    @Test
    fun testDomainSwitchingIsolation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = ZLibraryCredentialStorage(context.getSharedPreferences("credential-test", android.content.Context.MODE_PRIVATE))
        storage.clear()

        val cookieJar = EncryptedCookieJar(storage)
        cookieJar.syncFromRawCookieString("remix_userid=1010; remix_userkey=key1010", "1lib.cz")

        val czUrl = "https://1lib.cz/login".toHttpUrl()
        val gsUrl = "https://1lib.sk/login".toHttpUrl()

        val czCookies = cookieJar.loadForRequest(czUrl)
        val gsCookies = cookieJar.loadForRequest(gsUrl)

        assertFalse(czCookies.isEmpty())
        assertTrue("Cookies stored for 1lib.cz must not match 1lib.sk", gsCookies.isEmpty())
    }
}
