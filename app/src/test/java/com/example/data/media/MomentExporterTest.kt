package com.example.data.media

import android.content.Context
import com.example.data.MediaItem
import com.example.data.entitlement.EntitlementRepository
import com.example.data.entitlement.ProFeature
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.io.File

@androidx.media3.common.util.UnstableApi
class MomentExporterTest {

    private val mockContext: Context = mock()
    private val mockEntitlementRepo: EntitlementRepository = mock()
    private val mockEngine: MomentEncodingEngine = mock()
    private val cacheDir = File("build/test-cache")
    
    private lateinit var exporter: MomentExporter

    @Before
    fun setup() {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        whenever(mockContext.cacheDir).thenReturn(cacheDir)
        exporter = MomentExporter(mockContext, mockEntitlementRepo, mockEngine)
    }

    @Test
    fun `TEST 1 - FREE entitlement rejects export`() = runTest {
        whenever(mockEntitlementRepo.isFeatureAvailable(ProFeature.MOMENTS_EXPORT)).thenReturn(false)
        
        val result = exporter.exportMoment(listOf(createPhotoItem("1")))
        
        assertTrue(result is MomentExporter.ExportState.NotEntitled)
        verifyNoInteractions(mockEngine)
    }

    @Test
    fun `TEST 3 - PRO entitlement permits export`() = runTest {
        whenever(mockEntitlementRepo.isFeatureAvailable(ProFeature.MOMENTS_EXPORT)).thenReturn(true)
        whenever(mockEngine.encode(any(), any(), any())).thenReturn(Result.success(File("out.mp4")))
        
        val result = exporter.exportMoment(listOf(createPhotoItem("1")))
        
        assertTrue(result is MomentExporter.ExportState.Success)
    }

    @Test
    fun `TEST 4 - Empty Moment is rejected`() = runTest {
        whenever(mockEntitlementRepo.isFeatureAvailable(ProFeature.MOMENTS_EXPORT)).thenReturn(true)
        
        val result = exporter.exportMoment(emptyList())
        
        assertTrue(result is MomentExporter.ExportState.Failed)
        assertEquals("Moment is empty", (result as MomentExporter.ExportState.Failed).message)
    }

    private fun createPhotoItem(id: String) = MediaItem(
        id = id,
        title = "Photo $id",
        mediaType = "PHOTO",
        uriPath = "file:///test/photo$id.jpg"
    )
}
