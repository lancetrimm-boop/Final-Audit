package com.example.data.entitlement

import com.example.data.db.UserPreferenceDao
import com.example.data.db.UserPreferenceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class EntitlementRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val mockDao: UserPreferenceDao = mock()
    
    private lateinit var repository: EntitlementRepository

    @Before
    fun setup() {
    }

    @Test
    fun `TEST 1 - No persisted entitlement defaults to FREE`() = runBlocking {
        whenever(mockDao.getPreference(any())).thenReturn(null)
        
        repository = EntitlementRepository(mockDao, testScope)
        
        val state = repository.proState.value
        assertTrue("Expected Free state when no preference exists", state is ProState.Free)
    }

    @Test
    fun `TEST 2 - Persisted PRO state is loaded correctly`() = runBlocking {
        whenever(mockDao.getPreference("aura_pro_status_v1")).thenReturn(
            UserPreferenceEntity("aura_pro_status_v1", "PRO")
        )
        
        repository = EntitlementRepository(mockDao, testScope)
        
        val state = repository.proState.value
        assertTrue("Expected Pro state when persisted preference is PRO", state is ProState.Pro)
    }

    @Test
    fun `TEST 4-11 - Feature gating enforces entitlement correctly`() = runBlocking {
        whenever(mockDao.getPreference(any())).thenReturn(null)
        repository = EntitlementRepository(mockDao, testScope)
        
        // Initial state is FREE
        assertFalse(repository.isFeatureAvailable(ProFeature.ITERATIVE_VISUAL_SEARCH))
        assertFalse(repository.isFeatureAvailable(ProFeature.ADVANCED_HYBRID_SEARCH))
        assertFalse(repository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION))
        assertFalse(repository.isFeatureAvailable(ProFeature.MOMENTS_EXPORT))
        
        // Update to PRO
        repository.updateEntitlement(true)
        
        assertTrue(repository.isFeatureAvailable(ProFeature.ITERATIVE_VISUAL_SEARCH))
        assertTrue(repository.isFeatureAvailable(ProFeature.ADVANCED_HYBRID_SEARCH))
        assertTrue(repository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION))
        assertTrue(repository.isFeatureAvailable(ProFeature.MOMENTS_EXPORT))
    }

    @Test
    fun `TEST 12-13 - State Flow updates on entitlement change`() = runBlocking {
        whenever(mockDao.getPreference(any())).thenReturn(null)
        repository = EntitlementRepository(mockDao, testScope)
        
        assertFalse(repository.proState.value.isPro)
        
        repository.updateEntitlement(true)
        assertTrue(repository.proState.value.isPro)
        
        repository.updateEntitlement(false)
        assertFalse(repository.proState.value.isPro)
    }

    @Test
    fun `TEST 14 - Entitlement persists to DAO`() = runBlocking {
        whenever(mockDao.getPreference(any())).thenReturn(null)
        repository = EntitlementRepository(mockDao, testScope)
        
        repository.updateEntitlement(true)
        
        verify(mockDao).insertPreference(argThat { key == "aura_pro_status_v1" && value == "PRO" })
    }

    @Test
    fun `Access denied event is emitted for future paywall`() = runTest(testDispatcher) {
        whenever(mockDao.getPreference(any())).thenReturn(null)
        repository = EntitlementRepository(mockDao, testScope)
        
        val events = mutableListOf<ProFeature>()
        val job = launch {
            repository.accessDeniedEvents.collect { events.add(it) }
        }
        
        repository.isFeatureAvailable(ProFeature.MOMENTS_EXPORT)
        
        assertEquals(1, events.size)
        assertEquals(ProFeature.MOMENTS_EXPORT, events[0])
        
        job.cancel()
    }
}
