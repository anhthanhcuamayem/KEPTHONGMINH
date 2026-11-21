# Nudge — Smart Reminder Clip

Prototype for ESP32-C3 SuperMini with a single-file mobile Web Bluetooth controller.

## Files

- `nudge_esp32c3.ino` — Arduino firmware.
- `nudge_app.html` — Vietnamese mobile controller.

## Firmware setup

1. Install Arduino IDE and add the Espressif **esp32** board package.
2. Select **ESP32C3 Dev Module** (or the matching ESP32-C3 SuperMini profile), set **CPU Frequency: 80 MHz**, and set **USB CDC On Boot: Enabled**.
3. Install **NimBLE-Arduino 2.x** from Library Manager. This sketch uses the NimBLE-Arduino 2.x callback API.
4. Connect the board over USB, select its port, then open `nudge_esp32c3.ino` and upload.

The board's USB serial output is 115200 baud. Status strings are printed there as well as sent through the BLE status characteristic.

## Wiring

| Part | Connection |
| --- | --- |
| Passive buzzer + | GPIO 4 through 100–220 Ω |
| Passive buzzer − | GND |
| SuperMini onboard LED | GPIO 8, active LOW |
| BOOT button | GPIO 9, active LOW; built-in button on the board |
| LiPo | TP4056 B+ / B− |
| Boost input | TP4056 OUT+ / OUT− |
| Boost output | Regulated 5 V to board 5V and GND |

Confirm your specific SuperMini board's pin labels and boost output voltage before connecting power. Do not connect a LiPo directly to the board's 5V pin. A passive buzzer is driven with `tone()`; an active buzzer also works, though its tone is fixed by the buzzer.

## Phone app

Open `nudge_app.html` in a Web Bluetooth-capable browser. Web Bluetooth generally requires a secure page (HTTPS) or localhost. On Android, Chrome is a typical option. iOS browsers do not currently expose the Web Bluetooth API. The Android APK bundles the controller UI and uses native Bluetooth LE.

Choose a delay (1–1440 minutes), optional repeat, and alert pattern, then tap **Đặt lời nhắc**. BLE stays available while the reminder is armed so the reminder can still be changed or stopped from the app. Before its first phone connection, the clip remains discoverable and does not enter light sleep. After connecting once, when disconnected and idle it advertises for 2 seconds, sleeps for 8 seconds, then advertises again automatically. BOOT can also wake it during that short sleep.

## BLE protocol

- Name: `Nudge-Clip`
- Service: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`
- Command (write / write without response): `beb5483e-36e1-4688-b7f5-ea07361b26a8`
- Status (read / notify): `beb5483f-36e1-4688-b7f5-ea07361b26a8`
- Commands: `BUZZ:1|2|3`, `SET:<minutes>:<repeat 0|1>:<pattern 1|2|3>`, `STOP`, `SLEEP`, `PING`
- Status: `OK:IDLE`, `OK:BUZZING`, `OK:ARMED:<minutes>m`, `OK:SLEEPING`

## Battery target

The firmware lowers the CPU clock to 80 MHz, advertises at 0 dBm with a 1.0–1.25 second interval, and takes an 8-second Light Sleep break after each 2 seconds of disconnected idle advertising. A 2–3 day runtime depends on battery capacity and the measured average current of the complete board, regulator, and boost-converter power path; it cannot be guaranteed from firmware settings alone. Measure current on the assembled unit to confirm the runtime.

## Android APK

The Android app bundles the controller UI and uses Android's native Bluetooth LE API to connect to `Nudge-Clip` (Web Bluetooth is not available inside Android WebView). Build a debug APK with Android SDK 35 and JDK 17 or newer:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. On first connection, grant the requested Bluetooth permissions and turn on Bluetooth.
