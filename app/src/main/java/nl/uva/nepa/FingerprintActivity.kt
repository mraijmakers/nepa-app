package nl.uva.nepa

import android.os.Bundle
import android.support.design.widget.TextInputEditText
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.Button
import android.widget.TextView
import com.estimote.internal_plugins_api.scanning.BluetoothScanner
import com.estimote.internal_plugins_api.scanning.EstimoteLocation
import com.estimote.internal_plugins_api.scanning.ScanHandler
import com.estimote.scanning_plugin.api.EstimoteBluetoothScannerFactory
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.find
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val TAG = "FingerprintActivity"

private enum class State {
    IDLE,
    SCANNING,
    UPLOADING,
}

private const val SCAN_DURATION = 3L

class FingerprintActivity : AppCompatActivity() {
    private var state: State = State.IDLE

    private lateinit var fingerprintText: TextView
    private lateinit var startInterruptButton: Button
    private lateinit var locationInputText: TextInputEditText
    private lateinit var sectionInputText: TextInputEditText

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val apiClient = ApiClient.create()
    private lateinit var bluetoothScanner: BluetoothScanner

    private var telemetryHandler: ScanHandler? = null
    private var scanTask: Cancellable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fingerprint)

        fingerprintText = find(R.id.textFingerprint)

        startInterruptButton = find(R.id.buttonStartStop)
        startInterruptButton.setOnClickListener {
            startOrInterruptFingerprinting()
        }

        locationInputText = find(R.id.locationEditText)
        sectionInputText = find(R.id.sectionEditText)

        bluetoothScanner = EstimoteBluetoothScannerFactory(applicationContext).getSimpleScanner()
    }

    private fun startOrInterruptFingerprinting() {
        when(state) {
            State.IDLE -> {
                takeFingerprint()
            }
            State.SCANNING -> {
                interruptFingerprinting()
                fingerprintText.text = "Scanning interrupted"
                startInterruptButton.text = "Start"
                state = State.IDLE
            }
            State.UPLOADING -> { /* cannot be interrupted */ }
        }
    }

    private fun takeFingerprint() {
        val location = (locationInputText.text ?: "").toString()

        if (location.isEmpty()) {
            locationInputText.error = "Please enter a location first"
            return
        }

        val section = (sectionInputText.text ?: "").toString()

        if (section.isEmpty()) {
            sectionInputText.error = "Please enter a section first"
            return
        }

        scanTask = scanForPackets(SCAN_DURATION) { packets ->
            val fingerprint = packets.toFingerprint(location, section)
            fingerprintText.text = "${packets.size} packets received, uploading"

            doAsync {
                if (fingerprint.signals.isNotEmpty()) {
                    try {
                        uploadFingerprint(fingerprint)
                    } catch (e: IOException) {
                        Log.e(TAG, "Upload failed", e)
                    }
                }

                runOnUiThread {
                    state = State.IDLE
                    fingerprintText.text = "Uploaded fingerprint successfully: ${packets.size} packets received"
                    startInterruptButton.text = "Start"
                    startOrInterruptFingerprinting()
                }
            }
        }

        startInterruptButton.text = "Interrupt"
        state = State.SCANNING
        scanTask += (0..SCAN_DURATION)
            .map { i ->
                i to "Scanning for packets (${SCAN_DURATION - i}s)"
            }
            .map { (delay, text) ->
                scheduler.schedule({
                    runOnUiThread {
                        fingerprintText.text = text
                    }
                }, delay, TimeUnit.SECONDS)
                    .asCancellable(false)
            }
    }

    private fun scanForPackets(duration: Long, resultCallback: (List<EstimoteLocation>) -> Unit): Cancellable {
        val packets = mutableListOf<EstimoteLocation>()

        telemetryHandler = bluetoothScanner
            .estimoteLocationScan()
            .withBalancedPowerMode()
            .withOnPacketFoundAction { packet: EstimoteLocation ->
                packets.add(packet)
            }
            .withOnScanErrorAction { e: Throwable ->
                Log.e(TAG, "Scan error: $e")
            }
            .start()

        val scanTask = scheduler.schedule({
            runOnUiThread {
                Log.i(TAG, "$duration second scan duration is over, stopping telemetryHandler")
                telemetryHandler?.stop()
                resultCallback(packets.toList())
            }
        }, duration, TimeUnit.SECONDS)

        return cancellable {
            telemetryHandler?.stop()
            scanTask.cancel(false)
        }
    }

    private fun List<EstimoteLocation>.toFingerprint(location: String, section: String): Fingerprint {
        return Fingerprint(location, section, averageRssiPerBeacon())
    }

    private fun List<EstimoteLocation>.averageRssiPerBeacon(): Map<String, Int>
    {
        return map { p -> p.deviceId to averageRssi(p.deviceId) }.toMap()
    }

    private fun List<EstimoteLocation>.averageRssi(beacon: String): Int
    {
        return filter { it.deviceId == beacon }
            .map { it.rssi }
            .average()
            .roundToInt()
    }

    private fun uploadFingerprint(fingerprint: Fingerprint) {
        apiClient.saveFingerprint(fingerprint)
    }

    private fun interruptFingerprinting() {
        scanTask?.cancel()
    }
}
