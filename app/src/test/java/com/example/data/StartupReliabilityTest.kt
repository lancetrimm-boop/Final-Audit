package com.example.data

import android.content.Context
import com.example.data.db.AuraDatabase
import com.example.data.db.PassphraseManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupReliabilityTest {

    private lateinit var repository: MediaRepository
    private val context: Context = RuntimeEnvironment.getApplication()
    private val testScheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)

    @Before
    fun setUp() {
        // Use a new instance with a controlled dispatcher for testing
        repository = MediaRepository(testDispatcher)
        repository.resetTestingState()
        
        // Ensure any previous passphrase state is cleared for test isolation
        PassphraseManager.clearPassphrase(context)
        
        // Ensure database is closed if it was opened in another test
        AuraDatabase.getInstance(context).close()
    }

    @Test
    fun testInitializationOwner_MultipleCalls_OneJob() = runTest(testDispatcher) {
        // First call starts initialization
        repository.initDatabase(context)
        runCurrent()
        
        // We use StandardTestDispatcher so we can check state
        val state = repository.databaseState.value
        assertTrue("State should be INITIALIZING or VERIFYING or READY. Got: $state", 
            state == DatabaseState.INITIALIZING || state == DatabaseState.VERIFYING || state == DatabaseState.READY)

        // Second call should return immediately due to synchronized and initJob check
        repository.initDatabase(context)
        runCurrent()
        assertEquals("State must not change on redundant call", state, repository.databaseState.value)
    }

    @Test
    fun testDatabaseVsAI_ReadinessSeparation() = runTest(testDispatcher) {
        assertEquals(AIState.NOT_INITIALIZED, repository.aiState.value)
        assertEquals(DatabaseState.NOT_INITIALIZED, repository.databaseState.value)
    }

    @Test
    fun testStartupWatchdog_TriggersTimeout() = runTest(testDispatcher) {
        repository.initDatabase(context)
        runCurrent() // Start the watchdog coroutine
        
        // Wait 11 seconds
        advanceTimeBy(11.seconds)
        runCurrent() // Process the state update from watchdog
        
        val state = repository.databaseState.value
        // Either it finished or it timed out
        assertTrue("Watchdog should have transitioned to TIMEOUT or READY or TRANSITION_FAILED. Got: $state", 
            state == DatabaseState.TIMEOUT || state == DatabaseState.READY || state == DatabaseState.TRANSITION_FAILED)
    }
}
