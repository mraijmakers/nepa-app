package nl.uva.nepa

import android.Manifest
import android.annotation.TargetApi
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.Button
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.find

private val NEEDED_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
private const val REQUEST_FINE_LOCATION_CODE = 200

class MainActivity : AppCompatActivity(), AnkoLogger {

    private lateinit var bluetoothPermissionsButton: Button
    private lateinit var trainButton: Button
    private lateinit var liveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bluetoothPermissionsButton = find<Button>(R.id.bluetoothPermissionButton).apply {
            setOnClickListener {
                requestBluetoothPermissions()
            }
        }

        trainButton = find<Button>(R.id.trainButton).apply {
            isEnabled = false
            setOnClickListener {
                startActivity(Intent(applicationContext, TrainActivity::class.java))
            }
        }

        liveButton = find<Button>(R.id.liveButton).apply {
            isEnabled = false
            setOnClickListener {
                startActivity(Intent(applicationContext, LiveActivity::class.java))
            }
        }

        if (!shouldRequestPermissions()) {
            enableButtonsAndRemovePermissionButton()
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
            enableButtonsAndRemovePermissionButton()
        }
    }

    private fun enableButtonsAndRemovePermissionButton() {
        bluetoothPermissionsButton.visibility = View.GONE
        liveButton.isEnabled = true
        trainButton.isEnabled = true
    }
}
