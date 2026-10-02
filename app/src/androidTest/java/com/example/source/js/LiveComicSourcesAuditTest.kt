package com.example.source.js

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.source.ComicSource
import com.example.source.SourceResult
import com.example.source.impl.MangaDexSource
import com.example.source.LoginCredential
import com.example.library.ComicDownloadManager
import com.example.library.ComicDownloadStatus
import com.example.data.AppDatabase
import com.example.ui.comicImageLoader
import com.example.ui.comic.ComicImagePipeline
import com.example.ui.comic.ComicPageLoader
import com.example.ui.comic.ComicPageRef
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly invoked live audit; network outcomes are recorded, never assumed successful. */
@RunWith(AndroidJUnit4::class)
class LiveComicSourcesAuditTest {
    @Test fun auditEveryOfferedSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requestedKey = InstrumentationRegistry.getArguments().getString("sourceKey").orEmpty()
        val requestedKeys = requestedKey.split(',').filter { it.isNotBlank() }.toSet()
        val download = InstrumentationRegistry.getArguments().getString("fullDownload") == "true"
        val auditQuery = InstrumentationRegistry.getArguments().getString("auditQuery").orEmpty()
        val imageSamples = InstrumentationRegistry.getArguments().getString("imageSamples")?.toIntOrNull()?.coerceIn(1, 24) ?: 1
        val freshMycomicSession = requestedKeys == setOf("mycomic") &&
            InstrumentationRegistry.getArguments().getString("freshSession") == "true"
        if (freshMycomicSession) {
            // Explicit live diagnostic on the disposable device: keep all other accounts.
            JsCookieJar.clear(context, "mycomic.com")
            context.getSharedPreferences("js_source_website_verification", Context.MODE_PRIVATE)
                .edit().remove("mycomic_user_agent").commit()
            withContext(Dispatchers.Main) {
                val cookies = android.webkit.CookieManager.getInstance()
                cookies.getCookie("https://mycomic.com/").orEmpty().split(';').forEach { pair ->
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotEmpty()) {
                        cookies.setCookie("https://mycomic.com/", "$name=; Max-Age=0; Path=/")
                        cookies.setCookie("https://mycomic.com/", "$name=; Max-Age=0; Path=/; Domain=.mycomic.com")
                    }
                }
                cookies.flush()
            }
        }
        // Inject only into the disposable device's private files directory; never bundle credentials.
        val credentialFile = File(context.filesDir, "source-audit-credentials.json")
        val credentials = try {
            if (credentialFile.isFile) JSONObject(credentialFile.readText()) else JSONObject()
        } finally { credentialFile.delete() }
        val report = File(context.filesDir, "comic-source-audit.jsonl").apply { writeText("") }
        val index = InstrumentationRegistry.getInstrumentation().context.assets
            .open("source-audit/index.json").bufferedReader().use { it.readText() }
        val parser = JsSourceRepo::class.java.getDeclaredMethod("parseIndex", String::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val expected = (parser.invoke(JsSourceRepo, index) as List<JsSourceRepo.SourceMeta>)
        val installed = if (requestedKey.isNotEmpty()) JsSourceRepo.loadCached(context, true) else JsSourceRepo.install(context,
            "https://raw.githubusercontent.com/venera-app/venera-configs/main/index.json", true) {
            Log.i("SourceAudit", it)
        }
        val byKey = installed.associateBy { it.sourceKey }
        fun record(row: JSONObject) = synchronized(report) {
            report.appendText(row.toString() + "\n")
            Log.i("SourceAudit", row.toString())
        }
        expected.filter { it.key !in byKey }.forEach {
            record(JSONObject().put("key", it.key).put("name", it.name)
                .put("stage", "script_download").put("status", "failed"))
        }
        val sources = (installed + MangaDexSource(context = context)).filter {
            requestedKeys.isEmpty() || ((it as? JsComicSource)?.sourceKey ?: it.id) in requestedKeys
        }
        val gate = Semaphore(2)
        sources.map { source -> async(Dispatchers.IO) { gate.withPermit {
            val key = (source as? JsComicSource)?.sourceKey ?: source.id
            val row = JSONObject().put("key", key).put("name", source.name)
            if (key == "mycomic" && freshMycomicSession) row.put("freshSession", true)
            val started = SystemClock.elapsedRealtime()
            Log.i("SourceAudit", "START $key")
            suspend fun <T> stage(name: String, timeoutMs: Long = 45_000, block: suspend () -> T): T {
                row.put("stage", name)
                val start = SystemClock.elapsedRealtime()
                return withTimeout(timeoutMs) { block() }.also {
                    row.put(name + "Ms", SystemClock.elapsedRealtime() - start)
                }
            }
            fun <T> value(result: SourceResult<T>): T = when (result) {
                is SourceResult.Success -> result.data
                is SourceResult.Error -> throw result.exception
            }
            val loader = ComicPageLoader(context, comicImageLoader(context))
            try {
                if (key in setOf("picacg", "vomic") && credentials.has(key)) {
                    val credential = credentials.getJSONObject(key)
                    // Do not put login replies or account identifiers in audit reports.
                    val authenticated = withTimeout(45_000) { source.login(LoginCredential(
                        username = credential.getString("username"), password = credential.getString("password")
                    )) }
                    val ok = authenticated is SourceResult.Success && authenticated.data
                    row.put("loginStatus", if (ok) "ok" else "failed")
                    if (!ok) error("Account login did not succeed; private response omitted")
                }
                // Avoid mistaking an unsupported Chinese keyword for a broken English source.
                val keyword = auditQuery.ifEmpty { when (key) {
                    "nhentai", "wnacg", "ehentai", "hitomi", "jm" -> "miku"
                    "comick", "hcomic", "hot_manga", "mangadex" -> "naruto"
                    "bilimanga" -> "火影"
                    "vomic" -> "naruto"
                    "pufei" -> "我家老婆来自一千年前"
                    else -> "斗破"
                } }
                var books = stage("search") { value(source.search(keyword)) }
                row.put("query", keyword)
                if (books.isEmpty() && auditQuery.isEmpty()) {
                    if (key == "vomic" && source is JsComicSource) {
                        val engine = JsComicSource::class.java.getDeclaredField("engine")
                            .apply { isAccessible = true }.get(source) as JsSourceEngine
                        val probe = """
                            (async()=>{
                                const r=await Network.get(src.site('/so/key/%E6%96%97%E7%A0%B4/1'),src.rscHeaders(await src.token()));
                                const b=typeof r.body==='string'?r.body:'';
                                return {status:r.status,length:b.length,firstChar:b.charCodeAt(0)||0,
                                    cards:src.parseCards(b).length,unescapedCards:src.parseCards(src.unesc(b)).length,
                                    contentType:r.headers?.['content-type'] || r.headers?.['Content-Type'] || ''};
                            })()
                        """.trimIndent()
                        row.put("searchDiagnostic", JSONObject(engine.call(probe)!!))
                    }
                    books = stage("searchFallback") { value(source.search("naruto")) }
                    row.put("fallbackQuery", "naruto")
                }
                row.put("searchCount", books.size).put("searchStatus", "ok")
                if (books.isEmpty()) {
                    if (key == "ikmmh" && source is JsComicSource) {
                        val engine = JsComicSource::class.java.getDeclaredField("engine")
                            .apply { isAccessible = true }.get(source) as JsSourceEngine
                        val diagnostic = engine.call("""
                            (async()=>{
                                const r=await Network.get(src.constructor.baseUrl+'/search?searchkey=%E6%96%97%E7%A0%B4',src.constructor.webHeaders);
                                const d=new HtmlDocument(r.body || '');
                                return {status:r.status,length:(r.body || '').length,
                                    title:d.querySelector('title')?.text || '',
                                    cards:d.querySelectorAll('li.comic-item, div.classification').length,
                                    visibleText:d.querySelector('body')?.text?.slice(0,120)||'',
                                    cookieAssignment:(r.body||'').includes('document.cookie'),
                                    redirect:(r.body||'').includes('location'),
                                    shortDiagnostic:(r.body||'').length<=200 ? (r.body||'').replace(/[A-Za-z0-9_=.-]{16,}/g,'[redacted]') : '',
                                    firstChar:(r.body||'').charCodeAt(0)||0};
                            })()
                        """.trimIndent())
                        row.put("emptySearchDiagnostic", JSONObject(diagnostic!!))
                    }
                    row.put("status", "search_empty").put("readingStatus", "not_tested")
                } else {
                    val attempts = JSONArray()
                    var chapter: com.example.source.ComicChapter? = null
                    var selectedBook: com.example.source.SearchBook? = null
                    var selectedPages: List<String>? = null
                    val sampleSmallGallery = download && key in setOf("ehentai", "hitomi")
                    val mobilePufei = key == "pufei" && InstrumentationRegistry.getArguments().getString("detailVariant") == "mobile"
                    if (mobilePufei) row.put("detailVariant", "mobile")
                    val candidates = books.take(if (sampleSmallGallery) 6 else 3).map {
                        if (mobilePufei) it.copy(id = it.id.replace("https://www.pufeimh.com/", "https://m.pufeimh.com/")) else it
                    }
                    for ((index, book) in candidates.withIndex()) {
                        try {
                            val chapters = stage("chapters") { value(source.getChapters(book.id)) }
                            attempts.put(JSONObject().put("count", chapters.size))
                            chapter = if (key == "pufei" && auditQuery.isEmpty()) chapters.lastOrNull { it.title == "456" && !it.external }
                                else chapters.firstOrNull { !it.external }
                            val candidateChapter = chapter
                            if (candidateChapter != null) {
                                if (sampleSmallGallery) {
                                    val candidatePages = stage("pages") { value(source.getChapterImages(candidateChapter.id)) }
                                    if (candidatePages.size > 60 && index < candidates.lastIndex) {
                                        attempts.put(JSONObject().put("pageCount", candidatePages.size)
                                            .put("skipped", "Choose a smaller complete gallery for download audit"))
                                        chapter = null
                                        continue
                                    }
                                    selectedPages = candidatePages
                                }
                                selectedBook = book
                                row.put("chapterCount", chapters.size)
                                break
                            }
                        } catch (e: Exception) {
                            attempts.put(JSONObject().put("error", e.message))
                            if (e is TimeoutCancellationException || e === com.example.source.SourceException.LoginRequired) break
                        }
                    }
                    row.put("chapterAttempts", attempts)
                    val readableChapter = chapter ?: error("No hosted chapters found in three search results: $attempts")
                    val pages = selectedPages ?: stage("pages") { value(source.getChapterImages(readableChapter.id)) }
                    if (key == "pufei") stage("cachedPages") {
                        check(value(source.getChapterImages(readableChapter.id)) == pages) { "Cached chapter pages changed" }
                    }
                    row.put("pageCount", pages.size)
                    val url = pages.firstOrNull() ?: error("Chapter returned no images")
                    val headers = stage("headers") { source.getChapterImageHeaders(readableChapter.id, listOf(url)) }
                    val resolved = stage("resolve") { source.resolveChapterImage(url) } ?: url
                    row.put("firstImageHost", android.net.Uri.parse(resolved).host)
                    val imageHeaders = headers[url].orEmpty() + source.getResolvedHeaders(resolved)
                    val bitmap = stage("firstImage") { loader.load(
                        ComicPageRef.Remote("audit:$key", resolved, imageHeaders), "audit:$key",
                        ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()
                    ).bitmap }
                    row.put("width", bitmap.width).put("height", bitmap.height)
                        .put("enhancement", "OFF")
                    var visibleSamples = 0
                    for (y in 0 until 16) for (x in 0 until 16) {
                        val pixel = bitmap.getPixel(x * (bitmap.width - 1) / 15, y * (bitmap.height - 1) / 15)
                        if (pixel and 0x00ffffff > 0x000c0c0c) visibleSamples++
                    }
                    row.put("nonBlackSamples", visibleSamples)
                        .put("status", if (visibleSamples > 0) "reading_ok" else "image_black")
                    if (imageSamples > 1) {
                        val dimensions = JSONArray().put("${bitmap.width}x${bitmap.height}")
                        for ((sampleIndex, sampleUrl) in pages.take(imageSamples).withIndex()) {
                            if (sampleIndex == 0) continue
                            stage("sampleImage${sampleIndex + 1}") {
                                val sampleHeaders = source.getChapterImageHeaders(readableChapter.id, listOf(sampleUrl))
                                val sampleResolved = source.resolveChapterImage(sampleUrl) ?: sampleUrl
                                val sample = loader.load(ComicPageRef.Remote("audit:$key:$sampleIndex", sampleResolved,
                                    sampleHeaders[sampleUrl].orEmpty() + source.getResolvedHeaders(sampleResolved)),
                                    "audit:$key:$sampleIndex", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()).bitmap
                                check(sample.width > 0 && sample.height > 0)
                                dimensions.put("${sample.width}x${sample.height}")
                            }
                        }
                        row.put("sampleDimensions", dimensions).put("sampleBook", selectedBook?.title)
                            .put("sampleChapter", readableChapter.title)
                    }
                    if (key == "pufei" && pages.size > 55) {
                        val recoveryUrl = pages[55]
                        val recoveryHeaders = source.getChapterImageHeaders(readableChapter.id, listOf(recoveryUrl))
                        val recovered = stage("cachedErrorImage") { loader.load(
                            ComicPageRef.Remote("audit:pufei:56", recoveryUrl, recoveryHeaders[recoveryUrl].orEmpty()),
                            "audit:pufei:56", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()
                        ).bitmap }
                        row.put("cachedErrorImageWidth", recovered.width).put("cachedErrorImageHeight", recovered.height)
                    }
                    if (download && visibleSamples > 0) {
                        val book = requireNotNull(selectedBook).let {
                            // A fragment keeps the public comic URL valid while giving this
                            // owned audit a separate download identity and empty directory.
                            if (key == "pufei" && InstrumentationRegistry.getArguments().getString("freshDownload") == "true") {
                                row.put("freshDownload", true)
                                it.copy(id = it.id + "#audit-" + java.util.UUID.randomUUID())
                            } else it
                        }
                        stage("download", 180_000) {
                            val application = context.applicationContext as android.app.Application
                            val database = AppDatabase.getDatabase(context)
                            val id = ComicDownloadManager.taskId(book, readableChapter.id)
                            val previousTask = ComicDownloadManager.tasks.value[id]
                            ComicDownloadManager.start(application, database, book, readableChapter, source)
                            val terminal = try {
                                withTimeout(175_000) {
                                    ComicDownloadManager.tasks.first { tasks ->
                                        tasks[id] !== previousTask &&
                                            tasks[id]?.status in setOf(ComicDownloadStatus.SUCCESS, ComicDownloadStatus.FAILED)
                                    }.getValue(id)
                                }
                            } catch (e: CancellationException) {
                                ComicDownloadManager.pause(readableChapter.id, book)
                                throw e
                            }
                            check(terminal.status == ComicDownloadStatus.SUCCESS) {
                                terminal.error ?: "Background chapter download failed"
                            }
                            val directory = ComicDownloadManager.directory(context, id)
                            val localBook = database.bookDao().getBookByFilePath(directory.absolutePath)
                                ?: error("Completed download was not registered in bookshelf")
                            val localPages = database.bookDao().getChaptersListForBook(localBook.id)
                            assertEquals(pages.size, localPages.size)
                            localPages.forEach { page ->
                                val file = File(page.content)
                                assertTrue("Missing local page", file.isFile && file.length() > 0)
                                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                                assertTrue("Invalid downloaded image", bounds.outWidth > 0 && bounds.outHeight > 0)
                            }
                            // Local references use the offline reader and cannot issue an HTTP request.
                            val offlineLoader = ComicPageLoader(context)
                            try {
                                listOf(0, localPages.size / 2, localPages.lastIndex).distinct().forEach { index ->
                                    val page = localPages[index]
                                    val localBitmap = offlineLoader.load(
                                        ComicPageRef.Local("offline:$id:$index", page.content), "offline:$id:$index",
                                        ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()
                                    ).bitmap
                                    assertTrue(localBitmap.width > 0 && localBitmap.height > 0)
                                }
                            } finally { offlineLoader.shutdown() }
                            row.put("downloadStatus", "ok").put("downloadedPages", localPages.size)
                                .put("offlineStatus", "ok").put("status", "download_and_offline_ok")
                        }
                    }
                }
            } catch (e: Exception) {
                row.put("status", "failed").put("error", (e.message ?: e.javaClass.simpleName).take(400))
                if (key == "ikmmh" && row.optString("stage") == "search" && source is JsComicSource) {
                    runCatching {
                        val engine = JsComicSource::class.java.getDeclaredField("engine")
                            .apply { isAccessible = true }.get(source) as JsSourceEngine
                        val diagnostic = engine.call("""
                            (async()=>{
                                const r=await Network.get(Ikm.baseUrl+'/search?searchkey='+encodeURIComponent('斗破'),Ikm.webHeaders);
                                return {status:r.status,length:String(r.body||'').length,
                                    preview:String(r.body||'').length<200?String(r.body||'').replace(/[A-Za-z0-9_=.-]{16,}/g,'[redacted]'):'',
                                    cookies:(await Network.getCookies(Ikm.baseUrl)).map(c=>c.name)};
                            })()
                        """.trimIndent())
                        row.put("searchDiagnostic", JSONObject(diagnostic!!))
                    }
                }
            } finally {
                loader.shutdown()
                if (source is JsComicSource) runCatching {
                    val engine = JsComicSource::class.java.getDeclaredField("engine")
                        .apply { isAccessible = true }.get(source) as? JsSourceEngine
                    val meta = engine?.call("src.baseUrl || src.constructor.baseUrl || null")
                    if (meta != null) row.put("baseUrl", JSONObject(meta).opt("data"))
                }
                row.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                record(row)
            }
        } } }.awaitAll()
        record(JSONObject().put("summary", true).put("remoteExpected", expected.size)
            .put("installedJs", installed.size).put("attempted", sources.size))
        // Completion of the audit is separate from availability of the individual websites.
        assertEquals(expected.size + 3, byKey.size + expected.count { it.key !in byKey })
    }
}
