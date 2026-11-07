package com.privateai.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Only used to show "offline / online" on screen. Chats never use the network. */
fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
