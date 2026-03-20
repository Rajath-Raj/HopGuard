package com.example.hopguard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Base64
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

// HopGuard UUIDs
val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
val UUID_CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class MainActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var switchHost: MaterialSwitch
    private lateinit var fabScan: FloatingActionButton
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnLocation: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var spinnerTarget: Spinner

    // Audio
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private val receivedAudioParts = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>() // PacketID -> {PartIndex -> Data}

    // Recycler Views
    private lateinit var rvDevices: RecyclerView
    private lateinit var rvChat: RecyclerView
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var chatAdapter: ChatAdapter

    // Data
    private val foundDevices = ArrayList<BluetoothDevice>()
    private val chatMessages = ArrayList<ChatMessage>()
    private val MY_ID = Build.MODEL // Simple ID for Mesh
    private val SEPARATOR = "|"
    private val knownPeers = ArrayList<String>().apply { add("ALL") }
    private lateinit var spinnerAdapter: ArrayAdapter<String>
    private val seenPackets = Collections.synchronizedSet(HashSet<String>()) // Deduplication
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Bluetooth Tools
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient

    // Connection Variables
    private var connectedGatt: BluetoothGatt? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private val connectedClients = ArrayList<BluetoothDevice>()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        switchHost = findViewById(R.id.switchHost)
        fabScan = findViewById(R.id.fabScan)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnLocation = findViewById(R.id.btnLocation)
        btnMic = findViewById(R.id.btnMic)
        spinnerTarget = findViewById(R.id.spinnerTarget)
        rvDevices = findViewById(R.id.rvDevices)
        rvChat = findViewById(R.id.rvChat)

        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, knownPeers)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTarget.adapter = spinnerAdapter
        
        rvDevices.layoutManager = LinearLayoutManager(this)
        deviceAdapter = DeviceAdapter(foundDevices) { device -> connectToDevice(device) }
        rvDevices.adapter = deviceAdapter

        rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatAdapter = ChatAdapter(chatMessages) { msg ->
            if (msg.text.startsWith("AUDIO:")) {
                val base64 = msg.text.substringAfter("AUDIO:")
                playAudioFromBase64(base64)
            } else if (msg.text.contains("maps.google.com")) {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(msg.text))
                startActivity(intent)
            }
        }
        rvChat.adapter = chatAdapter

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)

        fabScan.setOnClickListener {
            if (!bluetoothAdapter.isEnabled) {
                Toast.makeText(this, "Please turn on Bluetooth", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (hasPermissions()) startScanning() else requestPermissions()
        }
        
        fabScan.setOnTouchListener(object : View.OnTouchListener {
            var dX = 0f
            var dY = 0f
            var startX = 0f
            var startY = 0f
            
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                 when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = view.x - event.rawX
                        dY = view.y - event.rawY
                        startX = event.rawX
                        startY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        view.x = event.rawX + dX
                        view.y = event.rawY + dY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (Math.abs(event.rawX - startX) < 10 && Math.abs(event.rawY - startY) < 10) {
                            view.performClick()
                        }
                        return true
                    }
                    else -> return false
                }
            }
        })

        switchHost.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!bluetoothAdapter.isEnabled) {
                    Toast.makeText(this, "Please turn on Bluetooth", Toast.LENGTH_SHORT).show()
                    switchHost.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (hasPermissions()) startHosting() else {
                    requestPermissions()
                    switchHost.isChecked = false
                }
            } else {
                stopHosting()
            }
        }

        btnSend.setOnClickListener {
            val message = etMessage.text.toString()
            if (message.isNotEmpty()) {
                val target = spinnerTarget.selectedItem.toString()
                sendMeshMessage(target, message)
            } else {
                Toast.makeText(this, "Empty message", Toast.LENGTH_SHORT).show()
            }
        }

        btnLocation.setOnClickListener {
            if (hasPermissions()) {
                sendLocation()
            } else {
                requestPermissions()
            }
        }

        btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (hasPermissions() && hasAudioPermission()) {
                        startRecording()
                        btnMic.setColorFilter(Color.RED) 
                    } else {
                        if (!hasPermissions()) requestPermissions()
                        if (!hasAudioPermission()) requestAudioPermission()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isRecording) {
                        stopRecordingAndSend()
                        btnMic.setColorFilter(Color.BLACK)
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val locMsg = "https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}"
                val target = spinnerTarget.selectedItem.toString()
                sendMeshMessage(target, locMsg)
            } else {
                Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        try {
            audioFile = File.createTempFile("voice_", ".3gp", cacheDir)
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            updateStatus("Recording...", true)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Recording Failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingAndSend() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            updateStatus("Idle", false)

            audioFile?.let { file ->
                val bytes = FileInputStream(file).readBytes()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val target = spinnerTarget.selectedItem.toString()
                sendAudioChunked(target, base64)
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendAudioChunked(target: String, element: String) {
        val streamId = UUID.randomUUID().toString().substring(0, 5)
        val chunkSize = 100
        val chunks = element.chunked(chunkSize)
        val total = chunks.size

        scope.launch {
            try {
                chunks.forEachIndexed { index, chunk ->
                    if (!isActive) return@forEachIndexed
                    val packetId = UUID.randomUUID().toString().substring(0, 5)
                    val content = "MULTIPART:$streamId:$index:$total:$chunk"
                    val packet = "$target$SEPARATOR$MY_ID$SEPARATOR$packetId$SEPARATOR$content"
                    
                    try {
                         sendRawPacket(packet)
                         seenPackets.add(packetId)
                    } catch (e: Exception) { e.printStackTrace() }
                    
                    delay(50) // Non-blocking delay
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this@MainActivity, "Send Failed", Toast.LENGTH_SHORT).show() }
            }
        }
        
        addMessageToChat("AUDIO:$element", true, "Me")
    }

    private fun sendMeshMessage(targetId: String, content: String) {
        val packetId = UUID.randomUUID().toString().substring(0, 5)
        val packet = "$targetId$SEPARATOR$MY_ID$SEPARATOR$packetId$SEPARATOR$content"
        sendRawPacket(packet)
        addMessageToChat(content, true, "Me")
        etMessage.text.clear()
        seenPackets.add(packetId)
    }

    @SuppressLint("MissingPermission")
    private fun sendRawPacket(packet: String) {
        val payload = packet.toByteArray()

        if (connectedGatt != null) {
            val service = connectedGatt?.getService(SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                connectedGatt?.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = payload
                @Suppress("DEPRECATION")
                connectedGatt?.writeCharacteristic(characteristic)
            }
        } else if (bluetoothGattServer != null && connectedClients.isNotEmpty()) {
            val service = bluetoothGattServer?.getService(SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return
            characteristic.value = payload

            for (client in connectedClients) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothGattServer?.notifyCharacteristicChanged(client, characteristic, false, payload)
                } else {
                    bluetoothGattServer?.notifyCharacteristicChanged(client, characteristic, false)
                }
            }
        } else {
            runOnUiThread { Toast.makeText(this, "No connection to send", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun processIncomingPacket(packet: String) {
        val parts = packet.split(SEPARATOR, limit = 4)
        if (parts.size < 4) return

        val targetId = parts[0].trim()
        val senderId = parts[1].trim()
        val packetId = parts[2].trim()
        val content = parts[3].trim()

        if (seenPackets.contains(packetId)) return
        seenPackets.add(packetId)

        if (targetId == "HELLO") {
            if (!knownPeers.contains(senderId)) {
                knownPeers.add(senderId)
                runOnUiThread {
                    spinnerAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "Found peer: $senderId", Toast.LENGTH_SHORT).show()
                }
            }
            if (bluetoothGattServer != null && !content.contains("REPLY")) {
                 val replyId = UUID.randomUUID().toString().substring(0, 5)
                 val replyPacket = "HELLO$SEPARATOR$MY_ID$SEPARATOR$replyId$SEPARATOR" + "REPLY"
                 sendRawPacket(replyPacket)
            }
            return
        }

        if (!knownPeers.contains(senderId)) {
            knownPeers.add(senderId)
            runOnUiThread { spinnerAdapter.notifyDataSetChanged() }
        }

        if (senderId == MY_ID) return

        if (targetId == "ALL" || targetId == MY_ID) {
            val isMultipart = content.startsWith("MULTIPART:")
            // Fix for "mul" printing: filter truncated multipart indicators
            val isPartial = content.length < 15 && (content.startsWith("MUL", ignoreCase = true))
            
            if (!isMultipart && !isPartial) {
                runOnUiThread {
                    addMessageToChat(content, false, senderId)
                }
            }
        }

        val isBroadcast = targetId == "ALL"
        val amIHost = bluetoothGattServer != null

        if (targetId != MY_ID) {
             if (isBroadcast) {
                 if (amIHost) {
                     runOnUiThread { Toast.makeText(this, "Broadcasting packet from $senderId", Toast.LENGTH_SHORT).show() }
                     sendRawPacket(packet)
                 }
             } else {
                 runOnUiThread { Toast.makeText(this, "Relaying packet from $senderId", Toast.LENGTH_SHORT).show() }
                 sendRawPacket(packet)
             }
        }
        
        if (targetId == "ALL" || targetId == MY_ID) {
            if (content.startsWith("MULTIPART:")) {
                try {
                    val subParts = content.split(":", limit = 5)
                    val streamId = subParts[1]
                    val index = subParts[2].toInt()
                    val total = subParts[3].toInt()
                    val data = subParts[4]

                    if (!receivedAudioParts.containsKey(streamId)) {
                        receivedAudioParts[streamId] = ConcurrentHashMap()
                    }
                    receivedAudioParts[streamId]?.put(index, data)

                    if (receivedAudioParts[streamId]?.size == total) {
                        val sb = StringBuilder()
                        for (i in 0 until total) {
                            sb.append(receivedAudioParts[streamId]?.get(i))
                        }
                        val fullBase64 = sb.toString()
                        receivedAudioParts.remove(streamId)
                        
                        runOnUiThread {
                            addMessageToChat("AUDIO:$fullBase64", false, senderId)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun playAudioFromBase64(base64: String) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val tempCb = File.createTempFile("play_", ".3gp", cacheDir)
            val fos = FileOutputStream(tempCb)
            fos.write(bytes)
            fos.close()

            val mp = MediaPlayer()
            mp.setDataSource(tempCb.absolutePath)
            mp.prepare()
            mp.start()
            Toast.makeText(this, "Playing Audio...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Playback Error", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        updateStatus("Scanning...", false)
        foundDevices.clear()
        deviceAdapter.notifyDataSetChanged()

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    if (!foundDevices.contains(device)) {
                        foundDevices.add(device)
                        runOnUiThread { deviceAdapter.notifyItemInserted(foundDevices.size - 1) }
                    }
                }
            }
        }
        scanner.startScan(scanCallback)
        android.os.Handler(mainLooper).postDelayed({
            scanner.stopScan(scanCallback)
            if (tvStatus.text == "Scanning...") updateStatus("Idle", false)
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        tvStatus.text = "Connecting..."
        connectedGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread { updateStatus("Connected. Requesting MTU...", true) }
                    gatt?.requestMtu(512)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread { updateStatus("Disconnected", false) }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                val msg = if (status == BluetoothGatt.GATT_SUCCESS) "MTU: $mtu" else "MTU Failed ($mtu)"
                runOnUiThread { updateStatus("Connected: ${device.name ?: device.address} ($msg)", true) }
                gatt?.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt?.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                        
                        val helloId = UUID.randomUUID().toString().substring(0, 5)
                        val helloPacket = "HELLO$SEPARATOR$MY_ID$SEPARATOR$helloId$SEPARATOR" + "INIT"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                             gatt.writeCharacteristic(characteristic, helloPacket.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        } else {
                             characteristic.value = helloPacket.toByteArray()
                             gatt.writeCharacteristic(characteristic)
                        }
                    }
                    runOnUiThread { Toast.makeText(this@MainActivity, "Ready to Chat", Toast.LENGTH_SHORT).show() }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val packet = characteristic.value.toString(Charsets.UTF_8)
                processIncomingPacket(packet)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startHosting() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        connectedClients.clear()
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    device?.let { 
                        if (!connectedClients.contains(it)) {
                            connectedClients.add(it)
                            runOnUiThread { updateStatus("Client Joined!", true) }
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    device?.let { connectedClients.remove(it) }
                }
            }

            override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                if (responseNeeded) bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                val packet = value?.toString(Charsets.UTF_8) ?: ""
                processIncomingPacket(packet)
            }

            override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                if (responseNeeded) bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
        bluetoothGattServer = bluetoothManager.openGattServer(this, serverCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
        service.addCharacteristic(characteristic)
        bluetoothGattServer?.addService(service)

        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(true).addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        bluetoothLeAdvertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { runOnUiThread { updateStatus("Hosting...", true) } }
        })
    }

    @SuppressLint("MissingPermission")
    private fun stopHosting() {
        bluetoothLeAdvertiser?.stopAdvertising(object : AdvertiseCallback() {})
        bluetoothGattServer?.close()
        bluetoothGattServer = null
        connectedClients.clear()
        updateStatus("Idle", false)
    }

    private fun updateStatus(status: String, active: Boolean) {
        tvStatus.text = status
        val color = if (active) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                resources.getColor(android.R.color.holo_green_dark, theme)
            } else {
                @Suppress("DEPRECATION")
                resources.getColor(android.R.color.holo_green_dark)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                resources.getColor(android.R.color.darker_gray, theme)
            } else {
                @Suppress("DEPRECATION")
                resources.getColor(android.R.color.darker_gray)
            }
        }
        tvStatus.setTextColor(color)
    }

    private fun addMessageToChat(message: String, isSent: Boolean, sender: String) {
        val timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        chatMessages.add(ChatMessage(message, isSent, sender, timestamp))
        chatAdapter.notifyItemInserted(chatMessages.size - 1)
        rvChat.scrollToPosition(chatMessages.size - 1)
    }

    private fun hasPermissions(): Boolean {
        // Only checking Location/Bluetooth permissions here (for Scanning/Connecting/Hosting)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions, 1)
    }

    private fun hasAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }


    data class ChatMessage(val text: String, val isSent: Boolean, val sender: String, val time: String)

    inner class DeviceAdapter(private val devices: List<BluetoothDevice>, private val onClick: (BluetoothDevice) -> Unit) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvDeviceName)
            val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))
        @SuppressLint("MissingPermission")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.tvName.text = device.name ?: "Unknown"
            holder.tvAddress.text = device.address
            holder.itemView.setOnClickListener { onClick(device) }
        }
        override fun getItemCount() = devices.size
    }

    inner class ChatAdapter(
        private val messages: List<ChatMessage>,
        private val onClick: (ChatMessage) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class SentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvMessage: TextView = view.findViewById(R.id.tvMessage)
            val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
        }
        inner class ReceivedViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSender: TextView = view.findViewById(R.id.tvSender)
            val tvMessage: TextView = view.findViewById(R.id.tvMessage)
            val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
        }
        override fun getItemViewType(position: Int) = if (messages[position].isSent) 1 else 2
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) SentViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_sent, parent, false))
            else ReceivedViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_received, parent, false))
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val msg = messages[position]
            if (holder is SentViewHolder) { 
                if (msg.text.startsWith("AUDIO:")) holder.tvMessage.text = "🔊 Voice Message"
                else holder.tvMessage.text = msg.text
                holder.tvTimestamp.text = msg.time
                holder.itemView.setOnClickListener { onClick(msg) }
            }
            else if (holder is ReceivedViewHolder) { 
                holder.tvSender.text = msg.sender
                if (msg.text.startsWith("AUDIO:")) holder.tvMessage.text = "🔊 Voice Message"
                else holder.tvMessage.text = msg.text
                holder.tvTimestamp.text = msg.time
                holder.itemView.setOnClickListener { onClick(msg) }
            }
        }
        override fun getItemCount() = messages.size
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
