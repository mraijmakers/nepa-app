package nl.uva.nepa

import android.util.Log
import com.google.gson.GsonBuilder
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.util.logging.Logger

private const val TAG = "api"
private val logger: Logger = Logger.getLogger(TAG)

data class EstimotePacket(
    val identifier: String,
    val channel: Int,
    val measuredPower: Int,
    val rssi: Int,
    val macAddress: String,
    val timestamp: Long
)

data class Packet(
    val deviceId: String,
    val deviceTimeStamp: Long,
    val estimoteTelemetryPacket: EstimotePacket
)

data class ApiStatus(
    val available: Boolean,
    val exception: IOException? = null
)

data class Fingerprint(
    val location: String,
    val section: String,
    val signals: Map<String, Int>
)

class ApiClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient
) {

    private val gson = GsonBuilder().serializeNulls().create()

    companion object {
        fun create() = ApiClient("http://nepa.1dev.nl/api", OkHttpClient())
        fun create(baseUrl: String) = ApiClient(baseUrl, OkHttpClient())
    }

    private val jsonType = MediaType.parse("application/json; charset=utf-8")

    fun getApiStatus(): ApiStatus
    {
        val request = Request.Builder()
            .url("$baseUrl/ping")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use {response ->
                return ApiStatus(response.isSuccessful)
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException while contacting API: ${e.message}", e)
            return ApiStatus(false, e)
        }
    }

    fun savePackets(packets: List<Packet>): Boolean {
        val json = mapOf("packets" to packets).serializeToJson()
        val uri = "$baseUrl/packets"

        return post(uri, json)
    }

    fun saveFingerprint(fingerprint: Fingerprint): Boolean {
        val jsonFingerprint = fingerprint.serializeToJson()
        val uri = "$baseUrl/fingerprint"

        return post(uri, jsonFingerprint)
    }

    private fun Any.serializeToJson(): String {
        return gson.toJson(this)
    }

    private fun post(uri: String, json: String): Boolean {
        val request = Request.Builder()
            .url(uri)
            .post(RequestBody.create(jsonType, json))
            .build()

        logger.info("POST $uri: $json")

        httpClient.newCall(request).execute().use { response ->
            logger.info("POST: $uri HTTP ${response.code()} ${response.message()}")

            return response.isSuccessful
        }
    }
}
