package com.selfmod.agent.agent

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Hard guard for the http tools. The prompt already tells the model "offline mode
 * only hits LAN/localhost", but a prompt is a soft constraint — a tool must not be
 * able to reach [SSRF](https://owasp.org/www-community/attacks/Server_Side_Request_Forgery)
 * sinks like the cloud metadata endpoint or internal services. This is the hard wall.
 *
 * Rules (see [check]):
 * - only http / https schemes
 * - always reject: loopback, link-local (169.254.0.0/16, incl. cloud metadata
 *   169.254.169.254), unspecified (0.0.0.0 / ::), multicast
 * - ONLINE mode also rejects private ranges (10/8, 172.16/12, 192.168/16,
 *   100.64/10, 127/8) — the agent calling its own device or the LAN is an SSRF
 * - OFFLINE mode allows private ranges (the local LLM lives on the LAN) but still
 *   blocks metadata / loopback / link-local
 */
object HttpGuard {

    sealed class Verdict {
        data class Allow(val url: String) : Verdict()
        data class Deny(val reason: String) : Verdict()
    }

    /** Cloud metadata is the highest-value SSRF sink; reject it explicitly. */
    private const val METADATA_V4 = "169.254.169.254"

    fun check(rawUrl: String, offlineMode: Boolean): Verdict {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Verdict.Deny("URL 为空")

        val uri = runCatching { URI(url) }.getOrNull()
            ?: return Verdict.Deny("URL 无法解析：$url")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Verdict.Deny("仅允许 http/https，收到：$scheme")
        }
        val host = uri.host?.takeIf { it.isNotEmpty() }
            ?: return Verdict.Deny("URL 缺少主机名")

        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: UnknownHostException) {
            // Can't resolve — common for offline LAN mDNS hosts (.local). Fall
            // back to a hostname heuristic so LAN addresses still work offline.
            return if (offlineMode && looksLikePrivateHostname(host)) {
                Verdict.Allow(url)
            } else {
                Verdict.Deny("无法解析主机名：$host")
            }
        }
        if (addresses.isEmpty()) return Verdict.Deny("主机名解析为空：$host")

        for (addr in addresses) {
            val deny = denyReason(addr, offlineMode)
            if (deny != null) return Verdict.Deny(deny)
        }
        return Verdict.Allow(url)
    }

    private fun looksLikePrivateHostname(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" ||
            h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") ||
            h == "raspberrypi" || h == "host.docker.internal"
    }

    private enum class Kind { LOOPBACK, UNSPECIFIED, LINK_LOCAL, PRIVATE, OK }

    private fun classify(addr: InetAddress): Kind {
        return when (addr) {
            is Inet4Address -> {
                val b = addr.address
                val a0 = b[0].toInt() and 0xff
                val a1 = b[1].toInt() and 0xff
                when {
                    a0 == 127 -> Kind.LOOPBACK
                    a0 == 0 -> Kind.UNSPECIFIED
                    a0 == 10 -> Kind.PRIVATE
                    a0 == 172 && a1 in 16..31 -> Kind.PRIVATE
                    a0 == 192 && a1 == 168 -> Kind.PRIVATE
                    a0 == 100 && a1 in 64..127 -> Kind.PRIVATE
                    a0 == 169 && a1 == 254 -> Kind.LINK_LOCAL
                    a0 in 224..239 -> Kind.PRIVATE // multicast range, treat as non-public
                    else -> Kind.OK
                }
            }
            is Inet6Address -> {
                val b = addr.address
                val hi = (b[0].toInt() and 0xff) shl 8 or (b[1].toInt() and 0xff)
                when {
                    addr.isLoopbackAddress -> Kind.LOOPBACK
                    addr.isAnyLocalAddress -> Kind.UNSPECIFIED
                    addr.isLinkLocalAddress -> Kind.LINK_LOCAL
                    addr.isMulticastAddress -> Kind.PRIVATE
                    // unique local fc00::/7
                    (hi and 0xfe00) == 0xfc00 -> Kind.PRIVATE
                    else -> Kind.OK
                }
            }
            else -> Kind.OK
        }
    }

    private fun denyReason(addr: InetAddress, offlineMode: Boolean): String? {
        val kind = classify(addr)
        return when (kind) {
            Kind.LOOPBACK -> "拒绝访问回环地址（127.0.0.0/8、::1）"
            Kind.UNSPECIFIED -> "拒绝访问未指定地址（0.0.0.0、::）"
            Kind.LINK_LOCAL -> "拒绝访问链路本地/云元数据地址（169.254.0.0/16，含 169.254.169.254）"
            Kind.PRIVATE -> if (offlineMode) {
                null
            } else {
                "拒绝访问私有网段（10/8、172.16/12、192.168/16 等）。在线模式下 http 工具只允许公网地址；" +
                    "若要访问本机/局域网模型，请先开启离线模式。"
            }
            Kind.OK -> null
        }
    }
}
