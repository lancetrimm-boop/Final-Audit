package com.example.data.export

import com.example.data.*
import com.example.data.db.SearchFeedbackEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class AuraPreferenceContractTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(AuraPreferenceContract::class.java)

    @Test
    fun testContractSerialization_ConformsToSchema() {
        val dna = TasteDNA(vibrancy = 0.8, confVibrancy = 0.9)
        val stats = IntelligenceStats(topGenres = listOf("Landscape"), favoriteMoods = listOf("Cinematic"))
        val favorites = listOf(MediaItem(id = "f1", title = "Sunset", mediaType = "PHOTO", isFavorite = true))
        val negatives = listOf(SearchFeedbackEntity(mediaId = "n1", query = "query", queryType = "TEXT", rankingPosition = 1, rrfScore = 0.5, feedback = "BAD"))
        val allMedia = favorites + listOf(MediaItem(id = "n1", title = "Blurry", mediaType = "PHOTO"))

        val contract = AuraPreferenceExporter.export(dna, stats, favorites, negatives, allMedia)
        val json = adapter.toJson(contract)

        assertNotNull(json)
        assertTrue(json.contains("\"schema\":\"aura-preferences-v1\""))
        assertTrue(json.contains("\"vibrancy\":{\"value\":0.8"))
        assertTrue(json.contains("\"status\":\"verified\""))
        assertTrue(json.contains("\"positive\":[\"Sunset\"]"))
        assertTrue(json.contains("\"negative\":[\"Blurry\"]"))
    }

    @Test
    fun testPrivacyLeak_NoInternalIdsOrPaths() {
        val dna = TasteDNA()
        val stats = IntelligenceStats()
        val favorites = listOf(MediaItem(id = "secret-uuid-123", title = "Sunset", mediaType = "PHOTO", uriPath = "/data/user/0/aura/secret.jpg", isFavorite = true))
        val contract = AuraPreferenceExporter.export(dna, stats, favorites, emptyList(), favorites)
        val json = adapter.toJson(contract)

        assertFalse("Should not contain internal UUID", json.contains("secret-uuid-123"))
        assertFalse("Should not contain filesystem paths", json.contains("/data/user/0"))
        assertFalse("Should not contain .jpg extensions from paths", json.contains("secret.jpg"))
        assertTrue("Should contain titles", json.contains("Sunset"))
    }

    @Test
    fun testConfidenceThreshold_UnknownStatus() {
        // vibrancy conf = 0.05 (< 0.1) => unknown
        // contrast conf = 0.2 (>= 0.1) => inferred
        // sharpness conf = 0.6 (>= 0.5) => verified
        val dna = TasteDNA(
            vibrancy = 0.8, confVibrancy = 0.05,
            contrast = 0.8, confContrast = 0.2,
            sharpness = 0.8, confSharpness = 0.6
        )
        val contract = AuraPreferenceExporter.export(dna, IntelligenceStats(), emptyList(), emptyList(), emptyList())
        
        assertEquals("unknown", contract.aesthetic_preferences.visual_dna["vibrancy"]?.status)
        assertEquals("inferred", contract.aesthetic_preferences.visual_dna["contrast"]?.status)
        assertEquals("verified", contract.aesthetic_preferences.visual_dna["sharpness"]?.status)
    }

    @Test
    fun testNarrativeTranslation_ProducesDescriptions() {
        val dna = TasteDNA(
            vibrancy = 0.9, confVibrancy = 1.0,
            minimalism = 0.1, confMinimalism = 1.0
        )
        val contract = AuraPreferenceExporter.export(dna, IntelligenceStats(), emptyList(), emptyList(), emptyList())
        
        val narrative = contract.aesthetic_preferences.narrative
        assertTrue(narrative.contains("Strong preference for vibrancy"))
        assertTrue(narrative.contains("Preference for low minimalism"))
    }

    @Test
    fun testEmptyData_ProducesValidContract() {
        val contract = AuraPreferenceExporter.export(TasteDNA(), IntelligenceStats(), emptyList(), emptyList(), emptyList())
        
        assertEquals("aura-preferences-v1", contract.schema)
        assertTrue(contract.aesthetic_preferences.visual_dna.isNotEmpty())
        assertTrue(contract.examples.positive.isEmpty())
        assertEquals("unknown", contract.discovery_behavior.novelty_seeking.level)
    }

    @Test
    fun testSerializationStability_IsDeterministic() {
        val dna = TasteDNA(vibrancy = 0.7, confVibrancy = 0.5)
        val stats = IntelligenceStats(topGenres = listOf("A", "B"))
        
        val c1 = AuraPreferenceExporter.export(dna, stats, emptyList(), emptyList(), emptyList())
        val c2 = AuraPreferenceExporter.export(dna, stats, emptyList(), emptyList(), emptyList())
        
        // generated_at will differ, but preferences should be identical
        assertEquals(c1.aesthetic_preferences, c2.aesthetic_preferences)
        assertEquals(c1.discovery_behavior, c2.discovery_behavior)
        assertEquals(c1.interests, c2.interests)
    }
}
