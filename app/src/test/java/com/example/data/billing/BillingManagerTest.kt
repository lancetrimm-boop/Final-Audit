package com.example.data.billing

import com.android.billingclient.api.*
import com.example.data.entitlement.EntitlementRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class BillingManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val mockEntitlementRepo: EntitlementRepository = mock()
    private val mockProvider: BillingProvider = mock()
    
    private lateinit var billingManager: BillingManager

    @Before
    fun setup() {
        billingManager = BillingManager(mock(), mockEntitlementRepo, testScope, mockProvider)
    }

    @Test
    fun `TEST 2 - Active PURCHASED Pro product updates repository to PRO`() = runTest(testDispatcher) {
        val purchase: Purchase = mock()
        whenever(purchase.products).thenReturn(listOf("aura_pro_unlock_one_time"))
        whenever(purchase.purchaseState).thenReturn(Purchase.PurchaseState.PURCHASED)
        whenever(purchase.isAcknowledged).thenReturn(true)
        whenever(purchase.purchaseToken).thenReturn("test_token")

        whenever(mockProvider.isReady()).thenReturn(true)
        
        doAnswer {
            val listener = it.getArgument<PurchasesResponseListener>(1)
            listener.onQueryPurchasesResponse(
                BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
                listOf(purchase)
            )
        }.whenever(mockProvider).queryPurchasesAsync(any(), any())

        billingManager.queryActivePurchases()

        verify(mockEntitlementRepo).updateEntitlement(eq(true), eq("test_token"))
    }

    @Test
    fun `TEST 8 - No active Pro purchase updates repository to FREE`() = runTest(testDispatcher) {
        whenever(mockProvider.isReady()).thenReturn(true)
        
        doAnswer {
            val listener = it.getArgument<PurchasesResponseListener>(1)
            listener.onQueryPurchasesResponse(
                BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
                emptyList()
            )
        }.whenever(mockProvider).queryPurchasesAsync(any(), any())

        billingManager.queryActivePurchases()

        verify(mockEntitlementRepo).updateEntitlement(eq(false), isNull())
    }

    @Test
    fun `TEST 6 - Unacknowledged PURCHASED Pro triggers acknowledgement`() = runTest(testDispatcher) {
        val purchase: Purchase = mock()
        whenever(purchase.products).thenReturn(listOf("aura_pro_unlock_one_time"))
        whenever(purchase.purchaseState).thenReturn(Purchase.PurchaseState.PURCHASED)
        whenever(purchase.isAcknowledged).thenReturn(false)
        whenever(purchase.purchaseToken).thenReturn("unack_token")

        whenever(mockProvider.isReady()).thenReturn(true)
        
        doAnswer {
            val listener = it.getArgument<PurchasesResponseListener>(1)
            listener.onQueryPurchasesResponse(
                BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
                listOf(purchase)
            )
        }.whenever(mockProvider).queryPurchasesAsync(any(), any())

        billingManager.queryActivePurchases()

        verify(mockProvider).acknowledgePurchase(any(), any())
    }
}
