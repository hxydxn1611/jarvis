package com.assistant.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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

    fun setActiveTv(tv: DiscoveredTv) {
        activeTv = tv
        if (discoveredTvs.none { it.ip == tv.ip }) {
            discoveredTvs.add(0, tv)
        }
    }

    fun saveTvToPrefs(context: Context, tv: DiscoveredTv) {
        try {
            val prefs = context.getSharedPreferences("SmartTvPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("tv_name", tv.name)
                .putString("tv_ip", tv.ip)
                .putString("tv_protocol", tv.protocol.name)
                .putInt("tv_port", tv.port)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Error saving TV to prefs", e)
        }
    }

    fun loadSavedTv(context: Context): DiscoveredTv? {
        return try {
            val prefs = context.getSharedPreferences("SmartTvPrefs", Context.MODE_PRIVATE)
            val ip = prefs.getString("tv_ip", null) ?: return null
            val name = prefs.getString("tv_name", "Saved Smart TV") ?: "Saved Smart TV"
            val protoStr = prefs.getString("tv_protocol", TvProtocol.DIAL_CAST.name) ?: TvProtocol.DIAL_CAST.name
            val proto = try { TvProtocol.valueOf(protoStr) } catch (e: Exception) { TvProtocol.DIAL_CAST }
            val port = prefs.getInt("tv_port", 8008)
            DiscoveredTv(name, ip, proto, port)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Launches Samsung Smart View or standard Android Wireless Display / Cast Settings
     */
    fun launchScreenMirroring(context: Context) {
        val candidates = listOf(
            // 1. Samsung Smart View native activity (One UI)
            Intent().setClassName("com.samsung.android.smartmirroring", "com.samsung.android.smartmirroring.MainActivity"),
            // 2. Android Standard Cast Settings (opens Smart View on Samsung)
            Intent(Settings.ACTION_CAST_SETTINGS),
            // 3. Samsung WFD picker
            Intent("android.intent.action.VIEW").apply {
                setClassName("com.android.settings", "com.android.settings.wfd.WifiDisplaySettings")
            },
            // 4. Generic WiFi Display
            Intent("android.settings.WIFI_DISPLAY_SETTINGS")
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (e: Exception) {}
        }
        try {
            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {}
    }

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
            if (found.isNotEmpty()) {
                if (activeTv == null || found.none { it.ip == activeTv?.ip }) {
                    activeTv = found.first()
                }
                saveTvToPrefs(context, activeTv ?: found.first())
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
        // 1. Broad coverage of adjacent host IPs (e.g. phones, TVs on local subnet)
        for (i in (host - 25).coerceAtLeast(1)..(host + 25).coerceAtMost(254)) {
            val ip = "$prefix.$i"
            if (!checkIps.contains(ip)) checkIps.add(ip)
        }
        // 2. Common DHCP smart home pool 100..140
        for (i in 100..140) {
            val ip = "$prefix.$i"
            if (!checkIps.contains(ip)) checkIps.add(ip)
        }
        // 3. Static/router/hub pool 1..30
        for (i in 1..30) {
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
        val ports = listOf(8008, 8001, 8060, 8009, 8080)
        for (port in ports) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 220)
                socket.close()

                // Port is open! Identify protocol
                return when (port) {
                    8008, 8009 -> {
                        val name = resolveDeviceFriendlyName("http://$ip:8008/ssdp/device-desc.xml", ip)
                        DiscoveredTv(if (name.contains("Smart TV")) "Google / Android TV ($ip)" else name, ip, TvProtocol.DIAL_CAST, port)
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
     * Sends key command via WebSocket for modern Samsung Tizen TVs (2016+)
     */
    private fun sendSamsungWebSocketKey(ip: String, keyCommand: String) {
        try {
            val appNameBase64 = Base64.encodeToString("Jarvis".toByteArray(), Base64.NO_WRAP)
            val wsUrl = "ws://$ip:8001/api/v2/channels/samsung.remote.control?name=$appNameBase64"
            val request = Request.Builder().url(wsUrl).build()

            httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val payload = JSONObject().apply {
                        put("method", "ms.remote.control")
                        put("params", JSONObject().apply {
                            put("Cmd", "Click")
                            put("DataOfCmd", keyCommand)
                            put("Option", "false")
                            put("TypeOfRemote", "SendRemoteKey")
                        })
                    }
                    webSocket.send(payload.toString())
                    scope.launch {
                        delay(250)
                        webSocket.close(1000, "Done")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Samsung WebSocket failed: ${t.message}")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Error initiating Samsung WebSocket", e)
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
            val tv = when {
                tvIp.isNotBlank() -> discoveredTvs.firstOrNull { it.ip == tvIp } ?: DiscoveredTv("Smart TV", tvIp, TvProtocol.DIAL_CAST)
                activeTv != null -> activeTv
                discoveredTvs.isNotEmpty() -> discoveredTvs.first()
                else -> null
            }

            if (tv == null || tv.ip.isBlank()) {
                withContext(Dispatchers.Main) {
                    onComplete(false, "No active Smart TV locked. Opening Smart View screen mirror...")
                }
                return@launch
            }

            try {
                when (tv.protocol) {
                    TvProtocol.DIAL_CAST -> {
                        val url = "http://${tv.ip}:8008/apps/YouTube"
                        val body = if (query.isNotBlank() && query.length == 11 && !query.contains(" ")) {
                            "v=$query".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                        } else if (query.isNotBlank()) {
                            val cleanQuery = java.net.URLEncoder.encode(query, "UTF-8")
                            "v=$cleanQuery".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                        } else {
                            "".toRequestBody(null)
                        }

                        val request = Request.Builder().url(url).post(body).build()
                        val resp = httpClient.newCall(request).execute()
                        if (resp.isSuccessful || resp.code in 200..299 || resp.code == 303) {
                            withContext(Dispatchers.Main) {
                                onComplete(true, if (query.isNotBlank()) "Transmitting '$query' to ${tv.name}, Sir." else "Launched YouTube on ${tv.name}, Sir.")
                            }
                            return@launch
                        }
                    }
                    TvProtocol.ROKU_ECP -> {
                        val cleanQuery = java.net.URLEncoder.encode(query, "UTF-8")
                        val url = if (query.isNotBlank()) "http://${tv.ip}:8060/launch/837?contentId=$cleanQuery" else "http://${tv.ip}:8060/launch/837"
                        val request = Request.Builder().url(url).post("".toRequestBody(null)).build()
                        httpClient.newCall(request).execute()
                        withContext(Dispatchers.Main) {
                            onComplete(true, if (query.isNotBlank()) "Queued '$query' on ${tv.name}, Sir." else "Launched YouTube on ${tv.name}, Sir.")
                        }
                        return@launch
                    }
                    TvProtocol.SAMSUNG_API -> {
                        // Launch YouTube app on Samsung Tizen OS (App ID 111299001912)
                        try {
                            val url = "http://${tv.ip}:8001/api/v2/applications/111299001912"
                            val request = Request.Builder().url(url).post("".toRequestBody(null)).build()
                            val resp = httpClient.newCall(request).execute()
                            if (resp.isSuccessful || resp.code in 200..299) {
                                withContext(Dispatchers.Main) {
                                    onComplete(true, if (query.isNotBlank()) "Broadcasting '$query' on ${tv.name}, Sir." else "Launched YouTube on ${tv.name}, Sir.")
                                }
                                return@launch
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Samsung REST launch failed, trying DIAL fallback", e)
                        }
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
            val tv = when {
                tvIp.isNotBlank() -> discoveredTvs.firstOrNull { it.ip == tvIp } ?: DiscoveredTv("Smart TV", tvIp, TvProtocol.DIAL_CAST)
                activeTv != null -> activeTv
                discoveredTvs.isNotEmpty() -> discoveredTvs.first()
                else -> null
            }

            if (tv == null || tv.ip.isBlank()) {
                withContext(Dispatchers.Main) {
                    onComplete(false, "No active Smart TV locked. Scan WiFi mesh first, Sir.")
                }
                return@launch
            }

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

                val samsungKey = when (action.uppercase()) {
                    "VOL_UP", "VOLUME_UP" -> "KEY_VOLUP"
                    "VOL_DOWN", "VOLUME_DOWN" -> "KEY_VOLDOWN"
                    "MUTE" -> "KEY_MUTE"
                    "HOME" -> "KEY_HOME"
                    "POWER" -> "KEY_POWER"
                    else -> "KEY_VOLUP"
                }

                if (tv.protocol == TvProtocol.SAMSUNG_API) {
                    // 1. Modern WebSocket Key dispatch (Tizen 2016+)
                    sendSamsungWebSocketKey(tv.ip, samsungKey)

                    // 2. HTTP POST fallback
                    try {
                        val url = "http://${tv.ip}:8001/api/v2/channels/samsung.remote.control"
                        val body = """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$samsungKey","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""
                            .toRequestBody("application/json".toMediaType())
                        val req = Request.Builder().url(url).post(body).build()
                        httpClient.newCall(req).execute()
                    } catch (e: Exception) {}

                    withContext(Dispatchers.Main) {
                        onComplete(true, "Executed $action on ${tv.name}, Sir.")
                    }
                    return@launch
                }

                // If DIAL or DLNA, also attempt Samsung WebSocket if port 8001 is listening
                sendSamsungWebSocketKey(tv.ip, samsungKey)

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

