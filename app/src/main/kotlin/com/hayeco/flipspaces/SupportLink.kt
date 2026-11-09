package com.hayeco.flipspaces

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hayeco.flipspaces.core.Support

/** 1.1.15: Ko-fi in the browser, from the "Buy me a beer" row at the top of the trackpad's Menu. */
object SupportLink {
    fun intent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(Support.KO_FI_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** False when nothing on the phone opens a web page. */
    fun open(context: Context): Boolean = runCatching { context.startActivity(intent()) }.isSuccess

    /** Unreleased: [url] in the browser (the Play testers card's two links); false when nothing opens it. */
    fun openUrl(context: Context, url: String): Boolean =
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
