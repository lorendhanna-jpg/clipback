package com.clipback.app

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/** ClipBack Pro entitlement: unlocks replay windows longer than 10 seconds. */
object Pro {
    private const val PREFS = "clipback"
    private const val KEY = "pro"

    fun isPro(ctx: Context): Boolean =
        BuildConfig.DEBUG ||   // test builds get Pro so long windows can be tried before the Play listing exists
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setPro(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}

/**
 * Google Play subscription for ClipBack Pro. Product id `clipback_pro` must be
 * created in Play Console (Monetize → Subscriptions) once the app is listed;
 * until then product details come back empty and the paywall explains that.
 */
class BillingManager(
    private val activity: Activity,
    private val onEntitlementChange: () -> Unit
) : PurchasesUpdatedListener {

    companion object { const val PRODUCT_ID = "clipback_pro" }

    var product: ProductDetails? = null
        private set

    private val client = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    refreshEntitlement()
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                product = details.firstOrNull()
            }
        }
    }

    private fun refreshEntitlement() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val owned = purchases.any {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        it.products.contains(PRODUCT_ID)
                }
                Pro.setPro(activity, owned)
                activity.runOnUiThread { onEntitlementChange() }
            }
        }
    }

    /** Kick off the Google Play purchase sheet. Returns false if the product
     *  isn't available yet (app not on Play / product not configured). */
    fun launchPurchase(): Boolean {
        val p = product ?: return false
        val offerToken = p.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(p)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        client.launchBillingFlow(activity, params)
        return true
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            if (!purchase.isAcknowledged) {
                client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                ) { }
            }
            if (purchase.products.contains(PRODUCT_ID)) {
                Pro.setPro(activity, true)
                activity.runOnUiThread { onEntitlementChange() }
            }
        }
    }
}
