package nl.uva.nepa

import android.content.Context
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import com.estimote.internal_plugins_api.scanning.EstimoteLocation
import com.estimote.internal_plugins_api.scanning.ScanHandler
import com.estimote.scanning_plugin.api.EstimoteBluetoothScannerFactory
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.find
import java.util.*

private const val TAG = "ScanActivity"
private const val PREFERENCE_DEVICE_UUID = "nl.uva.nepa.DEVICE_UUID"

class ScanActivity : AppCompatActivity() {
    private lateinit var deviceUuidTextView: TextView
    private lateinit var deviceUuid: String

    private lateinit var scanIndicator: ProgressBar

    private lateinit var packetCounter: TextView
    private var receivedPackets = 0

    private val apiClient = ApiClient.create()

    private var telemetryHandler: ScanHandler? = null
    private val packets = mutableListOf<EstimoteLocation>()

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
                packets.add(packet)
            }
            .withOnScanErrorAction { e: Throwable ->
                Log.e(TAG, "Scan error: $e")
            }
            .start()
    }

    private fun incrementPacketCounter() {
        receivedPackets++
        packetCounter.text = receivedPackets.toString()
    }

    private fun postPacketToServer(packet: EstimoteLocation)
    {
        val apiPacket = Packet(
            deviceId = deviceUuid,
            deviceTimeStamp = System.currentTimeMillis(),
            estimoteTelemetryPacket = EstimotePacket(
                identifier = packet.deviceId,
                rssi = packet.rssi,
                channel = packet.channel,
                measuredPower = packet.measuredPower,
                macAddress = packet.macAddress.address,
                timestamp = packet.timestamp
            )
        )

        doAsync {
            apiClient.savePacket(apiPacket)
        }
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
