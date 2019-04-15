package nl.uva.nepa.api

import android.util.Log
import com.estimote.internal_plugins_api.scanning.EstimoteTelemetryFull
import com.google.gson.Gson
import okhttp3.*
import java.io.IOException
import java.util.logging.Logger

private const val TAG = "api"
private val logger: Logger = Logger.getLogger(TAG)

data class DataPoint(
    val deviceId: String,
    val deviceTimeStamp: Long,
    val estimoteTelemetryPacket: EstimoteTelemetryFull
)

data class ApiStatus(
    val available: Boolean,
    val exception: IOException? = null
)

class ApiClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient
) {

    companion object {

        fun createProductionClient() = ApiClient("http://nepa.1dev.nl/api", OkHttpClient())

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

    fun createDataPoint(dataPoint: DataPoint): Boolean {
        val jsonDataPoint = serializeToJson(dataPoint)

        val request = Request.Builder()
            .url("$baseUrl/datapoint")
            .post(RequestBody.create(jsonType, jsonDataPoint))
            .build()

        logger.info("Sending datapoint to server: $jsonDataPoint")

        httpClient.newCall(request).execute().use { response ->
            logger.info("Server returned ${response.code()} ${response.message()}")

            return response.isSuccessful
        }
    }

    private fun serializeToJson(dataPoint: DataPoint): String {
        return Gson().toJson(dataPoint)
    }

}