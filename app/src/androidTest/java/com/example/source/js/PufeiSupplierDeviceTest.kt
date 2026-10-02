package com.example.source.js

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class PufeiSupplierDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun engine() = JsSourceEngine(
        context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
        context.assets.open("js_extra/pufei.js").bufferedReader().use { it.readText() }, "pufei_supplier_fixture", context)
    private suspend fun data(code: String): JSONObject {
        val raw = engine().call(code)!!
        val result = JSONObject(raw)
        assertTrue(raw, result.getBoolean("ok"))
        return result.getJSONObject("data")
    }

    @Test fun currentPublicReaderPayloadDecodesInQuickJsWithoutRemoteCode() = runBlocking {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("pufei-supplier/first-images.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val prefs = context.getSharedPreferences("js_source_data", Context.MODE_PRIVATE)
        val key = "data_pufei_supplier_fixture_public_payload"
        prefs.edit().putString(key, fixture.getString("images")).commit()
        try {
            val result = data("""
                (()=>{
                    const images=src.decodeSupplierImages(src.loadData('public_payload'));
                    return {count:images.length,first:images[0].url,last:images[images.length-1].url,
                        malformed:src.decodeSupplierImages('J7rbrokenmarkersnQ')===null,
                        oversized:src.decodeSupplierImages('J7r'+'A'.repeat(1400000)+'nQ')===null};
                })()
            """.trimIndent())
            assertEquals(185, result.getInt("count"))
            val base = "/scomic/wojialaopolaiziyiqiannianqian-yuewenmanhua/0/2-rolu/"
            assertEquals(base + "1.webp", result.getString("first"))
            assertEquals(base + "185.webp", result.getString("last"))
            assertTrue(result.getBoolean("malformed"))
            assertTrue(result.getBoolean("oversized"))
        } finally { prefs.edit().remove(key).commit() }
    }

    @Test fun supplierCatalogueRequestsAllChaptersAndPreservesDistinctChapterCacheKeys() = runBlocking {
        val result = data("""
            (async()=>{
                let requested='';src.html=async()=>'<div id="mangachapters" data-mid="2304"></div>';
                src.supplierJson=async(path)=>{requested=path;return {title:'火影忍者',chapters:
                    Array.from({length:40},(_,i)=>({id:String(i+1),attributes:{title:'第'+(i+1)+'话'}}))}};
                const details=await src.supplierDetails('huoyingrenzhe-anbenqishi');
                const chapters=Object.keys(details.chapters);
                return {requested,count:chapters.length,distinct:src.pageKey(chapters[0])!==src.pageKey(chapters[1])};
            })()
        """.trimIndent())
        assertEquals("manga/get?mid=2304&mode=all", result.getString("requested"))
        assertEquals(40, result.getInt("count"))
        assertTrue(result.getBoolean("distinct"))
    }

    @Test fun supplierLineSelectionUsesThePublishedCdnRatherThanAnOldHardcodedLine() = runBlocking {
        val result = data("""
            (async()=>{
                src.supplierJson=async()=>({info:{images:{line:2,images:[{order:1,url:'/hp/test/1.webp'}]}}});
                const images=await src.supplierPages('https://v2.apikk.top/api/v2/chapter/getinfo?m=1&c=2');
                let rejected=false;src.supplierJson=async()=>({info:{images:{images:[{url:'//outside.example/1'}]}}});
                try{await src.supplierPages('https://v2.apikk.top/api/v2/chapter/getinfo?m=1&c=2')}catch(e){rejected=true}
                return {first:images[0],rejected};
            })()
        """.trimIndent())
        assertEquals("https://c-nd2-1.6wm.top/hp/test/1.webp", result.getString("first"))
        assertTrue(result.getBoolean("rejected"))
    }

    @Test fun duplicatePublicUpstreamsAreRetainedAndZeroChapterRowsAreNotOfferedAsReadableBooks() = runBlocking {
        val result = data("""
            (()=>{
                const cache={};src.loadData=k=>cache[k];src.saveData=(k,v)=>{cache[k]=v};
                const rows=src.compact([
                    {name:'海贼王',url:'/comic/haizeiwang',chapter_url:'/chapter/1-2.html',source_url:'http://bz.mh.com/comic/haizeiwang-weitianrongyilang',chapter_nums:'100'},
                    {name:'海贼王',url:'/comic/haizeiwang',chapter_url:'/chapter/2-3.html',chapter_nums:'100'},
                    {name:'海贼王',url:'/comic/haizeiwang_old',chapter_url:'/chapter/3-4.html',chapter_nums:'100'},
                    {name:'海贼王资料集',url:'/comic/empty',chapter_nums:'0'}]);
                const cards=src.matches(rows,'海贼');
                return {cards:cards.length,providers:cache.book_links_v4.map(v=>v.supplier)};
            })()
        """.trimIndent())
        assertEquals(2, result.getInt("cards"))
        repeat(2) { assertEquals("haizeiwang-weitianrongyilang", result.getJSONArray("providers").getString(it)) }
    }

    @Test fun publishedAliasesKeepOldAndColourEditionsSeparate() = runBlocking {
        val result = data("""
            (()=>{
                const cache={};src.loadData=k=>cache[k];src.saveData=(k,v)=>{cache[k]=v};
                src.rememberSearchLinks([
                    ['斗破苍穹(旧)','/comic/old','','作者','/chapter/1-2.html','http://goda.mh.com/manga/old-edition'],
                    ['斗破苍穹','/comic/normal','','作者','/chapter/2-3.html','http://goda.mh.com/manga/new-edition'],
                    ['斗破苍穹(旧版)','/comic/old-alias','','作者','/chapter/3-4.html',''],
                    ['斗破苍穹【927后搜：斗破苍穹衔接版】','/comic/promo','','','/chapter/4-5.html',''],
                    ['斗破苍穹（全彩版）','/comic/colour','','作者','/chapter/5-6.html','']]);
                return {providers:cache.book_links_v4.map(v=>v.supplier||''),
                    pirate:src.workName('航海王（海贼王）')===src.workName('海贼王')};
            })()
        """.trimIndent())
        val providers = result.getJSONArray("providers")
        assertEquals("old-edition", providers.getString(2))
        assertEquals("old-edition", providers.getString(3))
        assertEquals("", providers.getString(4))
        assertTrue(result.getBoolean("pirate"))
    }

    @Test fun movedSupplierSlugIsResolvedByExactTitleAndThePositiveResolutionIsCached() = runBlocking {
        val result = data("""
            (async()=>{
                const cache={};src.loadData=k=>cache[k];src.saveData=(k,v)=>{cache[k]=v};
                const calls=[];let searches=0;
                src.request=async(url)=>{
                    if(url.endsWith('/manga/obsolete')){calls.push('obsolete');return {status:404,body:'not found'}}
                    if(url.endsWith('/manga/current')){calls.push('current');return {status:200,body:'<div id="mangachapters" data-mid="226"></div>'}}
                    if(!url.includes('/s/'))throw Error('Unrelated work selected');
                    searches++;return {status:200,body:'<a href="/manga/unrelated"><h3 class="cardtitle">海贼王同人</h3></a><a href="/manga/current"><h3 class="cardtitle">航海王</h3></a>'};
                };
                src.supplierJson=async()=>({title:'航海王',chapters:[{id:1,attributes:{title:'第一话'}}]});
                const hint={title:'海贼王',supplier:'obsolete'};
                await src.currentSupplier(hint);await src.currentSupplier(hint);
                return {searches,calls:calls.join(',')};
            })()
        """.trimIndent())
        assertEquals(1, result.getInt("searches"))
        assertEquals("obsolete,current,current", result.getString("calls"))
    }

    @Test fun verifiedMirrorRetainsChapterIdsAndReportsItsActualReadingLine() = runBlocking {
        val result = data("""
            (async()=>{
                const cache={};src.loadData=k=>cache[k];src.saveData=(k,v)=>{cache[k]=v};
                src.rememberSearchLinks([['火影忍者同人鼬神传','/comic/huoyingrenzhetongrenyoushenchuan','','郑明',
                    '/chapter/531967-178058.html','http://dmw.mh.com/TRxUNNH/']]);
                src.html=async(url)=>url.startsWith('https://www.guoman.net/')?
                    '<p class="detail-info-title">火影忍者同人鼬神传</p><script src="/api/hits/comic/531967"></script><div id="chapterlistload"><a href="/chapter/531967-178058.html">第一话：中忍考试</a><a href="/chapter/531967-178059.html">第二话：利器</a></div>':null;
                const d=await src.comic.loadInfo('/comic/huoyingrenzhetongrenyoushenchuan');
                return {first:Object.keys(d.chapters)[0],count:Object.keys(d.chapters).length,line:d.tags['阅读线路'][0]};
            })()
        """.trimIndent())
        assertEquals(2, result.getInt("count"))
        assertEquals("https://www.guoman.net/chapter/531967-178058.html", result.getString("first"))
        assertEquals("爱国漫（备用公开线路）", result.getString("line"))
    }

    @Test fun publicMirrorHtmlUsesTheSameOriginRefererAsItsReader() = runBlocking {
        val result = data("""
            (async()=>{
                const refs=[];src.request=async(url,headers)=>{refs.push(headers.Referer);return {status:200,body:'reader'}};
                await src.html('https://www.guoman.net/chapter/531967-178059.html');
                await src.html('https://www.pufeimh.com/chapter/1-2.html');
                return {mirror:refs[0],pufei:refs[1]};
            })()
        """.trimIndent())
        assertEquals("https://www.guoman.net/", result.getString("mirror"))
        assertEquals("https://m.pufeimh.com/category", result.getString("pufei"))
    }

    @Test fun mirrorDesktopFailureFallsBackToItsPublicMobileChapterWithoutChangingTheId() = runBlocking {
        val result=data("""
            (async()=>{
                src.loadData=()=>null;src.saveData=()=>{};
                const calls=[];src.request=async(url,headers)=>{
                    calls.push({url,ref:headers.Referer});
                    return url.startsWith('https://m.guoman.net/')?{status:200,body:'valid mobile reader'}:null;
                };
                src.parseImages=text=>text==='valid mobile reader'?['https://dmw.546457.xyz/fixture.webp']:null;
                const pages=await src.comic.loadEp('/comic/fixture','https://www.guoman.net/chapter/531967-178059.html');
                return {calls,pages:pages.images.length,unrelated:src.alternatePage('https://outside.example/chapter/1-2.html')};
            })()
        """.trimIndent())
        assertEquals(1,result.getInt("pages"))
        assertEquals("https://m.guoman.net/chapter/531967-178059.html",result.getJSONArray("calls").getJSONObject(1).getString("url"))
        assertEquals("https://www.guoman.net/",result.getJSONArray("calls").getJSONObject(1).getString("ref"))
        assertTrue(result.isNull("unrelated"))
    }

    @Test fun mirrorCannotReturnADifferentBookOrChangeAnotherImagesReferer() = runBlocking {
        val rejected = JSONObject(engine().call("""
            (async()=>{
                const cache={};src.loadData=k=>cache[k];src.saveData=(k,v)=>{cache[k]=v};
                src.rememberSearchLinks([['正确作品','/comic/mirror-fixture','','作者','/chapter/1-2.html','http://dmw.mh.com/fixture/']]);
                src.publicJson=async()=>null;
                src.html=async(url)=>url.startsWith('https://www.guoman.net/')?
                    '<p class="detail-info-title">其他作品</p><div id="chapterlistload"><a href="/chapter/1-2.html">第一话</a></div>':null;
                return await src.comic.loadInfo('/comic/mirror-fixture');
            })()
        """.trimIndent())!!)
        assertFalse(rejected.toString(), rejected.getBoolean("ok"))
        assertTrue(rejected.toString(), rejected.optString("error").contains("没有返回完整漫画详情"))
        val result = data("""
            (()=>{
                const headers=url=>src.comic.onImageLoad(url).headers;
                return {mirror:headers('https://dmw.546457.xyz/46/58/fixture.webp').Referer,
                    original:headers('https://c-nd3-1.6wm.top/hp/fixture.webp').Referer,
                    unrelated:headers('https://outside.example/fixture.webp').Referer};
            })()
        """.trimIndent())
        assertEquals("https://www.guoman.net/", result.getString("mirror"))
        assertEquals("https://manhuafree.com/", result.getString("original"))
        assertEquals("https://www.pufeimh.com/", result.getString("unrelated"))
    }

    @Test fun migratedPublicSupplierRequiresExactTitleAndCompleteFreeCatalogue() = runBlocking {
        val result = data("""
            (async()=>{
                const cache={};src.loadData=k=>cache[k];src.saveData=(k,v)=>{cache[k]=v};
                const calls=[];let restricted=false;
                src.publicJson=async(path)=>{
                    calls.push(path);
                    if(path.startsWith('search?'))return {data:{list:[{id:1,title:'一人之下同人'},{id:1728,title:'一人之下'}]}};
                    if(path==='comic/1728')return {data:{id:1728,title:'一人之下',cover:'cover',intro:'简介'}};
                    if(path.startsWith('comic/chapter?'))return {pagination:{total:201},data:
                        path.includes('&page=1&')?Array.from({length:200},(_,i)=>({id:i+1,title:'第'+i+'话',comicId:1728,isVip:false})):
                            [{id:201,title:'末章',comicId:1728,isVip:restricted}]};
                    return null;
                };
                const details=await src.publicDetails({title:'一人之下'});
                const searches=calls.filter(v=>v.startsWith('search?')).length;
                restricted=true;const denied=await src.publicDetails({title:'一人之下'});
                return {count:Object.keys(details.chapters).length,last:Object.keys(details.chapters)[200],
                    denied:denied===null,searches,cachedSearches:calls.filter(v=>v.startsWith('search?')).length};
            })()
        """.trimIndent())
        assertEquals(201, result.getInt("count"))
        assertEquals("https://manwaxu.cc/api/comic/image/201", result.getString("last"))
        assertTrue(result.getBoolean("denied"))
        assertEquals(1, result.getInt("searches"))
        assertEquals(1, result.getInt("cachedSearches"))
    }

    @Test fun publicImagePaginationReturnsEveryPageWithDistinctChapterKeys() = runBlocking {
        val result = data("""
            (async()=>{
                src.loadData=()=>null;src.saveData=()=>{};
                const calls=[];src.publicJson=async(path)=>{
                    calls.push(path);const second=path.includes('?page=2&');
                    return {data:{pagination:{total:26},images:Array.from({length:second?1:25},(_,i)=>({
                        url:'https://tu.mhttu.cc/en_images/fixture/'+(second?25:i)+'.jpg'}))}};
                };
                const pages=await src.comic.loadEp('/comic/fixture','https://manwaxu.cc/api/comic/image/701560');
                return {count:pages.images.length,calls,last:pages.images[25],
                    separate:src.pageKey('https://manwaxu.cc/api/comic/image/1')!==src.pageKey('https://manwaxu.cc/api/comic/image/2'),
                    ref:src.comic.onImageLoad(pages.images[0]).headers.Referer};
            })()
        """.trimIndent())
        assertEquals(26, result.getInt("count"))
        assertEquals(2, result.getJSONArray("calls").length())
        assertTrue(result.getJSONArray("calls").getString(0).contains("page_size=200"))
        assertEquals("https://tu.mhttu.cc/en_images/fixture/25.jpg", result.getString("last"))
        assertTrue(result.getBoolean("separate"))
        assertEquals("https://manwaxu.cc/", result.getString("ref"))
    }

    @Test fun retiredManwaIdsResolveUsingThePublicExactTitleWithoutDroppingOriginalCard() = runBlocking {
        val result = data("""
            (async()=>{
                const cache={};src.loadData=k=>cache[k];src.saveData=(k,v)=>{cache[k]=v};
                src.rememberSearchLinks([['火影同人-情人节的故事','/comic/fan','cover','作者',
                    '/chapter/557677-189395.html','http://manwa.mh.com/book/358947']]);
                src.html=async()=>null;let searched=false;
                src.publicJson=async(path)=>{
                    if(path.startsWith('search?')){searched=true;return {data:{list:[{id:17727,title:'火影同人-情人节的故事'}]}}}
                    if(path==='comic/17727')return {data:{id:17727,title:'火影同人-情人节的故事',cover:'encrypted-supplier-cover'}};
                    if(path.startsWith('comic/chapter?'))return {pagination:{total:1},data:[{id:701560,title:'短篇',comicId:17727}]};
                    return null;
                };
                const details=await src.comic.loadInfo('/comic/fan');
                return {searched,chapter:Object.keys(details.chapters)[0],cover:details.cover,
                    retained:cache.book_links_v4[0].key==='/comic/fan'};
            })()
        """.trimIndent())
        assertTrue(result.getBoolean("searched"))
        assertTrue(result.getBoolean("retained"))
        assertEquals("cover", result.getString("cover"))
        assertEquals("https://manwaxu.cc/api/comic/image/701560", result.getString("chapter"))
    }

    @Test fun structuredTransportErrorsRemainRecoverableWithoutRejectedPromises() = runBlocking {
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
        val reply = async(Dispatchers.IO) {
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) Unit
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: 10\r\nConnection: close\r\n\r\nnext entry".toByteArray())
                    flush()
                }
            }
        }
        try {
            val result = data("""
                (async()=>{
                    const failed=await src.request('http://127.0.0.1:$closedPort/old',{});
                    const next=await src.request('http://127.0.0.1:${server.localPort}/current',{});
                    return {recovered:failed===null&&next?.status===200&&next.body==='next entry'};
                })()
            """.trimIndent())
            assertTrue(result.toString(), result.getBoolean("recovered"))
            reply.await()
        } finally { server.close(); reply.cancelAndJoin() }
        Unit
    }
}
