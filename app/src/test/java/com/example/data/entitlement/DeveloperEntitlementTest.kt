package com.example.data.entitlement

import com.example.data.db.UserPreferenceDao
import com.example.data.db.UserPreferenceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class DeveloperEntitlementTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val mockDao: UserPreferenceDao = mock()
    private lateinit var repository: EntitlementRepository

    @Before
    fun setup() = runBlocking {
        whenever(mockDao.getPreference("aura_pro_status_v1")).thenReturn(null)
        repository = EntitlementRepository(mockDao, testScope)
    }

    @Test
    fun testDeveloperControl_EnablesProStateAndFeatureAvailability() = runTest(testDispatcher) {
        // Pre-condition: Initial state is FREE
        assertFalse(repository.proState.value.isPro)
        assertFalse(repository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION))

        // Action: Developer control activates Enable Aura Pro
        repository.updateEntitlement(true)

        // Verification: ProState is Pro and CLEANUP_AUTOMATION feature becomes available
        assertTrue(repository.proState.value.isPro)
        assertTrue(repository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION))
    }

    @Test
    fun testDeveloperControl_DisablesProStateAndFeatureAvailability() = runTest(testDispatcher) {
        // Pre-condition: Enable Pro
        repository.updateEntitlement(true)
        assertTrue(repository.proState.value.isPro)

        // Action: Developer control deactivates Pro
        repository.updateEntitlement(false)

        // Verification: ProState returns to Free and CLEANUP_AUTOMATION is locked
        assertFalse(repository.proState.value.isPro)
        assertFalse(repository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION))
    }

    @Test
    fun testDeveloperControl_PersistsStateToUserPreferences() = runTest(testDispatcher) {
        // Action: Developer control enables Pro
        repository.updateEntitlement(true)

        // Verification: EntitlementRepository persists "PRO" to user_preferences
        verify(mockDao).insertPreference(argThat { key == "aura_pro_status_v1" && value == "PRO" })
    }
}
