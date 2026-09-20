package com.example.ui.screens

import com.example.ui.models.DecisionProvenance
import com.example.ui.models.MirrorFixtures
import org.junit.Assert.*
import org.junit.Test

class DecisionProvenanceTest {

    @Test
    fun `test DecisionProvenance model storage`() {
        val prov = DecisionProvenance(
            rankScore = 0.99,
            primaryRelevance = 0.9f,
            provenanceSummary = "Summary"
        )
        assertEquals(0.99, prov.rankScore, 0.001)
        assertEquals(0.9f, prov.primaryRelevance)
        assertEquals("Summary", prov.provenanceSummary)
    }

    @Test
    fun `test MirrorFixtures carry provenance`() {
        val state = MirrorFixtures.populatedLibrary
        assertTrue(state.provenanceMap.isNotEmpty())
        val prov = state.provenanceMap["fixture_1"]
        assertNotNull(prov)
        assertEquals("FIXTURE", prov?.source)
    }

    @Test
    fun `test Empty fixture provenance is empty`() {
        val state = MirrorFixtures.emptyLibrary
        assertTrue(state.provenanceMap.isEmpty())
    }
}
