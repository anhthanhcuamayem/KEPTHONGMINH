package vn.nudge.clip;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.EditText;
import android.text.InputType;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private boolean scanStarted;
    private final Map<String, BluetoothDevice> foundDevices = new LinkedHashMap<>();
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private volatile String selectedAddress;
    private SharedPreferences deviceNames;

    private static final class DeviceSession {
        final String address;
        final String name;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic commandChar;
        BluetoothGattCharacteristic statusChar;
        String lastStatus = "";
        boolean ready;
        DeviceSession(String address, String name) { this.address = address; this.name = name; }
    }

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
        deviceNames = getSharedPreferences("nudge_device_names", MODE_PRIVATE);
    }

    @JavascriptInterface public void connect() { runOnUiThread(() -> {
        if (!hasPermissions()) { requestPermissions(requiredPermissions(), PERMISSIONS); return; }
        startScan();
    }); }

    @JavascriptInterface public boolean send(String text) {
        DeviceSession session = sessions.get(selectedAddress);
        BluetoothGatt active = session == null ? null : session.gatt;
        BluetoothGattCharacteristic characteristic = session == null ? null : session.commandChar;
        if (active == null || characteristic == null || !session.ready || text == null) return false;
        characteristic.setValue(text.getBytes(StandardCharsets.UTF_8));
        if (Build.VERSION.SDK_INT >= 33) return active.writeCharacteristic(characteristic, characteristic.getValue(), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == 0;
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        return active.writeCharacteristic(characteristic);
    }

    @JavascriptInterface public void disconnect() {
        runOnUiThread(() -> {
            DeviceSession session = sessions.get(selectedAddress);
            if (session != null && session.gatt != null) session.gatt.disconnect();
        });
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
        foundDevices.clear();
        // Some ESP32 BLE stacks put the local name in the scan response, so
        // controller-side name+UUID filters can hide the device on Android.
        scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        web.postDelayed(() -> {
            if (scanStarted) {
                scanner.stopScan(scanCallback);
                scanStarted = false;
                showFoundDevices();
            }
        }, 12000);
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, android.bluetooth.le.ScanResult result) {
            if (!scanStarted) return;
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null) name = result.getDevice().getName();
            boolean advertisesService = result.getScanRecord() != null
                    && result.getScanRecord().getServiceUuids() != null
                    && result.getScanRecord().getServiceUuids().contains(new ParcelUuid(SERVICE));
            if ((name == null || !name.startsWith("Nudge-")) && !advertisesService) return;
            foundDevices.put(result.getDevice().getAddress(), result.getDevice());
        }
        @Override public void onScanFailed(int errorCode) {
            scanStarted = false;
            toast("Không quét được Bluetooth (mã " + errorCode + ")");
        }
    };
    private void showFoundDevices() {
        List<String> addresses = new ArrayList<>(foundDevices.keySet());
        for (String address : sessions.keySet()) if (!addresses.contains(address)) addresses.add(address);
        if (addresses.isEmpty()) { toast("Không tìm thấy Nudge-Clip"); return; }
        String[] labels = new String[addresses.size()];
        for (int i = 0; i < addresses.size(); i++) {
            String address = addresses.get(i);
            labels[i] = labelFor(address);
            DeviceSession session = sessions.get(address);
            if (session != null && session.ready) labels[i] += " · Đang kết nối";
            else if (session != null) labels[i] += " · Đang kết nối…";
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Chọn Nudge cần điều khiển")
                .setItems(labels, (d, which) -> connectDevice(addresses.get(which)))
                .setNeutralButton("Đổi tên", (d, which) -> chooseDeviceToRename(addresses, labels))
                .setNegativeButton("Hủy", null)
                .create();
        dialog.show();
    }
    private void chooseDeviceToRename(List<String> addresses, String[] labels) {
        new AlertDialog.Builder(this).setTitle("Chọn Nudge để đổi tên")
                .setItems(labels, (d, which) -> renameDevice(addresses.get(which), labels[which]))
                .setNegativeButton("Hủy", null).show();
    }
    private void renameDevice(String address, String currentName) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(currentName);
        new AlertDialog.Builder(this).setTitle("Đặt tên cho Nudge")
                .setView(input)
                .setPositiveButton("Lưu", (d, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) deviceNames.edit().putString(address, name.replaceAll("[\\p{Cntrl}]", "")).apply();
                    showFoundDevices();
                }).setNegativeButton("Hủy", null).show();
    }
    private void connectDevice(String address) {
        DeviceSession existing = sessions.get(address);
        if (existing != null) {
            activateSession(existing);
            if (existing.ready) {
                if (!existing.lastStatus.isEmpty()) publishStatus(existing, existing.lastStatus.getBytes(StandardCharsets.UTF_8));
                toast("Đang điều khiển " + existing.name);
            }
            return;
        }
        BluetoothDevice selected = foundDevices.get(address);
        if (selected == null) return;
        DeviceSession session = new DeviceSession(address, labelFor(address));
        sessions.put(address, session);
        activateSession(session);
        session.gatt = selected.connectGatt(MainActivity.this, false, createGattCallback(session), BluetoothDevice.TRANSPORT_LE);
        if (session.gatt == null) {
            sessions.remove(address);
            js("onDisconnect()");
            toast("Không mở được kết nối tới " + session.name);
            return;
        }
        toast("Đang kết nối " + session.name + "…");
    }
    private String labelFor(String address) {
        String suffix = address.replace(":", "");
        suffix = suffix.substring(Math.max(0, suffix.length() - 4));
        return deviceNames.getString(address, "Nudge-" + suffix);
    }
    private void activateSession(DeviceSession session) {
        selectedAddress = session.address;
        String escapedName = escapeJs(session.name);
        String escapedAddress = escapeJs(session.address);
        js("selectedDeviceChanged('" + escapedName + "','" + escapedAddress + "')");
        if (session.ready) js("connectedView(true)");
        else js("connectedView(false)");
    }
    private String escapeJs(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
    }
    private BluetoothGattCallback createGattCallback(DeviceSession session) {
        return new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt active, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) active.discoverServices();
            else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                session.ready = false;
                session.commandChar = null;
                session.statusChar = null;
                active.close();
                sessions.remove(session.address);
                if (session.address.equals(selectedAddress)) {
                    DeviceSession fallback = null;
                    for (DeviceSession candidate : sessions.values()) if (candidate.ready) { fallback = candidate; break; }
                    if (fallback != null) {
                        activateSession(fallback);
                        if (!fallback.lastStatus.isEmpty()) publishStatus(fallback, fallback.lastStatus.getBytes(StandardCharsets.UTF_8));
                    }
                    else js("onDisconnect()");
                }
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
            session.statusChar = service.getCharacteristic(STATUS);
            session.commandChar = service.getCharacteristic(COMMAND);
            active.setCharacteristicNotification(session.statusChar, true);
            BluetoothGattDescriptor descriptor = session.statusChar.getDescriptor(CCCD);
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= 33) active.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                else { descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); active.writeDescriptor(descriptor); }
            }
            if (descriptor == null) finishSetup(session, active);
        }
        @Override public void onDescriptorWrite(BluetoothGatt active, BluetoothGattDescriptor descriptor, int status) {
            if (CCCD.equals(descriptor.getUuid())) finishSetup(session, active);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt active, BluetoothGattCharacteristic characteristic) {
            if (STATUS.equals(characteristic.getUuid())) publishStatus(session, characteristic.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt active, BluetoothGattCharacteristic characteristic, byte[] value) { if (STATUS.equals(characteristic.getUuid())) publishStatus(session, value); }
        @Override public void onCharacteristicRead(BluetoothGatt active, BluetoothGattCharacteristic characteristic, int status) { if (status == BluetoothGatt.GATT_SUCCESS && STATUS.equals(characteristic.getUuid())) publishStatus(session, characteristic.getValue()); }
        @Override public void onCharacteristicRead(BluetoothGatt active, BluetoothGattCharacteristic characteristic, byte[] value, int status) { if (status == BluetoothGatt.GATT_SUCCESS && STATUS.equals(characteristic.getUuid())) publishStatus(session, value); }
        };
    }
    private void finishSetup(DeviceSession session, BluetoothGatt active) {
        session.ready = true;
        if (Build.VERSION.SDK_INT >= 33) active.readCharacteristic(session.statusChar);
        else active.readCharacteristic(session.statusChar);
        if (session.address.equals(selectedAddress)) {
            js("connectedView(true);toast('Đã kết nối với " + escapeJs(session.name) + "')");
        }
    }
    private void publishStatus(DeviceSession session, byte[] value) {
        String text = new String(value, StandardCharsets.UTF_8).replace("\\", "\\\\").replace("'", "\\'");
        session.lastStatus = new String(value, StandardCharsets.UTF_8);
        if (session.address.equals(selectedAddress)) js("showDeviceStatus('" + text + "')");
    }
    private void js(String script) { runOnUiThread(() -> web.evaluateJavascript("(function(){" + script + "})()", null)); }
    private void toast(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show()); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
