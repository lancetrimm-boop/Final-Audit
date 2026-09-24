package com.example.data.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.example.data.entitlement.EntitlementRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BillingManager(
    private val context: Context,
    private val entitlementRepository: EntitlementRepository,
    private val scope: CoroutineScope,
    private val provider: BillingProvider? = null, // Optional for testing
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
) : PurchasesUpdatedListener {

    private val PRODUCT_ID = "aura_pro_unlock_one_time"
    private var billingProvider: BillingProvider

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    init {
        billingProvider = provider ?: AndroidBillingProvider(context, this)
        startConnection()
    }

    fun startConnection() {
        billingProvider.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails()
                    queryActivePurchases()
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun queryProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            ))
            .build()

        billingProvider.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val list = queryResult.productDetailsList
                if (list != null) {
                    for (details in list) {
                        if (details.productId == PRODUCT_ID) {
                            _productDetails.value = details
                            break
                        }
                    }
                }
            }
        }
    }

    fun queryActivePurchases() {
        if (!billingProvider.isReady()) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingProvider.queryPurchasesAsync(params) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(list)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            processPurchases(purchases)
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        var proPurchase: Purchase? = null
        for (p in purchases) {
            if (p.products.contains(PRODUCT_ID) && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                proPurchase = p
                break
            }
        }
        
        scope.launch {
            if (proPurchase != null) {
                acknowledgeIfNecessary(proPurchase)
            } else {
                entitlementRepository.updateEntitlement(false)
            }
        }
    }

    private suspend fun acknowledgeIfNecessary(purchase: Purchase) {
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            withContext(ioDispatcher) {
                billingProvider.acknowledgePurchase(params) { result ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        scope.launch { entitlementRepository.updateEntitlement(true, purchase.purchaseToken) }
                    }
                }
            }
        } else {
            entitlementRepository.updateEntitlement(true, purchase.purchaseToken)
        }
    }

    fun launchPurchaseFlow(activity: Activity) {
        val details = _productDetails.value ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()
            ))
            .build()
        billingProvider.launchBillingFlow(activity, params)
    }
}
