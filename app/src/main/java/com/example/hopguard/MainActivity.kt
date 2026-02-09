package com.example.hopguard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*

// HopGuard UUIDs
val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

class MainActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var btnScan: Button
    private lateinit var btnHost: Button
    private lateinit var btnSend: Button
    private lateinit var etMessage: EditText
    private lateinit var tvStatus: TextView
    private lateinit var deviceListView: ListView
    private lateinit var chatRecyclerView: RecyclerView

    // Data Holders
    private val messageList = ArrayList<Message>()
    private lateinit var chatAdapter: ChatAdapter
    private val foundDevices = ArrayList<BluetoothDevice>()
    private val deviceListLabels = ArrayList<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    // Bluetooth Tools
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var connectedGatt: BluetoothGatt? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Setup UI
        btnScan = findViewById(R.id.btnScan)
        btnHost = findViewById(R.id.btnHost)
        btnSend = findViewById(R.id.btnSend)
        etMessage = findViewById(R.id.etMessage)
        tvStatus = findViewById(R.id.tvStatus)
        deviceListView = findViewById(R.id.deviceListView)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)

        // Setup Lists
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceListLabels)
        deviceListView.adapter = listAdapter

        chatAdapter = ChatAdapter(messageList)
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
        chatRecyclerView.adapter = chatAdapter

        // 2. Init Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // 3. Button Listeners
        btnScan.setOnClickListener { if (hasPermissions()) startScanning() else requestPermissions() }
        btnHost.setOnClickListener { if (hasPermissions()) startHosting() else requestPermissions() }

        deviceListView.setOnItemClickListener { _, _, position, _ ->
            val device = foundDevices[position]
            connectToDevice(device)
        }

        btnSend.setOnClickListener {
            val content = etMessage.text.toString()
            if (content.isNotEmpty() && connectedGatt != null) sendMessage(content)
        }
    }

    // ==========================================
    // FIXED SCANNING LOGIC
    // ==========================================
    @SuppressLint("MissingPermission")
    private fun startScanning() {
        tvStatus.text = "Status: Scanning for HopGuard..."

        deviceListLabels.clear()
        foundDevices.clear()
        listAdapter.notifyDataSetChanged()

        deviceListView.visibility = View.VISIBLE
        chatRecyclerView.visibility = View.GONE

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            tvStatus.text = "Error: Bluetooth is OFF"
            return
        }

        // FIX 1: Remove Filter (Some devices struggle with it) -> Filter manually in callback
        /*
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        */

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                runOnUiThread {
                    result?.device?.let { device ->
                        val scanRecord = result.scanRecord
                        val name = scanRecord?.deviceName ?: device.name

                        // Identify HopGuard devices (by UUID or name) but still show all devices.
                        val serviceUuids = scanRecord?.serviceUuids
                        val matchesUuid = serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true
                        val matchesName = name?.contains("HopGuard", ignoreCase = true) == true
                        val isHopGuard = matchesUuid || matchesName

                        val baseName = when {
                            !name.isNullOrEmpty() -> name
                            else -> "Unknown (${device.address})"
                        }
                        val displayName = if (isHopGuard) "⭐ HopGuard: $baseName" else baseName

                        // Avoid duplicates by address
                        val alreadyAdded = foundDevices.any { it.address == device.address }
                        if (!alreadyAdded) {
                            foundDevices.add(device)
                            deviceListLabels.add("$displayName\nSignal: ${result.rssi} dBm")
                            listAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                runOnUiThread { tvStatus.text = "Scan Failed: Code $errorCode" }
            }
        }

        // Start scanning WITHOUT hardware filters (software filter above)
        scanner.startScan(null, settings, scanCallback)

        // Stop after 10 seconds
        android.os.Handler().postDelayed({
            scanner.stopScan(scanCallback)
            if (tvStatus.text.toString().contains("Scanning")) {
                tvStatus.text = "Status: Scan Finished"
            }
        }, 10000)
    }

    // ==========================================
    // CONNECTION & CHAT LOGIC
    // ==========================================
    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        tvStatus.text = "Status: Connecting..."
        // Connect with autoConnect = false for faster connections
        connectedGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread {
                        tvStatus.text = "Connected!"
                        deviceListView.visibility = View.GONE
                        chatRecyclerView.visibility = View.VISIBLE
                    }
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread { tvStatus.text = "Disconnected" }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun sendMessage(content: String) {
        val service = connectedGatt?.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
        if (characteristic != null) {
            characteristic.value = content.toByteArray()
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            connectedGatt?.writeCharacteristic(characteristic)

            val msg = Message(content, true)
            messageList.add(msg)
            chatAdapter.notifyItemInserted(messageList.size - 1)
            chatRecyclerView.scrollToPosition(messageList.size - 1)
            etMessage.text.clear()
        }
    }

    // ==========================================
    // FIXED HOSTING LOGIC
    // ==========================================
    @SuppressLint("MissingPermission")
    private fun startHosting() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        // 1. Setup Server
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread { tvStatus.text = "Client Connected!" }
                }
            }
            override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                if (responseNeeded) bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                val content = value?.toString(Charsets.UTF_8) ?: ""
                runOnUiThread {
                    val msg = Message(content, false)
                    messageList.add(msg)
                    chatAdapter.notifyItemInserted(messageList.size - 1)
                    chatRecyclerView.scrollToPosition(messageList.size - 1)
                }
            }
        }
        bluetoothGattServer = bluetoothManager.openGattServer(this, serverCallback)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
        service.addCharacteristic(characteristic)
        bluetoothGattServer?.addService(service)

        // 2. FIXED ADVERTISING (Split Data to avoid 31-byte limit)
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // Packet 1: Contains UUID (Critical for finding the device)
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // FALSE to save space
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        // Packet 2: Contains Name (Sent only if asked)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                runOnUiThread { tvStatus.text = "Status: HOSTING (Visible)" }
            }
            override fun onStartFailure(errorCode: Int) {
                // Log the specific error to help debug
                val errorMsg = when(errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data Too Large"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature Unsupported"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal Error"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too Many Advertisers"
                    else -> "Error $errorCode"
                }
                runOnUiThread { tvStatus.text = "Hosting Failed: $errorMsg" }
            }
        }

        bluetoothLeAdvertiser?.startAdvertising(settings, advertiseData, scanResponse, callback)
    }

    // ==========================================
    // PERMISSIONS
    // ==========================================
    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions, 1)
    }
}