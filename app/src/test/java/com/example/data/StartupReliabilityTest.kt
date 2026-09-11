package com.example.data

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StartupReliabilityTest {

    private lateinit var repository: MediaRepository
    private val mockContext: Context = mock()

    @Before
    fun setUp() {
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        val mockPrefs: android.content.SharedPreferences = mock()
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockPrefs)
        // Use a new instance with a controlled dispatcher for testing
        repository = MediaRepository(kotlinx.coroutines.test.StandardTestDispatcher())
        repository.resetTestingState()
    }

    @Test
    fun testInitializationOwner_MultipleCalls_OneJob() = runTest {
        // First call starts initialization
        repository.initDatabase(mockContext)
        
        // We use StandardTestDispatcher so we can check state BEFORE the coroutine runs
        assertEquals(DatabaseState.INITIALIZING, repository.databaseState.value)

        // Second call should return immediately due to synchronized and initJob check
        repository.initDatabase(mockContext)
        assertEquals(DatabaseState.INITIALIZING, repository.databaseState.value)
    }

    @Test
    fun testDatabaseVsAI_ReadinessSeparation() = runTest {
        assertEquals(AIState.NOT_INITIALIZED, repository.aiState.value)
        assertEquals(DatabaseState.NOT_INITIALIZED, repository.databaseState.value)
    }

    @Test
    fun testStartupWatchdog_TriggersTimeout() = runTest {
        repository.initDatabase(mockContext)
        advanceTimeBy(11.seconds)
        // If it didn't finish, it should hit TIMEOUT or TRANSITION_FAILED
        val state = repository.databaseState.value
        assertTrue(state == DatabaseState.TIMEOUT || state == DatabaseState.TRANSITION_FAILED)
    }
}
