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
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
    private lateinit var tvChatLog: TextView
    private lateinit var deviceListView: ListView

    // List Logic
    private val foundDevices = ArrayList<BluetoothDevice>()
    private val deviceListLabels = ArrayList<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    // Bluetooth Tools
    private lateinit var bluetoothAdapter: BluetoothAdapter

    // Connection Variables
    private var connectedGatt: BluetoothGatt? = null // For the Client (Sender)
    private var bluetoothGattServer: BluetoothGattServer? = null // For the Server (Receiver)
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
        tvChatLog = findViewById(R.id.tvChatLog)
        deviceListView = findViewById(R.id.deviceListView)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceListLabels)
        deviceListView.adapter = listAdapter

        // 2. Init Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // 3. Listeners
        btnScan.setOnClickListener { if (hasPermissions()) startScanning() else requestPermissions() }
        btnHost.setOnClickListener { if (hasPermissions()) startHosting() else requestPermissions() }

        deviceListView.setOnItemClickListener { _, _, position, _ ->
            val device = foundDevices[position]
            connectToDevice(device)
        }

        // 4. SEND BUTTON LOGIC (Client Side)
        btnSend.setOnClickListener {
            val message = etMessage.text.toString()
            if (message.isNotEmpty() && connectedGatt != null) {
                sendMessage(message)
            } else {
                Toast.makeText(this, "Not connected or empty message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==========================================
    // CLIENT LOGIC (Scanning & Sending)
    // ==========================================

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        tvStatus.text = "Status: Scanning..."
        deviceListLabels.clear()
        foundDevices.clear()
        listAdapter.notifyDataSetChanged()

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) { tvStatus.text = "Error: BT Off"; return }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    val advertisedName = result.scanRecord?.deviceName
                    val finalName = advertisedName ?: device.name ?: "Unknown"

                    if (!foundDevices.contains(device)) {
                        foundDevices.add(device)
                        deviceListLabels.add("$finalName\n${result.rssi} dBm | ${device.address}")
                        listAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
        scanner.startScan(scanCallback)

        android.os.Handler().postDelayed({
            scanner.stopScan(scanCallback)
            tvStatus.text = "Status: Scan Finished"
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        tvStatus.text = "Status: Connecting..."
        connectedGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread { tvStatus.text = "Connected to ${device.name}" }
                    gatt?.discoverServices() // Discovery is key!
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread { tvStatus.text = "Disconnected" }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun sendMessage(message: String) {
        val service = connectedGatt?.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)

        if (characteristic != null) {
            characteristic.value = message.toByteArray()
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val success = connectedGatt?.writeCharacteristic(characteristic)

            if (success == true) {
                tvChatLog.append("\nMe: $message")
                etMessage.text.clear()
            } else {
                Toast.makeText(this, "Failed to send", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Service not found. Wait a moment.", Toast.LENGTH_SHORT).show()
        }
    }

    // ==========================================
    // SERVER LOGIC (Hosting & Receiving)
    // ==========================================

    @SuppressLint("MissingPermission")
    private fun startHosting() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        // 1. Setup Server Callback (To Receive Messages)
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread { tvStatus.text = "Status: Client Connected!" }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
            ) {
                super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)

                // Acknowledge the write (Important!)
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }

                // Decode the message
                val message = value?.toString(Charsets.UTF_8) ?: ""
                runOnUiThread {
                    tvChatLog.append("\n${device?.address}: $message")
                }
            }
        }

        bluetoothGattServer = bluetoothManager.openGattServer(this, serverCallback)

        // 2. Add Service
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        bluetoothGattServer?.addService(service)

        // 3. Advertise
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        bluetoothLeAdvertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                runOnUiThread { tvStatus.text = "Status: HOSTING (Waiting for messages...)" }
            }
        })
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