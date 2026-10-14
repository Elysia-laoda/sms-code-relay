package com.elysia.smsrecv

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {

    /** 取本机局域网 IPv4：优先 wlan0，其次任何非回环地址。 */
    fun lanIpv4(): String {
        val list = allIpv4()
        return list.firstOrNull { it.first.startsWith("wlan") }?.second
            ?: list.firstOrNull()?.second
            ?: ""
    }

    fun allIpv4(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        out.add(nif.name to (addr.hostAddress ?: ""))
                    }
                }
            }
        }
        return out
    }

    fun summary(): String {
        val all = allIpv4()
        if (all.isEmpty()) return "没有局域网地址（Wi-Fi 关着？）"
        return all.joinToString("  ") { it.first + "=" + it.second }
    }
}
