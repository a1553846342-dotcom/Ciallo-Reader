package com.example.source

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.source.storage.SharedPreferencesSourceStorage
import com.example.source.storage.SourceStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class FakeSourceStorage : SourceStorage {
    private val enabledStates = mutableMapOf<String, Boolean>()
    private var activeSourceId: String? = null
    private val customJsons = mutableMapOf<String, String>()

    override suspend fun saveSourceState(sourceId: String, enabled: Boolean) {
        enabledStates[sourceId] = enabled
    }

    override suspend fun getSourceStates(): Map<String, Boolean> = enabledStates

    override suspend fun saveActiveSourceId(sourceId: String) {
        activeSourceId = sourceId
    }

    override suspend fun getActiveSourceId(): String? = activeSourceId

    override suspend fun saveCustomSourceJson(sourceId: String, jsonContent: String) {
        customJsons[sourceId] = jsonContent
    }

    override suspend fun getCustomSourceJsons(): Map<String, String> = customJsons

    override suspend fun removeCustomSourceJson(sourceId: String) {
        customJsons.remove(sourceId)
        enabledStates.remove(sourceId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SourceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }


    @Test
    fun testImportAndManageSourcesViaViewModel() = runTest(testDispatcher) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val storage = FakeSourceStorage()
        val sourceManager = SourceManager(storage)
        sourceManager.initialize()
        val viewModel = SourceViewModel(application, sourceManager)

        // Wait for asynchronous initialization to complete
        kotlinx.coroutines.delay(100)

        // 1. Import JSON source string
        val validJson = """
            {
                "id": "vm_test_source",
                "name": "ViewModel测试书源",
                "baseUrl": "https://example.org",
                "search": {
                    "url": "https://example.org/search?q={keyword}",
                    "listPath": "results"
                }
            }
        """.trimIndent()

        viewModel.importSourceFromJsonString(validJson)
        
        // Verify source added with async wait
        var allSources: List<BookSource> = emptyList()
        for (i in 1..20) {
            allSources = viewModel.allSources.value
            if (allSources.any { it.id == "vm_test_source" }) break
            kotlinx.coroutines.delay(50)
        }
        assertTrue(allSources.any { it.id == "vm_test_source" })
        assertTrue(viewModel.importStatus.value?.contains("成功导入 1 个书源") == true)

        // 2. Set as active source
        viewModel.setActiveSource("vm_test_source")
        var activeSourceId: String? = null
        for (i in 1..20) {
            activeSourceId = viewModel.activeSource.value?.id
            if (activeSourceId == "vm_test_source") break
            kotlinx.coroutines.delay(50)
        }
        assertEquals("vm_test_source", activeSourceId)

        // 3. Disable source
        viewModel.disableSource("vm_test_source")
        var isEnabled = true
        for (i in 1..20) {
            isEnabled = viewModel.isSourceEnabled("vm_test_source")
            if (!isEnabled) break
            kotlinx.coroutines.delay(50)
        }
        assertFalse(isEnabled)

        // 4. Delete custom source
        viewModel.removeSource("vm_test_source")
        var containsVmTestSource = true
        for (i in 1..20) {
            containsVmTestSource = viewModel.allSources.value.any { it.id == "vm_test_source" }
            if (!containsVmTestSource) break
            kotlinx.coroutines.delay(50)
        }
        assertFalse(containsVmTestSource)
    }
}
