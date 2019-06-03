package nl.uva.nepa

import android.content.Context
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import com.estimote.internal_plugins_api.scanning.EstimoteLocation
import com.estimote.internal_plugins_api.scanning.ScanHandler
import com.estimote.scanning_plugin.api.EstimoteBluetoothScannerFactory
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.find
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val TAG = "LiveActivity"
private const val PREFERENCE_DEVICE_UUID = "nl.uva.nepa.DEVICE_UUID"

private const val SEND_PACKETS_INTERVAL = 3L // seconds

class LiveActivity : AppCompatActivity() {
    private lateinit var deviceUuidTextView: TextView
    private lateinit var deviceUuid: String

    private lateinit var scanIndicator: ProgressBar

    private lateinit var packetCounter: TextView
    private var receivedPackets = 0

    private val apiClient = ApiClient.create()

    private var telemetryHandler: ScanHandler? = null
    private val packets = mutableListOf<Packet>()

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        deviceUuid = getDeviceUniqueId()

        deviceUuidTextView = find(R.id.deviceUuidText)
        deviceUuidTextView.text = deviceUuid

        scanIndicator = find(R.id.scanIndicator)
        packetCounter = find(R.id.packetCounter)

        telemetryHandler = EstimoteBluetoothScannerFactory(applicationContext).getSimpleScanner()
            .estimoteLocationScan()
            .withBalancedPowerMode()
            .withOnPacketFoundAction { packet: EstimoteLocation ->
                incrementPacketCounter()
                packets.add(Packet(
                    deviceUuid,
                    System.currentTimeMillis(),
                    EstimotePacket(
                        packet.deviceId,
                        packet.channel,
                        packet.measuredPower,
                        packet.rssi,
                        packet.macAddress.address,
                        packet.timestamp
                    )
                ))
            }
            .withOnScanErrorAction { e: Throwable ->
                Log.e(TAG, "Scan error: $e")
            }
            .start()

        scheduler.scheduleAtFixedRate({
            Log.i(TAG, "Scheduled flush")
            runOnUiThread {
                postPacketsToServer()
            }
        }, SEND_PACKETS_INTERVAL, SEND_PACKETS_INTERVAL, TimeUnit.SECONDS)
    }

    private fun incrementPacketCounter() {
        receivedPackets++
        packetCounter.text = receivedPackets.toString()
    }

    private fun postPacketsToServer()
    {
        // shallow copy
        val apiPackets = this.packets.map { it}
        this.packets.clear()

        Log.i(TAG, "Sending ${apiPackets.size} packets to server")
        doAsync {
            apiClient.savePackets(apiPackets)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        scheduler.shutdown()
        telemetryHandler?.stop()
    }

    private fun getDeviceUniqueId(): String {
        val preferences = getPreferences(Context.MODE_PRIVATE)

        val uniqueId = preferences.getString(PREFERENCE_DEVICE_UUID, null)

        if (uniqueId != null) {
            return uniqueId
        }

        val newUuid = UUID.randomUUID().toString()

        preferences.edit()
            .putString(PREFERENCE_DEVICE_UUID, newUuid)
            .apply()

        return newUuid
    }
}
