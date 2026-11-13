package com.psei.launcher.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.psei.launcher.data.Prefs

/**
 * Ultra-lightweight, zero-battery Google Play Billing Manager.
 * Handles Subscriptions (Monthly, Yearly) and Lifetime In-App Purchase.
 * Thread-safe, reactive, and compliant with Google Play Billing 7.x.
 */
class BillingManager private constructor(private val context: Context) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"

        // Subscription Plans (Google Play Console -> Subscriptions)
        const val PRODUCT_MONTHLY = "psei_pro_monthly"
        const val PRODUCT_YEARLY = "psei_pro_yearly"

        // One-time Lifetime In-App Purchase (Google Play Console -> In-app products)
        const val PRODUCT_LIFETIME = "psei_pro_lifetime"

        @Volatile
        private var instance: BillingManager? = null

        fun getInstance(context: Context): BillingManager =
            instance ?: synchronized(this) {
                instance ?: BillingManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = Prefs.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val productDetailsMap = mutableMapOf<String, ProductDetails>()
    private var isConnected = false
    private var isConnecting = false

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    interface BillingCallback {
        fun onProductsLoaded(prices: Map<String, String>)
        fun onPurchaseSuccess()
        fun onPurchaseFailed(errorMessage: String)
    }

    private var activeCallback: BillingCallback? = null

    fun setCallback(callback: BillingCallback?) {
        activeCallback = callback
        if (callback != null) {
            callback.onProductsLoaded(getFormattedPrices())
        }
    }

    fun startConnection() {
        if (isConnected || isConnecting) return
        isConnecting = true

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                isConnecting = false
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    Log.d(TAG, "BillingClient connected successfully")
                    queryProductDetails()
                    queryActivePurchases()
                } else {
                    isConnected = false
                    Log.w(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnected = false
                isConnecting = false
                Log.d(TAG, "Billing service disconnected")
            }
        })
    }

    fun queryProductDetails() {
        if (!isConnected) return

        // 1. Query Subscriptions
        val subProducts = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(subProducts).build()
        ) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (details in productDetailsList) {
                    productDetailsMap[details.productId] = details
                }
                mainHandler.post { activeCallback?.onProductsLoaded(getFormattedPrices()) }
            }
        }

        // 2. Query In-App One-Time Product (Lifetime)
        val inAppProducts = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_LIFETIME)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(inAppProducts).build()
        ) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (details in productDetailsList) {
                    productDetailsMap[details.productId] = details
                }
                mainHandler.post { activeCallback?.onProductsLoaded(getFormattedPrices()) }
            }
        }
    }

    fun getFormattedPrices(): Map<String, String> {
        val prices = mutableMapOf<String, String>()

        // Fallback default pricing ($ plans as requested)
        prices[PRODUCT_MONTHLY] = "$0.99 / month"
        prices[PRODUCT_YEARLY] = "$4.99 / year"
        prices[PRODUCT_LIFETIME] = "$9.99 (lifetime)"

        for ((productId, details) in productDetailsMap) {
            if (details.productType == BillingClient.ProductType.SUBS) {
                val pricingPhase = details.subscriptionOfferDetails?.firstOrNull()
                    ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                if (pricingPhase != null) {
                    val price = pricingPhase.formattedPrice
                    prices[productId] = when (productId) {
                        PRODUCT_MONTHLY -> "$price / month"
                        PRODUCT_YEARLY -> "$price / year"
                        else -> price
                    }
                }
            } else {
                val oneTime = details.oneTimePurchaseOfferDetails
                if (oneTime != null) {
                    prices[productId] = "${oneTime.formattedPrice} (lifetime)"
                }
            }
        }
        return prices
    }

    fun launchBillingFlow(activity: Activity, productId: String) {
        if (!isConnected) {
            startConnection()
            activeCallback?.onPurchaseFailed("Connecting to Google Play. Please try again.")
            return
        }

        val details = productDetailsMap[productId]
        if (details == null) {
            queryProductDetails()
            activeCallback?.onPurchaseFailed("Product not loaded yet from Google Play. Please try again.")
            return
        }

        val flowParams = if (details.productType == BillingClient.ProductType.SUBS) {
            val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: ""
            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .setOfferToken(offerToken)
                    .build()
            )
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()
        } else {
            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .build()
            )
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()
        }

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            activeCallback?.onPurchaseFailed("Failed to launch purchase: ${result.debugMessage}")
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
            mainHandler.post { activeCallback?.onPurchaseSuccess() }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d(TAG, "User canceled purchase")
        } else {
            mainHandler.post {
                activeCallback?.onPurchaseFailed("Purchase error: ${billingResult.debugMessage}")
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            prefs.isProUser = true

            if (!purchase.isAcknowledged) {
                val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(acknowledgeParams) { ackResult ->
                    Log.d(TAG, "Purchase acknowledged: ${ackResult.responseCode}")
                }
            }
        }
    }

    fun queryActivePurchases(onComplete: ((Boolean) -> Unit)? = null) {
        if (!isConnected) {
            onComplete?.invoke(prefs.isProUser)
            return
        }

        // Query active Subscriptions
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { subResult, subPurchases ->
            var hasActivePro = false
            if (subResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (p in subPurchases) {
                    if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        hasActivePro = true
                        handlePurchase(p)
                    }
                }
            }

            // Query In-App One-Time (Lifetime)
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            ) { inAppResult, inAppPurchases ->
                if (inAppResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (p in inAppPurchases) {
                        if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            hasActivePro = true
                            handlePurchase(p)
                        }
                    }
                }

                if (hasActivePro) {
                    prefs.isProUser = true
                }

                mainHandler.post {
                    onComplete?.invoke(prefs.isProUser)
                }
            }
        }
    }
}

