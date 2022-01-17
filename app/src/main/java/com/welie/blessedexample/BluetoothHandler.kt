package com.welie.blessedexample

import android.content.Context
import android.util.Log
import com.welie.blessed.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.nio.ByteOrder
import java.util.*

internal class BluetoothHandler private constructor(context: Context) {

    private var currentTimeCounter = 0
    val bloodpressureChannel = Channel<BloodPressureMeasurement>(UNLIMITED)


    private fun handlePeripheral(peripheral: BluetoothPeripheral) {
        scope.launch(Dispatchers.IO) {
            try {
                val mtu = peripheral.requestMtu(185)
                Timber.i("MTU is $mtu")

                peripheral.requestConnectionPriority(ConnectionPriority.HIGH)

                val rssi = peripheral.readRemoteRssi()
                Timber.i("RSSI is $rssi")

                peripheral.getCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID)?.let {
                    val manufacturerName = peripheral.readCharacteristic(it).asString()
                    Timber.i("Received: $manufacturerName")
                }

                val model = peripheral.readCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID).asString()
                Timber.i("Received: $model")

                val batteryLevel = peripheral.readCharacteristic(BTS_SERVICE_UUID, BATTERY_LEVEL_CHARACTERISTIC_UUID).asUInt8()
                Timber.i("Battery level: $batteryLevel")

                // Turn on notifications for Current Time Service and write it if possible
                peripheral.getCharacteristic(CTS_SERVICE_UUID, CURRENT_TIME_CHARACTERISTIC_UUID)?.let {
                    // If it has the write property we write the current time
                    if (it.supportsWritingWithResponse()) {
                        // Write the current time unless it is an Omron device
                        if (!peripheral.name.contains("BLEsmart_")) {
                            val parser = BluetoothBytesParser(ByteOrder.LITTLE_ENDIAN)
                            parser.setCurrentTime(Calendar.getInstance())
                            peripheral.writeCharacteristic(it, parser.value, WriteType.WITH_RESPONSE)
                        }
                    }
                }

                setupBLPnotifications(peripheral)
                setupCTSnotifications(peripheral)

                peripheral.getCharacteristic(CONTOUR_SERVICE_UUID, CONTOUR_CLOCK)?.let {
                    writeContourClock(peripheral)
                }
            } catch (e: IllegalArgumentException) {
                Timber.e(e)
            } catch (b: GattException) {
                Timber.e(b)
            }
        }
    }


    private suspend fun writeContourClock(peripheral: BluetoothPeripheral) {
        val calendar = Calendar.getInstance()
        val offsetInMinutes = calendar.timeZone.rawOffset / 60000
        calendar.timeZone = TimeZone.getTimeZone("UTC")
        val parser = BluetoothBytesParser(ByteOrder.LITTLE_ENDIAN)
        parser.setIntValue(1, BluetoothBytesParser.FORMAT_UINT8)
        parser.setIntValue(calendar[Calendar.YEAR], BluetoothBytesParser.FORMAT_UINT16)
        parser.setIntValue(calendar[Calendar.MONTH] + 1, BluetoothBytesParser.FORMAT_UINT8)
        parser.setIntValue(calendar[Calendar.DAY_OF_MONTH], BluetoothBytesParser.FORMAT_UINT8)
        parser.setIntValue(calendar[Calendar.HOUR_OF_DAY], BluetoothBytesParser.FORMAT_UINT8)
        parser.setIntValue(calendar[Calendar.MINUTE], BluetoothBytesParser.FORMAT_UINT8)
        parser.setIntValue(calendar[Calendar.SECOND], BluetoothBytesParser.FORMAT_UINT8)
        parser.setIntValue(offsetInMinutes, BluetoothBytesParser.FORMAT_SINT16)
        peripheral.writeCharacteristic(CONTOUR_SERVICE_UUID, CONTOUR_CLOCK, parser.value, WriteType.WITH_RESPONSE)
    }

    private suspend fun setupCTSnotifications(peripheral: BluetoothPeripheral) {
        peripheral.getCharacteristic(CTS_SERVICE_UUID, CURRENT_TIME_CHARACTERISTIC_UUID)?.let { currentTimeCharacteristic ->
            peripheral.observe(currentTimeCharacteristic) { value ->
                val parser = BluetoothBytesParser(value)
                val currentTime = parser.dateTime
                Timber.i("Received device time: %s", currentTime)

                // Deal with Omron devices where we can only write currentTime under specific conditions
                val name = peripheral.name
                if (name.contains("BLEsmart_")) {
                    peripheral.getCharacteristic(BLP_SERVICE_UUID, BLP_MEASUREMENT_CHARACTERISTIC_UUID)?.let {
                        val isNotifying = peripheral.isNotifying(it)
                        if (isNotifying) currentTimeCounter++

                        // We can set device time for Omron devices only if it is the first notification and currentTime is more than 10 min from now
                        val interval = Math.abs(Calendar.getInstance().timeInMillis - currentTime.time)
                        if (currentTimeCounter == 1 && interval > 10 * 60 * 1000) {
                            parser.setCurrentTime(Calendar.getInstance())
                            scope.launch {
                                peripheral.writeCharacteristic(it, parser.value, WriteType.WITH_RESPONSE)
                            }
                        }
                    }
                }
            }
        }
    }


    private suspend fun setupBLPnotifications(peripheral: BluetoothPeripheral) {
        peripheral.getCharacteristic(BLP_SERVICE_UUID, BLP_MEASUREMENT_CHARACTERISTIC_UUID)?.let {
            peripheral.observe(it) { value ->
                val measurement = BloodPressureMeasurement.fromBytes(value)
                bloodpressureChannel.trySend(measurement)
                Timber.d("%s", measurement)
            }
        }
    }



    private fun startScanning() {
        central.scanForPeripheralsWithNames(arrayOf("MyESP32")) { peripheral, scanResult ->
//        central.scanForPeripheralsWithServices(arrayOf(BLP_SERVICE_UUID)) { peripheral, scanResult ->
            Timber.i("Found peripheral '${peripheral.name}' with RSSI ${scanResult.rssi}")
            central.stopScan()
            connectPeripheral(peripheral)
        }
    }

    private fun connectPeripheral(peripheral: BluetoothPeripheral) {
        peripheral.observeBondState {
            Timber.i("Bond state is $it")
        }

        scope.launch {
            try {
                central.connectPeripheral(peripheral)
            } catch (connectionFailed: ConnectionFailedException) {
                Timber.e("connection failed")
            }
        }
    }

    companion object {


        // UUIDs for the Blood Pressure service (BLP)
        private val BLP_SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val BLP_MEASUREMENT_CHARACTERISTIC_UUID : UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

        // UUIDs for the Device Information service (DIS)
        private val DIS_SERVICE_UUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
        private val MANUFACTURER_NAME_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A29-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Current Time service (CTS)
        private val CTS_SERVICE_UUID: UUID = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
        private val CURRENT_TIME_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A2B-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Battery Service (BAS)
        private val BTS_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

        // Contour Glucose Service
        val CONTOUR_SERVICE_UUID: UUID = UUID.fromString("00000000-0002-11E2-9E96-0800200C9A66")
        private val CONTOUR_CLOCK = UUID.fromString("00001026-0002-11E2-9E96-0800200C9A66")


        private var instance: BluetoothHandler? = null
        private val supportedServices = arrayOf(BLP_SERVICE_UUID,)

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): BluetoothHandler {
            if (instance == null) {
                instance = BluetoothHandler(context.applicationContext)
            }
            return requireNotNull(instance)
        }
    }

    @JvmField
    var central: BluetoothCentralManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        Timber.plant(DebugTree())
        central = BluetoothCentralManager(context)

        central.observeConnectionState { peripheral, state ->
            Timber.i("Peripheral ${peripheral.name} has $state")
            when (state) {
                ConnectionState.CONNECTED -> handlePeripheral(peripheral)
                ConnectionState.DISCONNECTED -> scope.launch {
                    delay(15000)
                    central.autoConnectPeripheral(peripheral)
                }
                else -> {
                }
            }
        }

        startScanning()
    }
}