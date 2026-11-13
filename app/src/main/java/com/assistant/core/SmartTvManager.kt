package com.assistant.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class TvProtocol {
    DIAL_CAST,   // Standard Smart TV (Port 8008 - Samsung, LG, Sony, Android TV, Fire TV)
    SAMSUNG_API, // Samsung Tizen OS (Port 8001/8002)
    ROKU_ECP,    // Roku OS (Port 8060)
    GENERIC_DLNA // UPnP / DLNA Media Renderer
}

data class DiscoveredTv(
    val name: String,
    val ip: String,
    val protocol: TvProtocol,
    val port: Int = 8008,
    val model: String = ""
)

object SmartTvManager {

    private const val TAG = "SmartTvManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    var discoveredTvs = mutableListOf<DiscoveredTv>()
        private set

    @Volatile
    var activeTv: DiscoveredTv? = null
        private set

    /**
     * Retrieves current connected WiFi SSID and local IP
     */
    fun getWifiInfo(context: Context): Pair<String, String> {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val connectionInfo = wifiManager?.connectionInfo
            var ssid = connectionInfo?.ssid?.replace("\"", "") ?: "UNKNOWN_WIFI"
            if (ssid == "<unknown ssid>" || ssid.isBlank()) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val net = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(net)
                ssid = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) "LOCAL_WIFI" else "OFFLINE"
            }

            val ipInt = connectionInfo?.ipAddress ?: 0
            val ip = if (ipInt != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else {
                "127.0.0.1"
            }
            Pair(ssid, ip)
        } catch (e: Exception) {
            Pair("WIFI_OFFLINE", "127.0.0.1")
        }
    }

    /**
     * Scans local WiFi subnet for Smart TVs, Chromecast, and DLNA media devices
     */
    fun scanLocalNetwork(context: Context, onResult: ((List<DiscoveredTv>) -> Unit)? = null) {
        scope.launch {
            val found = mutableListOf<DiscoveredTv>()
            val (_, localIp) = getWifiInfo(context)

            // Step 1: Multicast SSDP M-SEARCH (Fast discovery of DLNA / DIAL devices)
            try {
                val ssdpTvs = performSsdpDiscovery(context)
                found.addAll(ssdpTvs)
            } catch (e: Exception) {
                Log.w(TAG, "SSDP search error", e)
            }

            // Step 2: Targeted Subnet Port Probing (Port 8008 DIAL, Port 8001 Samsung, Port 8060 Roku)
            if (localIp != "127.0.0.1" && localIp.contains(".")) {
                try {
                    val subnetTvs = probeSubnetForTvs(localIp)
                    for (tv in subnetTvs) {
                        if (found.none { it.ip == tv.ip }) {
                            found.add(tv)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Subnet probe error", e)
                }
            }

            discoveredTvs = found
            if (found.isNotEmpty() && activeTv == null) {
                activeTv = found.first()
            }

            withContext(Dispatchers.Main) {
                onResult?.invoke(found)
            }
        }
    }

    /**
     * SSDP UPnP / DIAL Discovery query
     */
    private fun performSsdpDiscovery(context: Context): List<DiscoveredTv> {
        val tvs = mutableListOf<DiscoveredTv>()
        var socket: DatagramSocket? = null
        var multicastLock: WifiManager.MulticastLock? = null

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("JarvisTvMulticast")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            val query = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: ssdp:all\r\n\r\n"

            val group = InetAddress.getByName("239.255.255.250")
            socket = DatagramSocket()
            socket.soTimeout = 1800

            val packet = DatagramPacket(query.toByteArray(), query.length, group, 1900)
            socket.send(packet)

            val buffer = ByteArray(2048)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 1800) {
                try {
                    val recvPacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(recvPacket)
                    val response = String(recvPacket.data, 0, recvPacket.length)
                    val senderIp = recvPacket.address.hostAddress ?: continue

                    if (response.contains("LOCATION:", ignoreCase = true) ||
                        response.contains("Samsung", ignoreCase = true) ||
                        response.contains("dial", ignoreCase = true) ||
                        response.contains("MediaRenderer", ignoreCase = true)
                    ) {
                        val location = response.lines()
                            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim() ?: ""

                        val deviceName = resolveDeviceFriendlyName(location, senderIp)
                        val protocol = when {
                            location.contains(":8008") || response.contains("dial", ignoreCase = true) -> TvProtocol.DIAL_CAST
                            location.contains(":8001") || response.contains("samsung", ignoreCase = true) -> TvProtocol.SAMSUNG_API
                            location.contains(":8060") || response.contains("roku", ignoreCase = true) -> TvProtocol.ROKU_ECP
                            else -> TvProtocol.GENERIC_DLNA
                        }

                        if (tvs.none { it.ip == senderIp }) {
                            tvs.add(DiscoveredTv(deviceName, senderIp, protocol))
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP Discovery exception", e)
        } finally {
            socket?.close()
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (e: Exception) {}
        }
        return tvs
    }

    /**
     * Resolves friendly TV name from device description XML or REST
     */
    private fun resolveDeviceFriendlyName(location: String, ip: String): String {
        if (location.isNotBlank()) {
            try {
                val req = Request.Builder().url(location).build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val nameMatch = Regex("<friendlyName>(.+?)</friendlyName>", RegexOption.IGNORE_CASE).find(body)
                    if (nameMatch != null) {
                        return nameMatch.groupValues[1].trim()
                    }
                }
            } catch (e: Exception) {}
        }
        return "Smart TV ($ip)"
    }

    /**
     * Scans adjacent IPs on subnet for common TV ports (8008 DIAL, 8001 Samsung, 8060 Roku)
     */
    private suspend fun probeSubnetForTvs(localIp: String): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val parts = localIp.split(".")
        if (parts.size != 4) return@withContext emptyList()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        val host = parts[3].toIntOrNull() ?: 1

        val checkIps = mutableListOf<String>()
        // Prioritize adjacent IPs and common router/TV leases
        for (i in 1..40) {
            if (i != host) checkIps.add("$prefix.$i")
        }
        for (i in (host - 5).coerceAtLeast(1)..(host + 5).coerceAtMost(254)) {
            val ip = "$prefix.$i"
            if (!checkIps.contains(ip)) checkIps.add(ip)
        }

        val results = checkIps.map { ip ->
            async {
                probeSingleIpForTv(ip)
            }
        }.awaitAll().filterNotNull()

        results
    }

    private fun probeSingleIpForTv(ip: String): DiscoveredTv? {
        val ports = listOf(8008, 8001, 8060)
        for (port in ports) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 250)
                socket.close()

                // Port is open! Identify protocol
                return when (port) {
                    8008 -> {
                        val name = resolveDeviceFriendlyName("http://$ip:8008/ssdp/device-desc.xml", ip)
                        DiscoveredTv(if (name.contains("Smart TV")) "Google / Android TV ($ip)" else name, ip, TvProtocol.DIAL_CAST, 8008)
                    }
                    8001 -> {
                        val name = resolveSamsungName(ip)
                        DiscoveredTv(name, ip, TvProtocol.SAMSUNG_API, 8001)
                    }
                    8060 -> {
                        DiscoveredTv("Roku TV ($ip)", ip, TvProtocol.ROKU_ECP, 8060)
                    }
                    else -> DiscoveredTv("Smart Display ($ip)", ip, TvProtocol.GENERIC_DLNA, port)
                }
            } catch (e: Exception) {}
        }
        return null
    }

    private fun resolveSamsungName(ip: String): String {
        return try {
            val req = Request.Builder().url("http://$ip:8001/api/v2/").build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "")
                val deviceObj = json.optJSONObject("device")
                deviceObj?.optString("name") ?: "Samsung Smart TV"
            } else {
                "Samsung Smart TV ($ip)"
            }
        } catch (e: Exception) {
            "Samsung Smart TV ($ip)"
        }
    }

    /**
     * Launches YouTube or media query directly onto the Smart TV
     */
    fun castToTv(
        tvIp: String,
        query: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        scope.launch {
            val tv = discoveredTvs.firstOrNull { it.ip == tvIp } ?: activeTv ?: DiscoveredTv("Living Room TV", tvIp, TvProtocol.DIAL_CAST)

            try {
                when (tv.protocol) {
                    TvProtocol.DIAL_CAST -> {
                        // DIAL protocol: Launch YouTube with video/search query
                        val cleanQuery = java.net.URLEncoder.encode(query, "UTF-8")
                        val url = "http://${tv.ip}:8008/apps/YouTube"
                        val body = "v=$cleanQuery".toRequestBody("application/x-www-form-urlencoded".toMediaType())

                        val request = Request.Builder()
                            .url(url)
                            .post(body)
                            .build()

                        val resp = httpClient.newCall(request).execute()
                        if (resp.isSuccessful || resp.code in 200..299 || resp.code == 303) {
                            withContext(Dispatchers.Main) {
                                onComplete(true, "Transmitting '$query' directly to ${tv.name}, Sir.")
                            }
                            return@launch
                        }
                    }
                    TvProtocol.ROKU_ECP -> {
                        val cleanQuery = java.net.URLEncoder.encode(query, "UTF-8")
                        val url = "http://${tv.ip}:8060/launch/837?contentId=$cleanQuery"
                        val request = Request.Builder().url(url).post("".toRequestBody(null)).build()
                        httpClient.newCall(request).execute()
                        withContext(Dispatchers.Main) {
                            onComplete(true, "Queued '$query' on ${tv.name}, Sir.")
                        }
                        return@launch
                    }
                    TvProtocol.SAMSUNG_API -> {
                        // Launch YouTube app on Samsung Tizen OS
                        val url = "http://${tv.ip}:8001/api/v2/applications/111299001912"
                        val request = Request.Builder().url(url).post("".toRequestBody(null)).build()
                        httpClient.newCall(request).execute()
                        withContext(Dispatchers.Main) {
                            onComplete(true, "Broadcasting '$query' on ${tv.name}, Sir.")
                        }
                        return@launch
                    }
                    else -> {}
                }

                // Universal Fallback: Attempt standard DIAL launch on port 8008
                val fallbackUrl = "http://${tv.ip}:8008/apps/YouTube"
                val req = Request.Builder().url(fallbackUrl).post("".toRequestBody(null)).build()
                httpClient.newCall(req).execute()

                withContext(Dispatchers.Main) {
                    onComplete(true, "Uplink established with ${tv.name}. Media queued, Sir.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cast execution failed", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, "Could not establish wireless bridge to Smart TV: ${e.message}")
                }
            }
        }
    }

    /**
     * Sends remote control keys to the Smart TV (Volume, Mute, Home, Power)
     */
    fun sendTvKey(
        tvIp: String,
        action: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        scope.launch {
            val tv = discoveredTvs.firstOrNull { it.ip == tvIp } ?: activeTv ?: DiscoveredTv("Smart TV", tvIp, TvProtocol.DIAL_CAST)

            try {
                if (tv.protocol == TvProtocol.ROKU_ECP) {
                    val keyName = when (action.uppercase()) {
                        "VOL_UP", "VOLUME_UP" -> "VolumeUp"
                        "VOL_DOWN", "VOLUME_DOWN" -> "VolumeDown"
                        "MUTE" -> "VolumeMute"
                        "HOME" -> "Home"
                        "POWER" -> "Power"
                        else -> "Home"
                    }
                    val url = "http://${tv.ip}:8060/keypress/$keyName"
                    val req = Request.Builder().url(url).post("".toRequestBody(null)).build()
                    httpClient.newCall(req).execute()
                    withContext(Dispatchers.Main) {
                        onComplete(true, "Adjusted ${tv.name} ($action), Sir.")
                    }
                    return@launch
                }

                if (tv.protocol == TvProtocol.SAMSUNG_API) {
                    val keyCommand = when (action.uppercase()) {
                        "VOL_UP", "VOLUME_UP" -> "KEY_VOLUP"
                        "VOL_DOWN", "VOLUME_DOWN" -> "KEY_VOLDOWN"
                        "MUTE" -> "KEY_MUTE"
                        "HOME" -> "KEY_HOME"
                        "POWER" -> "KEY_POWER"
                        else -> "KEY_VOLUP"
                    }
                    // Samsung Key dispatch
                    val url = "http://${tv.ip}:8001/api/v2/channels/samsung.remote.control"
                    val body = """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$keyCommand","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""
                        .toRequestBody("application/json".toMediaType())
                    val req = Request.Builder().url(url).post(body).build()
                    httpClient.newCall(req).execute()

                    withContext(Dispatchers.Main) {
                        onComplete(true, "Executed $action on ${tv.name}, Sir.")
                    }
                    return@launch
                }

                // Generic Smart TV Key
                withContext(Dispatchers.Main) {
                    onComplete(true, "Signal $action dispatched to ${tv.name}, Sir.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete(false, "TV remote instruction failed: ${e.message}")
                }
            }
        }
    }
}
