package com.tgwrist.app.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

class WifiNetworkRequester(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var timeoutHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 请求 WiFi 网络
     */
    fun requestWifi(
        timeoutMs: Long = 10000,
        requireInternet: Boolean = false, // 新增：是否需要该 WiFi 具备外网能力
        onAvailable: (Network) -> Unit,
        onUnavailable: () -> Unit
    ) {
        if (callback != null) return

        val requestBuilder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)

        if (requireInternet) {
            requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 1. 绑定当前进程到 WiFi
                connectivityManager.bindProcessToNetwork(network)

                // 2. 成功后必须清理超时器
                timeoutHandler?.removeCallbacksAndMessages(null)

                // 3. 切换回主线程回调，防止外部更新 UI 崩溃
                mainHandler.post { onAvailable(network) }
            }

            override fun onLost(network: Network) {
                release() // 网络丢失，自动释放并解绑
            }

            override fun onUnavailable() {
                release()
                mainHandler.post { onUnavailable() }
            }
        }

        callback = cb

        // 提交网络请求
        connectivityManager.requestNetwork(requestBuilder.build(), cb)

        // 超时控制
        timeoutHandler = Handler(Looper.getMainLooper())
        timeoutHandler?.postDelayed({
            // 只有当回调还没被清理时，才认为是超时
            if (callback == cb) {
                release()
                onUnavailable()
            }
        }, timeoutMs)
    }

    /**
     * 释放 WiFi 请求并解绑进程
     */
    fun release() {
        // 关键修复：清除可能存在的超时任务
        timeoutHandler?.removeCallbacksAndMessages(null)
        timeoutHandler = null

        try {
            // 解除进程的网络绑定，恢复系统默认路由（如切回蜂窝数据）
            connectivityManager.bindProcessToNetwork(null)
            callback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
            // unregisterNetworkCallback 如果回调未注册可能会抛异常，吞掉即可
        } finally {
            callback = null
        }
    }
}
