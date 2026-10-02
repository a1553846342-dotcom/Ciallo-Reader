package com.example.source.impl

import com.example.source.SourceResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MangaDexChapterFallbackTest {
    private fun source(hostedStatus: Int, sameTitle: Boolean = true): Pair<MangaDexSource, MutableList<String>> {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request.url.toString())
            var status = 200
            val body = when {
                request.url.encodedPath.startsWith("/at-home/server/") -> """{"baseUrl":"https://image.example","chapter":{"hash":"hash","data":["page.jpg"]}}"""
                request.url.host == "image.example" -> {
                    assertNull(request.header("Referer"))
                    assertEquals("bytes=0-63", request.header("Range"))
                    status = hostedStatus
                    if (status == 200) "image bytes" else ""
                }
                request.url.encodedPath == "/chapter/chapter-id" -> """{"data":{"attributes":{"chapter":"36"},"relationships":[{"type":"manga","id":"manga-id"}]}}"""
                request.url.encodedPath == "/manga/manga-id" -> """{"data":{"attributes":{"title":{"en":"Fixture Manga"}}}}"""
                request.url.encodedPath == "/search" -> """<div class="unit"><a href="/manga/other" title="Fixture Manga Fanbook">Other</a></div>""" +
                    if (sameTitle) """<div class="unit"><a href="/manga/fixture" title="Fixture Manga">Fixture Manga</a></div>""" else ""
                request.url.encodedPath == "/manga/fixture" -> """<ul class="chapter-list-item" data-code="EN"><li class="item" data-number="36"><a href="/read/fixture/en/chapter-id">36</a></li></ul>"""
                request.url.encodedPath == "/read/fixture/en/chapter-id" -> """<img src="https://cdn.example/chapter/hash/1.jpg"><img src="https://cdn.example/chapter/hash/2.jpg">"""
                else -> error("Unexpected request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return MangaDexSource(client) to requests
    }

    @Test fun listedButMissingOfficialImageFallsBackToTheSameMangaAndChapter() = runBlocking {
        val (source, requests) = source(404)
        val result = source.getChapterImages("mdapich:manga-id:chapter-id:36")
        assertTrue(result.toString(), result is SourceResult.Success)
        assertEquals(listOf("https://cdn.example/chapter/hash/1.jpg", "https://cdn.example/chapter/hash/2.jpg"),
            (result as SourceResult.Success).data)
        assertFalse(requests.any { it.contains("/manga/other") })
    }

    @Test fun availableHostedImagesStayOnTheOfficialSource() = runBlocking {
        val (source, requests) = source(200)
        val result = source.getChapterImages("mdapich:manga-id:chapter-id:36") as SourceResult.Success
        assertEquals(listOf("https://image.example/data/hash/page.jpg"), result.data)
        assertFalse(requests.any { it.contains("mangadex.live") })
    }

    @Test fun oldSavedChapterIdRecoversMangaIdentityForFallback() = runBlocking {
        val (source, requests) = source(404)
        val result = source.getChapterImages("mdapich:chapter-id")
        assertTrue(result.toString(), result is SourceResult.Success)
        assertTrue(requests.any { it.endsWith("/chapter/chapter-id") })
    }

    @Test fun unrelatedSearchHitCannotReplaceAMissingChapter() = runBlocking {
        val (source, requests) = source(404, sameTitle = false)
        val result = source.getChapterImages("mdapich:manga-id:chapter-id:36")
        assertTrue(result.toString(), result is SourceResult.Error)
        assertFalse(requests.any { it.contains("/manga/other") })
    }
}
