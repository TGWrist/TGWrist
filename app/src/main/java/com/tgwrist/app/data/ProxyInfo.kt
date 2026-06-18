package com.tgwrist.app.data

import org.drinkless.tdlib.TdApi

/**
 * 代理类型。
 * - SOCKS5：SOCKS5 代理（可选用户名/密码）
 * - HTTP：HTTP 代理（可选用户名/密码 + httpOnly）
 * - MTPROTO：Telegram MTProto 代理（secret）
 */
enum class ProxyKind {
    SOCKS5,
    HTTP,
    MTPROTO
}

/**
 * 本地保存的代理信息。
 *
 * TDLib 的代理列表是存放在「每个账号各自的 tdlib 数据库」里的，
 * 切换账号 / 重新初始化时不会自动带过来，因此这里在 App 层额外持久化一份，
 * 在每次 tdlib 启动（AuthorizationStateWaitTdlibParameters）时重新下发选中的代理。
 *
 * @param id 本地唯一标识（与 TDLib 的 proxyId 无关）
 * @param server 代理服务器域名或 IP
 * @param port 代理端口
 * @param type 代理类型
 * @param username 登录用户名（SOCKS5 / HTTP，可空）
 * @param password 登录密码（SOCKS5 / HTTP，可空）
 * @param secret MTProto 代理的 secret（十六进制）
 * @param httpOnly HTTP 代理是否仅支持 HTTP 请求
 */
data class ProxyInfo(
    val id: String,
    val server: String,
    val port: Int,
    val type: ProxyKind,
    val username: String = "",
    val password: String = "",
    val secret: String = "",
    val httpOnly: Boolean = false
) {
    /**
     * 转换为 TDLib 的 [TdApi.Proxy]，用于 AddProxy 等调用。
     */
    fun toTdProxy(): TdApi.Proxy {
        val proxyType: TdApi.ProxyType = when (type) {
            ProxyKind.SOCKS5 -> TdApi.ProxyTypeSocks5(username, password)
            ProxyKind.HTTP -> TdApi.ProxyTypeHttp(username, password, httpOnly)
            ProxyKind.MTPROTO -> TdApi.ProxyTypeMtproto(secret)
        }
        return TdApi.Proxy(server, port, proxyType)
    }
}
