package vn.nudge.clip;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SERVICE = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    private static final UUID COMMAND = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");
    private static final UUID STATUS = UUID.fromString("beb5483f-36e1-4688-b7f5-ea07361b26a8");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int PERMISSIONS = 41;
    private WebView web;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandChar;
    private BluetoothGattCharacteristic statusChar;
    private boolean scanStarted;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(this, "NudgeBle");
        setContentView(web);
        web.loadUrl("file:///android_asset/nudge_app.html");
        BluetoothManager manager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager.getAdapter();
    }

    @JavascriptInterface public void connect() { runOnUiThread(() -> {
        if (!hasPermissions()) { requestPermissions(requiredPermissions(), PERMISSIONS); return; }
        startScan();
    }); }

    @JavascriptInterface public boolean send(String text) {
        BluetoothGatt active = gatt;
        BluetoothGattCharacteristic characteristic = commandChar;
        if (active == null || characteristic == null || text == null) return false;
        characteristic.setValue(text.getBytes(StandardCharsets.UTF_8));
        if (Build.VERSION.SDK_INT >= 33) return active.writeCharacteristic(characteristic, characteristic.getValue(), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == 0;
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        return active.writeCharacteristic(characteristic);
    }

    @JavascriptInterface public void disconnect() {
        runOnUiThread(() -> { if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; commandChar = null; } js("onDisconnect()"); });
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    private boolean hasPermissions() {
        for (String p : requiredPermissions()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == PERMISSIONS && hasPermissions()) startScan();
        else if (request == PERMISSIONS) toast("Cần quyền Bluetooth để kết nối Nudge-Clip");
    }

    private void startScan() {
        if (adapter == null || !adapter.isEnabled()) { toast("Hãy bật Bluetooth rồi thử lại"); return; }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null || scanStarted) return;
        scanStarted = true;
        // Some ESP32 BLE stacks put the local name in the scan response, so
        // controller-side name+UUID filters can hide the device on Android.
        scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        web.postDelayed(() -> { if (scanStarted) { scanner.stopScan(scanCallback); scanStarted = false; toast("Không tìm thấy Nudge-Clip"); } }, 12000);
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, android.bluetooth.le.ScanResult result) {
            if (!scanStarted) return;
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null) name = result.getDevice().getName();
            boolean advertisesService = result.getScanRecord() != null
                    && result.getScanRecord().getServiceUuids() != null
                    && result.getScanRecord().getServiceUuids().contains(new ParcelUuid(SERVICE));
            if (!"Nudge-Clip".equals(name) && !advertisesService) return;
            scanStarted = false;
            adapter.getBluetoothLeScanner().stopScan(this);
            toast("Đang kết nối Nudge-Clip…");
            gatt = result.getDevice().connectGatt(MainActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        }
        @Override public void onScanFailed(int errorCode) {
            scanStarted = false;
            toast("Không quét được Bluetooth (mã " + errorCode + ")");
        }
    };
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt active, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) active.discoverServices();
            else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                commandChar = null;
                active.close();
                if (gatt == active) gatt = null;
                js("onDisconnect()");
                if (status != BluetoothGatt.GATT_SUCCESS) toast("Kết nối Nudge-Clip thất bại (mã " + status + ")");
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt active, int status) {
            android.bluetooth.BluetoothGattService service = active.getService(SERVICE);
            if (status != BluetoothGatt.GATT_SUCCESS || service == null
                    || service.getCharacteristic(STATUS) == null || service.getCharacteristic(COMMAND) == null) {
                toast("Nudge-Clip không cung cấp dịch vụ BLE phù hợp");
                active.disconnect();
                return;
            }
            statusChar = service.getCharacteristic(STATUS);
            commandChar = service.getCharacteristic(COMMAND);
            active.setCharacteristicNotification(statusChar, true);
            BluetoothGattDescriptor descriptor = statusChar.getDescriptor(CCCD);
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= 33) active.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                else { descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); active.writeDescriptor(descriptor); }
            }
            if (descriptor == null) finishSetup(active);
        }
        @Override public void onDescriptorWrite(BluetoothGatt active, BluetoothGattDescriptor descriptor, int status) {
            if (CCCD.equals(descriptor.getUuid())) finishSetup(active);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt active, BluetoothGattCharacteristic characteristic) {
            if (STATUS.equals(characteristic.getUuid())) publishStatus(characteristic.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt active, BluetoothGattCharacteristic characteristic, byte[] value) { if (STATUS.equals(characteristic.getUuid())) publishStatus(value); }
        @Override public void onCharacteristicRead(BluetoothGatt active, BluetoothGattCharacteristic characteristic, int status) { if (status == BluetoothGatt.GATT_SUCCESS && STATUS.equals(characteristic.getUuid())) publishStatus(characteristic.getValue()); }
        @Override public void onCharacteristicRead(BluetoothGatt active, BluetoothGattCharacteristic characteristic, byte[] value, int status) { if (status == BluetoothGatt.GATT_SUCCESS && STATUS.equals(characteristic.getUuid())) publishStatus(value); }
    };
    private void finishSetup(BluetoothGatt active) {
        if (Build.VERSION.SDK_INT >= 33) active.readCharacteristic(statusChar);
        else active.readCharacteristic(statusChar);
        js("connectedView(true);toast('Đã kết nối với Nudge-Clip')");
    }
    private void publishStatus(byte[] value) {
        String text = new String(value, StandardCharsets.UTF_8).replace("\\", "\\\\").replace("'", "\\'");
        js("showDeviceStatus('" + text + "')");
    }
    private void js(String script) { runOnUiThread(() -> web.evaluateJavascript("(function(){" + script + "})()", null)); }
    private void toast(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show()); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
