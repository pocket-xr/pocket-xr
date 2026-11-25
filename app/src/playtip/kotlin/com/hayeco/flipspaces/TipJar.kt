package com.hayeco.flipspaces

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.hayeco.flipspaces.core.Support

/**
 * 1.6.26 (Mike: a one-time "tip" in-app product, as Play's payments policy hides the Ko-fi link in the Play build): the
 * "Buy me a beer" pill in the Play build buys [Support.TIP_PRODUCT], a one-time product in Play Console, through Play's own
 * purchase sheet. Each tip is consumed once Play says it is paid, so it can be bought again; nothing is unlocked by it. A
 * tip paid while the app was closed (a slow payment that cleared) is consumed, and thanked for, on the next [warm]. The
 * library talks to the Play Store app on the phone; Pocket XR sends nothing of its own about it.
 */
object TipJar : PurchasesUpdatedListener {
    private const val TAG = "TipJar"
    private val main = Handler(Looper.getMainLooper())
    private var client: BillingClient? = null
    private var app: Context? = null
    private var details: ProductDetails? = null
    private var waiting: Activity? = null

    /** Play's price for the tip, as Play formats it for this account; null until Play has answered. */
    @Volatile var price: String? = null
        private set

    /** Connects, fetches the price and consumes any tip paid since; cheap to call again. */
    fun warm(context: Context) {
        app = context.applicationContext
        connect { ok ->
            if (!ok) return@connect
            loadDetails {}
            client?.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list ->
                if (r.responseCode == BillingClient.BillingResponseCode.OK) list.forEach { settle(it, said = true) }
            }
        }
    }

    /** Opens Play's purchase sheet for the tip; true when the tip goes through Play (false: the caller opens Ko-fi). */
    fun tip(activity: Activity): Boolean {
        app = activity.applicationContext
        waiting = activity
        connect { ok ->
            if (!ok) return@connect say(R.string.tip_no_play)
            loadDetails { d ->
                val a = waiting ?: return@loadDetails
                if (d == null) return@loadDetails say(R.string.tip_no_play)
                val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d)
                d.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken?.let { product.setOfferToken(it) }
                val r = client?.launchBillingFlow(a, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product.build())).build())
                if (r != null && r.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "tip: the purchase sheet would not open (${r.responseCode} ${r.debugMessage})")
                    say(R.string.tip_failed)
                }
            }
        }
        return true
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { settle(it, said = true) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            // A tip not yet consumed (an earlier one whose consume did not reach Play): consumed now, then it can be bought.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> app?.let(::warm)
            else -> {
                Log.w(TAG, "tip: ${result.responseCode} ${result.debugMessage}")
                say(R.string.tip_failed)
            }
        }
    }

    /** A tip purchase: consumed and thanked for once paid; a pending one said ([said]) only when it was just bought. */
    private fun settle(p: Purchase, said: Boolean) {
        if (Support.TIP_PRODUCT !in p.products) return
        when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> client?.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { r, _ ->
                if (r.responseCode == BillingClient.BillingResponseCode.OK) say(R.string.tip_thanks)
                else Log.w(TAG, "tip: consume failed (${r.responseCode}); the next start tries again")
            }
            Purchase.PurchaseState.PENDING -> if (said && waiting != null) say(R.string.tip_pending)
            else -> Unit
        }
    }

    private fun connect(then: (Boolean) -> Unit) {
        val ctx = app ?: return then(false)
        val c = client ?: BillingClient.newBuilder(ctx)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build().also { client = it }
        if (c.isReady) return then(true)
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                main.post { then(r.responseCode == BillingClient.BillingResponseCode.OK) }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun loadDetails(then: (ProductDetails?) -> Unit) {
        details?.let { return then(it) }
        val c = client ?: return then(null)
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(Support.TIP_PRODUCT).setProductType(BillingClient.ProductType.INAPP).build(),
        )).build()
        c.queryProductDetailsAsync(params) { r, result ->
            val d = if (r.responseCode == BillingClient.BillingResponseCode.OK) result.productDetailsList.firstOrNull { it.productId == Support.TIP_PRODUCT } else null
            if (d == null) Log.w(TAG, "tip: Play has no ${Support.TIP_PRODUCT} for this account (${r.responseCode} ${r.debugMessage})")
            details = d
            price = d?.oneTimePurchaseOfferDetails?.formattedPrice
            main.post { then(d) }
        }
    }

    private fun say(res: Int) {
        val ctx = app ?: return
        main.post { Toast.makeText(ctx, ctx.getString(res), Toast.LENGTH_LONG).show() }
    }
}
