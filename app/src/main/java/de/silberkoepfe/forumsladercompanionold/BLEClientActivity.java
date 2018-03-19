package de.silberkoepfe.forumsladercompanionold;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.silberkoepfe.forumsladercompanionold.databinding.ActivityBleclientBinding;
import de.silberkoepfe.forumsladercompanionold.databinding.ViewGattServerBinding;
import de.silberkoepfe.util.BluetoothUtils;
import de.silberkoepfe.util.StringUtils;

public class BLEClientActivity extends AppCompatActivity implements GattClientActionListener {

    private static final String TAG = "ClientActivity";

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_FINE_LOCATION = 2;

    private ActivityBleclientBinding mBinding;

    private boolean mScanning;
    private Handler mHandler;
    private Handler mLogHandler;
    private Map<String, BluetoothDevice> mScanResults;

    private boolean mConnected;
    private boolean mTimeInitialized;
    private boolean mEchoInitialized;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private ScanCallback mScanCallback;
    private BluetoothGatt mGatt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        log("onCreate");
        super.onCreate(savedInstanceState);

        mLogHandler = new Handler(Looper.getMainLooper());

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "No Bluetooth support", Toast.LENGTH_LONG).show();
        } else {

            mBinding = DataBindingUtil.setContentView(this, R.layout.activity_bleclient);
            @SuppressLint("HardwareIds")
            String deviceInfo = "Device Info"
                    + "\nName: " + mBluetoothAdapter.getName()
                    + "\nAddress: " + mBluetoothAdapter.getAddress();
            mBinding.clientDeviceInfoTextView.setText(deviceInfo);
            mBinding.startScanningButton.setOnClickListener(v -> startScan());
            mBinding.stopScanningButton.setOnClickListener(v -> stopScan());
            mBinding.sendMessageButton.setOnClickListener(v -> sendMessage());
            mBinding.disconnectButton.setOnClickListener(v -> disconnectGattServer());
            mBinding.viewClientLog.clearLogButton.setOnClickListener(v -> clearLogs());
        }
    }

    @Override
    protected void onResume() {
        log("onResume");
        super.onResume();

        // Check low energy support
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            // Get a newer device
            logError("No LE Support.");
            finish();
        }
    }

    private void startScan() {
        if (!hasPermissions() || mScanning) {
            return;
        }

        disconnectGattServer();

        mBinding.serverListContainer.removeAllViews();

        mScanResults = new HashMap<>();
        mScanCallback = new BtleScanCallback(mScanResults);

        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        // Note: Filtering does not work the same (or at all) on most devices. It also is unable to
        // search for a mask or anything less than a full UUID.
        // Unless the full UUID of the server is known, manual filtering may be necessary.
        // For example, when looking for a brand of device that contains a char sequence in the UUID
        ScanFilter scanFilter = new ScanFilter.Builder()
                //.setServiceUuid(new ParcelUuid(UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")))
                .build();
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(scanFilter);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build();

        mBluetoothLeScanner.startScan(filters, settings, mScanCallback);

        mHandler = new Handler();
        mHandler.postDelayed(this::stopScan, 5000);

        mScanning = true;
        log("Started scanning.");
    }

    private void stopScan() {
        if (mScanning && mBluetoothAdapter != null && mBluetoothAdapter.isEnabled() && mBluetoothLeScanner != null) {
            mBluetoothLeScanner.stopScan(mScanCallback);
            scanComplete();
        }

        mScanCallback = null;
        mScanning = false;
        mHandler = null;
        log("Stopped scanning.");
    }

    private void scanComplete() {
        if (mScanResults.isEmpty()) {
            return;
        }

        for (String deviceAddress : mScanResults.keySet()) {
            BluetoothDevice device = mScanResults.get(deviceAddress);
            GattServerViewModel viewModel = new GattServerViewModel(device);

            ViewGattServerBinding binding = DataBindingUtil.inflate(LayoutInflater.from(this),
                    R.layout.view_gatt_server,
                    mBinding.serverListContainer,
                    true);
            binding.setViewModel(viewModel);
            binding.connectGattServerButton.setOnClickListener(v -> connectDevice(device));
        }
    }

    private boolean hasPermissions() {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            requestBluetoothEnable();
            return false;
        } else if (!hasLocationPermissions()) {
            requestLocationPermission();
            return false;
        }
        return true;
    }

    private void requestBluetoothEnable() {
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        log("Requested user enables Bluetooth. Try starting the scan again.");
    }

    private boolean hasLocationPermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_FINE_LOCATION);
        log("Requested user enable Location. Try starting the scan again.");
    }

    private class BtleScanCallback extends ScanCallback {

        private Map<String, BluetoothDevice> mScanResults;

        BtleScanCallback(Map<String, BluetoothDevice> scanResults) {
            mScanResults = scanResults;
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            log("onScanResult");
            addScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            log("onBatchScanResults");
            for (ScanResult result : results) {
                addScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("onScanFailed");
            logError("BLE Scan Failed with code " + errorCode);
        }

        private void addScanResult(ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String deviceAddress = device.getAddress();

            log("found device: " + device.getName() + " with address " + deviceAddress);

            //if (device.getName())

            mScanResults.put(deviceAddress, device);
        }
    }

    private void connectDevice(BluetoothDevice device) {
        log("Connecting to " + device.getAddress());
        GattClientCallback gattClientCallback = new GattClientCallback(this);
        mGatt = device.connectGatt(this, false, gattClientCallback);
    }

    private void sendMessage() {
        if (!mConnected || !mEchoInitialized) {
            return;
        }

        BluetoothGattCharacteristic characteristic = BluetoothUtils.findEchoCharacteristic(mGatt);
        if (characteristic == null) {
            logError("Unable to find echo characteristic.");
            disconnectGattServer();
            return;
        }

        String message = mBinding.messageEditText.getText().toString();
        log("Sending message: " + message);

        byte[] messageBytes = StringUtils.bytesFromString(message);
        if (messageBytes.length == 0) {
            logError("Unable to convert message to bytes");
            return;
        }

        characteristic.setValue(messageBytes);
        boolean success = mGatt.writeCharacteristic(characteristic);
        if (success) {
            log("Wrote: " + StringUtils.byteArrayInHexFormat(messageBytes));
        } else {
            logError("Failed to write data");
        }
    }

    private void requestTimestamp() {
        if (!mConnected || !mTimeInitialized) {
            return;
        }

        BluetoothGattCharacteristic characteristic = BluetoothUtils.findTimeCharacteristic(mGatt);
        if (characteristic == null) {
            logError("Unable to find time charactaristic");
            return;
        }

        mGatt.readCharacteristic(characteristic);
    }

    // Logging

    private void clearLogs() {
        mLogHandler.post(() -> mBinding.viewClientLog.logTextView.setText(""));
    }

    @Override
    public void log(String msg) {
        Log.d(TAG, msg);
        if (mLogHandler != null && mBinding != null) {
            mLogHandler.post(() -> {
                mBinding.viewClientLog.logTextView.append(msg + "\n");
                mBinding.viewClientLog.logScrollView.post(() -> mBinding.viewClientLog.logScrollView.fullScroll(View.FOCUS_DOWN));
            });
        }
    }

    @Override
    public void logError(String msg) {
        log("Error: " + msg);
    }

    @Override
    public void setConnected(boolean connected) {
        mConnected = connected;
    }

    @Override
    public void initializeTime() {
        mTimeInitialized = true;
    }

    @Override
    public void initializeEcho() {
        mEchoInitialized = true;
    }

    @Override
    public void disconnectGattServer() {
        log("Closing Gatt connection");
        clearLogs();
        mConnected = false;
        mEchoInitialized = false;
        mTimeInitialized = false;
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
        }
    }
}
