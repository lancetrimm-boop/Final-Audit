package com.example.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.*
import com.example.data.contribution.ConsentState
import com.example.data.intelligence.IntelligenceSnapshotReport
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileExportUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testExportRow_IsVisibleWithCorrectLabel() {
        val repository = mock<MediaRepository>()
        whenever(repository.tasteDNA).thenReturn(MutableStateFlow(TasteDNA()))
        whenever(repository.preferenceProfile).thenReturn(MutableStateFlow(TasteDNA.PreferenceProfile()))
        whenever(repository.discoveryPolicy).thenReturn(MutableStateFlow(DiscoveryPolicy()))
        whenever(repository.consentState).thenReturn(MutableStateFlow(ConsentState.NOT_DECIDED))
        whenever(repository.signatureStyleProfile).thenReturn(MutableStateFlow(com.example.data.intelligence.SignatureStyleProfile(emptyList(), emptyList())))
        whenever(repository.mediaItems).thenReturn(MutableStateFlow(emptyList()))
        whenever(repository.intelligenceStats).thenReturn(MutableStateFlow(IntelligenceStats()))
        
        val searchFeedbackRepository = mock<SearchFeedbackRepository>()
        whenever(repository.searchFeedbackRepository).thenReturn(searchFeedbackRepository)

        // Mocking IntelligenceRepository for DashboardViewModel
        val intelligenceRepository = mock<IntelligenceRepository>()
        whenever(repository.intelligenceRepository).thenReturn(intelligenceRepository)
        whenever(intelligenceRepository.getAllFindings()).thenReturn(MutableStateFlow(emptyList()))
        
        val masterReport = mock<MasterIntelligenceReport>()
        whenever(intelligenceRepository.getMasterReportFlow()).thenReturn(MutableStateFlow(masterReport))
        
        val decisionCenterState = mock<DecisionCenterState>()
        whenever(intelligenceRepository.getDecisionCenterFlow()).thenReturn(MutableStateFlow(decisionCenterState))
        
        whenever(intelligenceRepository.getAllIntelligenceEvents()).thenReturn(MutableStateFlow(emptyList()))
        whenever(intelligenceRepository.getAllAttentionItems()).thenReturn(MutableStateFlow(emptyList()))
        
        // Correcting property access for snapshotReport
        whenever(intelligenceRepository.snapshotReport).thenReturn(MutableStateFlow(null))

        // Mocking PlaybackErrorLogRepository for DiagnosticsViewModel
        val playbackRepository = mock<PlaybackErrorLogRepository>()
        whenever(repository.playbackErrorLogRepository).thenReturn(playbackRepository)
        whenever(playbackRepository.observeRecentErrors()).thenReturn(MutableStateFlow(emptyList()))

        composeTestRule.setContent {
            ProfileScreen(
                repository = repository,
                onNavigateToFavorites = {},
                onNavigateToCleanup = {},
                onNavigateToPrivacyPolicy = {},
                onNavigateToDiagnostics = {},
                onLaunchAuraMoments = {},
                onMediaSelect = { _, _ -> }
            )
        }

        // Verify the export row is present
        composeTestRule.onNodeWithText("Export Taste DNA").assertExists()
        composeTestRule.onNodeWithText("Generate portable preference profile for Aura Scout").assertExists()
    }
}
