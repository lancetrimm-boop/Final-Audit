package com.example.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*

/**
 * Abstraction for BillingClient to enable deterministic testing without Google Play Services.
 */
interface BillingProvider {
    fun startConnection(listener: BillingClientStateListener)
    fun queryProductDetailsAsync(params: QueryProductDetailsParams, listener: ProductDetailsResponseListener)
    fun queryPurchasesAsync(params: QueryPurchasesParams, listener: PurchasesResponseListener)
    fun acknowledgePurchase(params: AcknowledgePurchaseParams, listener: AcknowledgePurchaseResponseListener)
    fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult
    fun isReady(): Boolean
}

/**
 * Production implementation using the real [BillingClient].
 */
class AndroidBillingProvider(context: Context, listener: PurchasesUpdatedListener) : BillingProvider {
    private val client = BillingClient.newBuilder(context)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    override fun startConnection(listener: BillingClientStateListener) = client.startConnection(listener)
    override fun queryProductDetailsAsync(params: QueryProductDetailsParams, listener: ProductDetailsResponseListener) =
        client.queryProductDetailsAsync(params, listener)
    override fun queryPurchasesAsync(params: QueryPurchasesParams, listener: PurchasesResponseListener) =
        client.queryPurchasesAsync(params, listener)
    override fun acknowledgePurchase(params: AcknowledgePurchaseParams, listener: AcknowledgePurchaseResponseListener) =
        client.acknowledgePurchase(params, listener)
    override fun launchBillingFlow(activity: Activity, params: BillingFlowParams) = client.launchBillingFlow(activity, params)
    override fun isReady() = client.isReady
}
