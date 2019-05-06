package nl.uva.nepa

import android.Manifest
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import android.support.design.widget.TextInputEditText
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.TextView
import com.estimote.internal_plugins_api.scanning.EstimoteLocation
import com.estimote.internal_plugins_api.scanning.EstimoteTelemetryFull
import com.estimote.internal_plugins_api.scanning.Packet
import com.estimote.internal_plugins_api.scanning.ScanHandler
import com.estimote.scanning_plugin.api.EstimoteBluetoothScannerFactory
import nl.uva.nepa.api.ApiClient
import nl.uva.nepa.api.DataPoint
import nl.uva.nepa.api.EstimotePacket
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.find
import org.jetbrains.anko.info
import java.util.*

const val TAG = "MainActivity"

val NEEDED_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
const val REQUEST_FINE_LOCATION_CODE = 200

const val PREFERENCE_DEVICE_UUID = "nl.uva.nepa.DEVICE_UUID"

enum class Status(val textRepresentation: String) {
    INITIALIZING("Initializing"),
    REQUESTING_PERMISSIONS("Requesting Bluetooth Permissions"),
    PERMISSIONS_DENIED("This App needs Bluetooth Permissions to function properly"),
    CHECKING_API_STATUS("Checking API Status"),
    API_UNREACHABLE("API is unreachable"),
    SCANNING_FOR_PACKETS("Scanning for Estimote Telemetry Packets"),
}

class MainActivity : AppCompatActivity(), AnkoLogger {

    private lateinit var statusText: TextView
    private var status = Status.REQUESTING_PERMISSIONS

    private lateinit var startStopButton: Button
    private var sendingEnabled = false

    private lateinit var sectionEditText: TextInputEditText
    private var section: String? = null

    private lateinit var packetsReceivedCounterText: TextView
    private var packetsReceived = 0

    private var telemetryHandler: ScanHandler? = null

    private val client = ApiClient.createProductionClient()

    private lateinit var deviceUuid: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceUuid = getDeviceUniqueId()

        statusText = find(R.id.status)
        packetsReceivedCounterText = find(R.id.packetsReceivedCounter)


        startStopButton = find(R.id.buttonStartStop)
        startStopButton.setOnClickListener {
            sendingEnabled = !sendingEnabled
            startStopButton.text = if (sendingEnabled) "Stop" else "Start"
        }

        sectionEditText = find(R.id.sectionEditText)
        sectionEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                section = s.toString()
            }
        })


        find<TextView>(R.id.deviceUuid).text = deviceUuid

        if (shouldRequestPermissions()) {
            requestBluetoothPermissions()
        } else {
            waitUntilApiIsOnline()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        telemetryHandler?.stop()
    }

    private fun updateStatus(newStatus: Status, additionalInfo: String = "")
    {
        this.status = newStatus

        runOnUiThread {
            statusText.text = if (additionalInfo.isNotBlank())
                "${newStatus.textRepresentation}: $additionalInfo"
            else
                newStatus.textRepresentation
        }
    }

    private fun incrementPacketsReceived()
    {
        this.packetsReceived++

        runOnUiThread {
            packetsReceivedCounterText.text = "Packets received: ${this.packetsReceived}"
        }
    }

    private fun shouldRequestPermissions(): Boolean
    {
        return Build.VERSION.SDK_INT > 23
                && NEEDED_PERMISSIONS.any { perm -> checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun requestBluetoothPermissions()
    {
        updateStatus(Status.REQUESTING_PERMISSIONS)

        val requestedPermissions = NEEDED_PERMISSIONS.filter {
                perm -> checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED
        }

        requestPermissions(
            requestedPermissions.toTypedArray(),
            REQUEST_FINE_LOCATION_CODE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = requestCode == REQUEST_FINE_LOCATION_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        if (granted) {
            waitUntilApiIsOnline()
        } else {
            updateStatus(Status.PERMISSIONS_DENIED)
        }
    }

    private fun waitUntilApiIsOnline(interval: Long = 10)
    {
        doAsync {
            updateStatus(Status.CHECKING_API_STATUS)
            var apiStatus = client.getApiStatus()

            while (!apiStatus.available) {
                info("API Unreachable", apiStatus.exception)

                (interval downTo 1).forEach { timeLeft ->
                    updateStatus(Status.API_UNREACHABLE, "Checking again in $timeLeft seconds...")
                    Thread.sleep(1000)
                }

                updateStatus(Status.CHECKING_API_STATUS)
                apiStatus = client.getApiStatus()
            }

            startScanning()
        }
    }

    private fun startScanning()
    {
        updateStatus(Status.SCANNING_FOR_PACKETS)

        val bluetoothScanner = EstimoteBluetoothScannerFactory(applicationContext).getSimpleScanner()

        telemetryHandler = bluetoothScanner
            .estimoteLocationScan()
            .withBalancedPowerMode()
            .withOnPacketFoundAction { packet: EstimoteLocation ->
                incrementPacketsReceived()

                if (sendingEnabled) {
                    postPacketToServer(packet)
                }
            }
            .withOnScanErrorAction {e: Throwable ->
                Log.e(TAG, "Scan error: $e")
            }
            .start()

        runOnUiThread { startStopButton.isEnabled = true }
    }

    private fun postPacketToServer(packet: EstimoteLocation)
    {
        val dataPoint = DataPoint(
            deviceId = deviceUuid,
            deviceTimeStamp = System.currentTimeMillis(),
            section = section,
            estimoteTelemetryPacket = EstimotePacket(
                identifier = packet.deviceId,
                rssi = packet.rssi,
                channel = packet.channel,
                measuredPower =  packet.measuredPower,
                macAddress =  packet.macAddress.address,
                timestamp =  packet.timestamp
            )
        )

        doAsync {
            client.createDataPoint(dataPoint)
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
