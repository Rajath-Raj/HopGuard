package com.example.hopguard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.ArrayList
import java.util.UUID

// HopGuard Constants
val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

class MainActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var btnScan: Button
    private lateinit var btnHost: Button
    private lateinit var tvStatus: TextView
    private lateinit var deviceListView: ListView

    // List Logic
    private val foundDevices = ArrayList<BluetoothDevice>()
    private val deviceListLabels = ArrayList<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    // Bluetooth Tools
    private lateinit var bluetoothAdapter: BluetoothAdapter

    // Server & Advertiser (For Hosting)
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Setup UI
        btnScan = findViewById(R.id.btnScan)
        btnHost = findViewById(R.id.btnHost)
        tvStatus = findViewById(R.id.tvStatus)
        deviceListView = findViewById(R.id.deviceListView)

        // Setup the List Adapter
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceListLabels)
        deviceListView.adapter = listAdapter

        // 2. Initialize Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // 3. Button Click Listeners
        btnScan.setOnClickListener {
            if (hasPermissions()) {
                startScanning()
            } else {
                requestPermissions()
            }
        }

        btnHost.setOnClickListener {
            if (hasPermissions()) {
                startHosting()
            } else {
                requestPermissions()
            }
        }

        // 4. List Click Listener (To Connect)
        deviceListView.setOnItemClickListener { _, _, position, _ ->
            val device = foundDevices[position]
            connectToDevice(device)
        }
    }

    // ==========================================
    // CLIENT LOGIC (Scanning & Connecting)
    // ==========================================

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        tvStatus.text = "Status: Scanning..."
        deviceListLabels.clear()
        foundDevices.clear()
        listAdapter.notifyDataSetChanged()

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            tvStatus.text = "Error: BT Off"
            return
        }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    // Only add if not already in list
                    if (!foundDevices.contains(device)) {
                        foundDevices.add(device)
                        val name = device.name ?: "Unknown"
                        val rssi = result.rssi
                        deviceListLabels.add("$name\n$rssi dBm | ${device.address}")
                        listAdapter.notifyDataSetChanged()
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                tvStatus.text = "Scan Failed: $errorCode"
            }
        }

        scanner.startScan(scanCallback)

        // Stop scan after 10s
        android.os.Handler().postDelayed({
            scanner.stopScan(scanCallback)
            if (foundDevices.isEmpty()) {
                tvStatus.text = "Status: Scan finished. No devices found."
            } else {
                tvStatus.text = "Status: Scan finished. Select a device."
            }
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        tvStatus.text = "Status: Connecting to ${device.address}..."

        // Connect to the device (Client connects to Server)
        device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread {
                        tvStatus.text = "Status: CONNECTED to ${device.name}"
                        Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
                    }
                    // Important: Discover services immediately
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread { tvStatus.text = "Status: Disconnected" }
                }
            }
        })
    }

    // ==========================================
    // SERVER LOGIC (Hosting & Advertising)
    // ==========================================

    @SuppressLint("MissingPermission")
    private fun startHosting() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        // 1. Open the Server
        bluetoothGattServer = bluetoothManager.openGattServer(this, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread {
                        tvStatus.text = "Status: A Client Connected!"
                        Toast.makeText(this@MainActivity, "Client Connected!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        // 2. Add Service & Characteristic
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        bluetoothGattServer?.addService(service)

        // 3. Start Advertising
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                runOnUiThread { tvStatus.text = "Status: HOSTING (Visible to others)" }
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e("HopGuard", "Advertising failed: $errorCode")
            }
        }

        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ), 1)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ), 1)
        }
    }
}