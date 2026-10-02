package com.example.source.zlibrary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZLibraryDomainResolverTest {

    private lateinit var context: Context
    private lateinit var credentialStorage: ZLibraryCredentialStorage
    private lateinit var endpointProvider: ZLibraryEndpointProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        credentialStorage = ZLibraryCredentialStorage(context.getSharedPreferences("credential-test", android.content.Context.MODE_PRIVATE))
        endpointProvider = ZLibraryEndpointProvider(context, credentialStorage)
        endpointProvider.invalidateCache()
    }

    @Test
    fun testCacheValidReturnsCachedEndpoint() = runTest {
        // 2026-09-04 容灾语义更新：缓存节点先做快速体检——可达（有任何 HTTP 应答，
        // example.com 稳定返回 200）才原样复用，避免死节点直接返回卡死搜索。
        val testEndpoint = "example.com"
        endpointProvider.saveCache(testEndpoint)

        val resolved = endpointProvider.getEndpoint()
        assertEquals(testEndpoint, resolved)

        // 体检不通的缓存节点（挂了/被墙/根本不存在）不再原样返回：
        // 自动作废重扫换活节点；离线环境下扫描全挂也兜底预置首域，均不等于死域名。
        endpointProvider.invalidateCache()
        endpointProvider.saveCache("dead-zlib.invalid")
        val resolvedAfter = endpointProvider.getEndpoint()
        assertNotEquals("dead-zlib.invalid", resolvedAfter)
        assertNotNull(resolvedAfter)
    }

    @Test
    fun testCustomEndpointTakesPrecedence() = runTest {
        // 2026-09-04 容灾语义更新：自定义节点同样先体检，可达才优先返回。
        val custom = "example.com"
        endpointProvider.setCustomEndpoint(custom)

        val resolved = endpointProvider.getEndpoint()
        assertEquals(custom, resolved)
        endpointProvider.setCustomEndpoint(null)
    }
}
