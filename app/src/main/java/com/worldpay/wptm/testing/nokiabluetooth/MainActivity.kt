package com.worldpay.wptm.testing.nokiabluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.*

class MainActivity : AppCompatActivity(), OnRequestPermissionsResultCallback {

    private lateinit var buttonOld: Button
    private lateinit var buttonNew: Button
    private lateinit var buttonGroup: Button
    private lateinit var logOut: TextView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var postScanMode = Mode.OLD
    private var cancelJob: Job? = null
    private val logText = StringBuilder()
    private val scanReceiver = object : BroadcastReceiver() {

        val format = SimpleDateFormat.getTimeInstance()
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    // Discovery has found a device. Get the BluetoothDevice
                    // object and its info from the Intent.
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                    device?.name?.apply { updateLog("Found: $this at ${format.format(Date())}") }

                    if (device?.name?.contains("Worldpay") == true) {
                        cancelJob?.cancel()
                        context.unregisterReceiver(this)
                        bluetoothAdapter?.cancelDiscovery()

                        updateLog("${device.name} is recognised")
                        lifecycleScope.launch(Dispatchers.IO) {
                            when (postScanMode) {
                                Mode.OLD -> openSocketTheOldWay(device)
                                Mode.NEW -> openSocketTheNewWay(device)
                            }
                        }
                    }
                }
                else -> {
                    Log.d(TAG, "ignoring intent: ${intent.action}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().build())

        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        logOut = findViewById(R.id.logOut)
        buttonOld = findViewById(R.id.buttonOld)
        buttonNew = findViewById(R.id.buttonNew)
        buttonGroup = findViewById(R.id.buttonGroup)

        buttonOld.setOnClickListener {

            updateLog("*********************************")
            updateLog("")
            postScanMode = Mode.OLD
            fetchPairedDevice()
                ?.let {
                    lifecycleScope.launch {
                        openSocketTheOldWay(it)
                    }
                } ?: run {
                updateLog("No existing devices found")
                scan()
            }
        }
        buttonNew.setOnClickListener {
            updateLog("*********************************")
            updateLog("")
            postScanMode = Mode.NEW
            fetchPairedDevice()
                ?.let {
                    lifecycleScope.launch {
                        openSocketTheNewWay(it)
                    }
                } ?: run {
                updateLog("No existing devices found")
                scan()
            }
        }

        buttonGroup.setOnClickListener {
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(Manifest.permission_group.NEARBY_DEVICES),
                REQUEST_CODE_PERMISSION_GROUP
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        val p = permissions.fold("") { old, new -> "$old $new"}
        val g = grantResults.fold("") { old, new -> "$old $new"}
        updateLog("onRequestPermissionsResult $requestCode $p $g")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onResume() {
        super.onResume()




        val permissions =
            mutableListOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
                .also {
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
                        it.add(Manifest.permission.BLUETOOTH_CONNECT)
                        it.add(Manifest.permission.BLUETOOTH_SCAN)
                    }
                }.toTypedArray()
//        val permissions = packageManager.queryPermissionsByGroup(Manifest.permission_group.NEARBY_DEVICES, 0)
//            .map { it.name }
//            .toTypedArray()//arrayOf(Manifest.permission_group.NEARBY_DEVICES)

        val allGranted = permissions.fold(true) { granted, permission ->
            val state = ActivityCompat.checkSelfPermission(this, permission)
            val stateName = when (state) {
                PackageManager.PERMISSION_GRANTED -> "granted"
                PackageManager.PERMISSION_DENIED -> "denied"
                else -> "unknown ($state)"
            }
            updateLog("Permission $permission is $stateName")
            state == PackageManager.PERMISSION_GRANTED && granted
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_PERMISSIONS)
        } else {
            if (bluetoothAdapter?.isEnabled == false) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        }

        buttonOld.isEnabled = allGranted
        buttonNew.isEnabled = allGranted
        buttonGroup.visibility = View.GONE
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            checkNearbyDevices()
//            buttonGroup.visibility = View.VISIBLE
//        } else {
//            buttonGroup.visibility = View.GONE
//        }
    }


    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkNearbyDevices() {
        val stateName = when (val groupGranted =
            ActivityCompat.checkSelfPermission(this, Manifest.permission_group.NEARBY_DEVICES)) {
            PackageManager.PERMISSION_GRANTED -> "granted"
            PackageManager.PERMISSION_DENIED -> "denied"
            else -> "unknown ($groupGranted)"
        }
        updateLog("Permission group ${Manifest.permission_group.NEARBY_DEVICES} is $stateName")
    }

    private fun fetchPairedDevice(): BluetoothDevice? {
        updateLog("Looking for a paired device")
        return runCatching {
            bluetoothAdapter
                ?.bondedDevices
                ?.firstOrNull { it.name?.contains("Worldpay") == true }
                ?.also { updateLog("Found paired device: ${it.name}") }
        }.onFailure {
            updateLog("Looking failed: ${it.message}")
            updateLog("----------------------")
        }.getOrNull()
    }


    private fun scan() {
        registerReceiver(scanReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        updateLog("Scanning for bluetooth devices")
        runCatching {
            cancelJob?.cancel()
            bluetoothAdapter?.startDiscovery()

            cancelJob = lifecycleScope.launch {
                delay(5 * DateUtils.MINUTE_IN_MILLIS)
                if (this.isActive) {
                    bluetoothAdapter?.cancelDiscovery()
                    updateLog("Scanning timeout")
                    updateLog("----------------------")
                }
            }
        }.onFailure {
            updateLog("Scanning failed: ${it.message}")
            updateLog("----------------------")
        }
    }

    private fun openSocketTheOldWay(device: BluetoothDevice) {
        updateLog("* Try to open the socket the old way *")
        kotlin.runCatching {
            val m = device.javaClass.getMethod(
                "createRfcommSocket", *arrayOf<Class<*>?>(
                    Int::class.javaPrimitiveType
                )
            )

            val socket = m.invoke(device, Integer.valueOf(1)) as BluetoothSocket?
            socket?.apply {
                connect()
                updateLog("Socket connected")
                close()
                updateLog("Socket closed")
            } ?: updateLog("Created socket is null")

        }.onFailure {
            updateLog("Error opening socket: ${it.message}")
        }

        updateLog("----------------------")
        updateLog("")
    }

    private fun openSocketTheNewWay(device: BluetoothDevice) {
        updateLog("* Try to open the socket the new way *")
        kotlin.runCatching {
            val socket =
                device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            socket?.apply {
                connect()
                updateLog("Socket connected")
                close()
                updateLog("Socket closed")
            } ?: updateLog("Created socket is null")

        }.onFailure {
            updateLog("Error opening socket: ${it.message}")
        }

        updateLog("----------------------")
        updateLog("")
    }

    private fun updateLog(message: String) {
        logText.appendLine(message)
        runOnUiThread {
            logOut.text = logText.toString()
        }
    }

    private companion object {
        const val TAG = "NokiaBtActivity"
        const val REQUEST_CODE_PERMISSIONS = 123
        const val REQUEST_CODE_PERMISSION_GROUP = 121
        const val REQUEST_ENABLE_BT = 111
    }

    enum class Mode {
        OLD,
        NEW
    }
}