package nl.uva.nepa

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.estimote.internal_plugins_api.scanning.EstimoteTelemetryFull
import com.estimote.internal_plugins_api.scanning.ScanHandler
import com.estimote.scanning_plugin.api.EstimoteBluetoothScannerFactory
import com.estimote.scanning_plugin.packet_provider.EstimoteTelemetryFullPacket

const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private lateinit var telemetryHandler: ScanHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bluetoothScanner = EstimoteBluetoothScannerFactory(applicationContext).getSimpleScanner()
        telemetryHandler = bluetoothScanner
            .estimoteTelemetryFullScan()
            .withBalancedPowerMode()
            .withOnPacketFoundAction {  packet: EstimoteTelemetryFull ->
                Log.d(TAG, "Received telemetry packet: $packet")
            }
            .withOnScanErrorAction {e: Throwable ->
                Log.e(TAG, "Scan error: $e")
            }
            .start()
    }
}
