package nl.uva.nepa

import android.content.Context
import android.os.*
import android.support.design.widget.TextInputEditText
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.Button
import android.widget.TextView
import com.estimote.internal_plugins_api.scanning.BluetoothScanner
import com.estimote.internal_plugins_api.scanning.EstimoteLocation
import com.estimote.scanning_plugin.api.EstimoteBluetoothScannerFactory
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.find
import kotlin.math.min

private const val TAG = "TrainActivity"

private const val TIME_WINDOW = 1000L // 1 second time windows
private const val NUM_FINGERPRINTS_PER_SECTION = 180

class TrainActivity : AppCompatActivity() {
    private var isFingerprinting = false

    private lateinit var statusText: TextView
    private lateinit var startInterruptButton: Button

    private lateinit var locationInputText: TextInputEditText
    private lateinit var sectionInputText: TextInputEditText

    private val apiClient = ApiClient.create()

    private lateinit var bluetoothScanner: BluetoothScanner
    private var scanTask: Cancellable? = null
    private val handler = Handler()

    data class Packet(val receivedTime: Long, val estimoteLocation: EstimoteLocation)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_train)


        statusText = find(R.id.textFingerprint)

        startInterruptButton = find(R.id.buttonStartStop)
        startInterruptButton.setOnClickListener {
            startOrInterruptFingerprinting()
        }

        locationInputText = find(R.id.locationEditText)
        sectionInputText = find(R.id.sectionEditText)

        bluetoothScanner = EstimoteBluetoothScannerFactory(applicationContext).getSimpleScanner()
    }

    private fun startOrInterruptFingerprinting() {
        if (isFingerprinting) {
            interruptFingerprinting()
        } else {
            takeFingerprints(TIME_WINDOW, NUM_FINGERPRINTS_PER_SECTION)
        }
    }

    private fun interruptFingerprinting() {
        handler.removeCallbacksAndMessages(null)
        scanTask?.cancel()
        statusText.text = "Interrupted"
        startInterruptButton.text = "Start"
        isFingerprinting = false
    }

    private fun takeFingerprints(timeWindowMs: Long, numSamples: Int) {
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

        val scanDurationMs = timeWindowMs * numSamples

        scanTask = collectPackets(scanDurationMs) { recordStartTime, recordEndTime, packets ->
            vibrate()

            val fingerprints = splitIntoFingerprintsByTimeWindow(
                location, section, timeWindowMs, numSamples, recordStartTime, recordEndTime, packets
            )

            doAsync {
                apiClient.saveFingerprints(fingerprints)
            }

            statusText.text = "Collected ${fingerprints.size} fingerprints"
            startInterruptButton.text = "Start"
        }

        isFingerprinting = true

        statusText.text = "Collecting fingerprints... (${scanDurationMs / 1000} seconds remaining)"
        startInterruptButton.text = "Interrupt"

        for (i in 1..((scanDurationMs / 1000) - 1)) {
            val secondsLeft = (scanDurationMs / 1000) - i

            handler.postDelayed({
                statusText.text = "Collecting fingerprints... ($secondsLeft seconds remaining)"
            }, i * 1000)
        }
    }

    private fun collectPackets(
        durationMs: Long,
        resultCallback: (recordStartTimeMs: Long, recordEndTimeMs: Long, List<Packet>) -> Unit
    ): Cancellable {
        val receivedPackets = mutableListOf<Packet>()
        val recordStartTime: Long = System.currentTimeMillis()

        val telemetryHandler = bluetoothScanner
            .estimoteLocationScan()
            .withBalancedPowerMode()
            .withOnPacketFoundAction { packet: EstimoteLocation ->
                receivedPackets.add(
                    Packet(
                        System.currentTimeMillis(),
                        packet
                    )
                )
            }
            .withOnScanErrorAction { e: Throwable ->
                Log.e(TAG, "Scan error: $e")
            }
            .start()

        var cancelled = false
        val task = cancellable {
            cancelled = true
            telemetryHandler.stop()
        }

        handler.postDelayed({
            if (!cancelled) {
                telemetryHandler.stop()
                val recordEndTime = System.currentTimeMillis()
                resultCallback(recordStartTime, recordEndTime, receivedPackets)
            }
        }, durationMs)

        return task
    }

    private fun splitIntoFingerprintsByTimeWindow(
        location: String,
        section: String,
        timeWindowMs: Long,
        numFingerprints: Int,
        recordStartTimeMs: Long,
        recordEndTimeMs: Long,
        packets: List<Packet>
    ): List<Fingerprint> {
        var currentWindowStart = recordStartTimeMs
        var currentWindowEnd = min(recordStartTimeMs + timeWindowMs, recordEndTimeMs)

        val fingerprints = mutableListOf<Fingerprint>()

        while (fingerprints.size < numFingerprints && currentWindowEnd <= recordEndTimeMs) {
            val packetsInWindow = packets.filter { p ->
                p.receivedTime in currentWindowStart..(currentWindowEnd - 1)
            }

            fingerprints.add(Fingerprint(
                location,
                section,
                currentWindowStart,
                currentWindowEnd,
                packetsInWindow.map { p ->
                    EstimotePacket(
                        p.estimoteLocation.deviceId,
                        p.estimoteLocation.channel,
                        p.estimoteLocation.measuredPower,
                        p.estimoteLocation.rssi,
                        p.estimoteLocation.macAddress.address,
                        p.estimoteLocation.timestamp
                    )
                }
            ))

            // move window
            currentWindowStart += timeWindowMs
            currentWindowEnd += timeWindowMs
        }

        return fingerprints
    }

    private fun vibrate(durationMs: Long = 1000) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(
                durationMs, VibrationEffect.DEFAULT_AMPLITUDE
            ))
        } else {
            vibrator.vibrate(durationMs)
        }
    }
}
