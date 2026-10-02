package com.example.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.source.js.JsComicSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourceRegistrationTest {
    @Test fun rejectsNonWebLinksAndCredentials() {
        listOf(null, "", "javascript:alert(1)", "file:///tmp/register", "https://user:password@example.com/register")
            .forEach { assertNull(validRegistrationUrl(it)) }
        assertEquals("https://example.com/register?q=1", validRegistrationUrl(" https://example.com/register?q=1 "))
    }

    @Test fun literalSourceRegistrationDoesNotInitializeItsRuntimeOrPerformLogin() = runBlocking {
        val source = JsComicSource(ApplicationProvider.getApplicationContext<Context>(), "test", "Test", "1",
            "class Test extends ComicSource { account = { registerWebsite: 'https://example.com/register' }; init() { throw Error('must not run'); } }")
        assertEquals("https://example.com/register", source.getRegistrationUrl())
        assertNull(JsComicSource::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(source))
    }
}
