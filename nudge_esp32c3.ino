#include <Arduino.h>
#include <NimBLEDevice.h>
#include "esp_sleep.h"
#include "driver/gpio.h"

// Nudge — ESP32-C3 SuperMini firmware (Arduino-ESP32 3.x, NimBLE-Arduino 2.x)
static constexpr uint8_t BUZZER_PIN = 4;
static constexpr uint8_t LED_PIN = 8;       // onboard LED, active LOW
static constexpr gpio_num_t BUTTON_PIN = GPIO_NUM_9; // BOOT, active LOW
static constexpr uint32_t BLE_IDLE_TIMEOUT_MS = 30000;
static constexpr uint16_t ADV_MIN = 1600;   // 1000 ms, units of 0.625 ms
static constexpr uint16_t ADV_MAX = 2000;   // 1250 ms

static const char *DEVICE_NAME = "Nudge-Clip";
static const char *SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b";
static const char *COMMAND_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a8";
static const char *STATUS_UUID  = "beb5483f-36e1-4688-b7f5-ea07361b26a8";

// Light sleep preserves RAM. These values survive the timer sleep/wake cycle.
RTC_DATA_ATTR uint16_t timerMinutes = 0;
RTC_DATA_ATTR uint8_t repeatEnabled = 0;
RTC_DATA_ATTR uint8_t alertPattern = 1;
RTC_DATA_ATTR uint8_t timerArmed = 0;

NimBLEServer *bleServer = nullptr;
NimBLECharacteristic *statusCharacteristic = nullptr;
volatile bool connected = false;
volatile bool commandPending = false;
char commandBuffer[64] = {0};
uint32_t lastActivityMs = 0;
bool bleReady = false;

void setLed(bool on) { digitalWrite(LED_PIN, on ? LOW : HIGH); }

void publishStatus(const String &status) {
  Serial.println(status);
  if (statusCharacteristic) {
    statusCharacteristic->setValue(status.c_str());
    statusCharacteristic->notify();
  }
}

void playPattern(uint8_t pattern) {
  publishStatus("OK:BUZZING");
  setLed(true);
  uint16_t frequency = pattern == 3 ? 2600 : (pattern == 2 ? 1900 : 1400);
  uint8_t count = pattern == 3 ? 5 : (pattern == 2 ? 4 : 2);
  uint16_t onMs = pattern == 2 ? 110 : 180;
  uint16_t offMs = pattern == 2 ? 100 : 220;
  for (uint8_t i = 0; i < count; ++i) {
    tone(BUZZER_PIN, frequency);
    delay(onMs);
    noTone(BUZZER_PIN);
    if (i + 1 < count) delay(offMs);
  }
  setLed(false);
}

void beepConfirmation() {
  tone(BUZZER_PIN, 1500);
  delay(90);
  noTone(BUZZER_PIN);
}

class ServerCallbacks final : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *, NimBLEConnInfo &) override {
    connected = true;
    lastActivityMs = millis();
  }
  void onDisconnect(NimBLEServer *, NimBLEConnInfo &, int) override {
    connected = false;
    setLed(false);
    if (bleReady) NimBLEDevice::getAdvertising()->start();
  }
};

class CommandCallbacks final : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *characteristic, NimBLEConnInfo &) override {
    std::string value = characteristic->getValue();
    if (value.empty()) return;
    // Commands are short ASCII strings. Truncate safely and let loop() execute them.
    size_t n = value.size() < sizeof(commandBuffer) - 1 ? value.size() : sizeof(commandBuffer) - 1;
    memcpy(commandBuffer, value.data(), n);
    commandBuffer[n] = '\0';
    commandPending = true;
    lastActivityMs = millis();
  }
};

void startBle() {
  if (bleReady) return;
  NimBLEDevice::init(DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_N0); // 0 dBm
  bleServer = NimBLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());
  NimBLEService *service = bleServer->createService(SERVICE_UUID);
  NimBLECharacteristic *command = service->createCharacteristic(
      COMMAND_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  command->setCallbacks(new CommandCallbacks());
  statusCharacteristic = service->createCharacteristic(
      STATUS_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  statusCharacteristic->setValue("OK:IDLE");
  service->start();
  NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();
  advertising->setMinInterval(ADV_MIN);
  advertising->setMaxInterval(ADV_MAX);
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->start();
  bleReady = true;
  lastActivityMs = millis();
  publishStatus(timerArmed ? String("OK:ARMED:") + timerMinutes + "m" : "OK:IDLE");
}

void stopBle() {
  if (!bleReady) return;
  connected = false;
  statusCharacteristic = nullptr;
  bleServer = nullptr;
  NimBLEDevice::deinit(true);
  bleReady = false;
}

void enterLightSleep(uint32_t seconds = 0) {
  if (bleReady) stopBle();
  setLed(false);
  noTone(BUZZER_PIN);
  pinMode(static_cast<uint8_t>(BUTTON_PIN), INPUT_PULLUP);
  gpio_wakeup_enable(BUTTON_PIN, GPIO_INTR_LOW_LEVEL);
  esp_sleep_enable_gpio_wakeup();
  while (true) {
    if (seconds > 0) {
      esp_sleep_enable_timer_wakeup(static_cast<uint64_t>(seconds) * 1000000ULL);
    } else {
      // Wake sources remain configured after light sleep; clear an old timer
      // when this sleep is meant to be woken only by the BOOT button.
      esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_TIMER);
    }
    Serial.flush();
    esp_light_sleep_start();
    gpio_wakeup_disable(BUTTON_PIN);
    while (digitalRead(static_cast<uint8_t>(BUTTON_PIN)) == LOW) { delay(15); }

    esp_sleep_wakeup_cause_t cause = esp_sleep_get_wakeup_cause();
    if (cause == ESP_SLEEP_WAKEUP_TIMER && timerArmed) {
      playPattern(alertPattern);
      if (repeatEnabled) {
        // Keep BLE off and sleep for the next interval. Button wake still works.
        seconds = static_cast<uint32_t>(timerMinutes) * 60U;
        gpio_wakeup_enable(BUTTON_PIN, GPIO_INTR_LOW_LEVEL);
        continue;
      }
      timerArmed = 0;
    }
    break;
  }
  startBle();
  lastActivityMs = millis();
}

void processCommand(const char *cmd) {
  lastActivityMs = millis();
  if (strcmp(cmd, "PING") == 0) {
    publishStatus(timerArmed ? String("OK:ARMED:") + timerMinutes + "m" : "OK:IDLE");
    return;
  }
  if (strncmp(cmd, "BUZZ:", 5) == 0) {
    int pattern = atoi(cmd + 5);
    if (pattern < 1 || pattern > 3) { publishStatus("ERR:PATTERN"); return; }
    playPattern(pattern);
    publishStatus(timerArmed ? String("OK:ARMED:") + timerMinutes + "m" : "OK:IDLE");
    return;
  }
  if (strncmp(cmd, "SET:", 4) == 0) {
    unsigned int minutes = 0, repeat = 0, pattern = 0;
    char tail = 0;
    if (sscanf(cmd, "SET:%u:%u:%u%c", &minutes, &repeat, &pattern, &tail) != 3 ||
        minutes < 1 || minutes > 1440 || repeat > 1 || pattern < 1 || pattern > 3) {
      publishStatus("ERR:SET"); return;
    }
    timerMinutes = minutes;
    repeatEnabled = repeat;
    alertPattern = pattern;
    timerArmed = 1;
    beepConfirmation();
    publishStatus(String("OK:ARMED:") + timerMinutes + "m");
    delay(120);
    publishStatus("OK:SLEEPING");
    enterLightSleep(static_cast<uint32_t>(timerMinutes) * 60U);
    return;
  }
  if (strcmp(cmd, "STOP") == 0) {
    timerArmed = 0;
    timerMinutes = 0;
    repeatEnabled = 0;
    publishStatus("OK:IDLE");
    return;
  }
  if (strcmp(cmd, "SLEEP") == 0) {
    publishStatus("OK:SLEEPING");
    delay(120);
    enterLightSleep();
    return;
  }
  publishStatus("ERR:COMMAND");
}

void setup() {
  setCpuFrequencyMhz(80);
  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(LED_PIN, OUTPUT);
  pinMode(static_cast<uint8_t>(BUTTON_PIN), INPUT_PULLUP);
  setLed(false);
  noTone(BUZZER_PIN);
  Serial.begin(115200);
  delay(100);

  esp_sleep_wakeup_cause_t cause = esp_sleep_get_wakeup_cause();
  if (cause == ESP_SLEEP_WAKEUP_TIMER && timerArmed) {
    playPattern(alertPattern);
    if (!repeatEnabled) timerArmed = 0;
  }
  startBle();
}

void loop() {
  if (commandPending) {
    char local[sizeof(commandBuffer)];
    noInterrupts();
    strncpy(local, commandBuffer, sizeof(local));
    local[sizeof(local) - 1] = '\0';
    commandPending = false;
    interrupts();
    processCommand(local);
  }

  if (bleReady && !connected && millis() - lastActivityMs >= BLE_IDLE_TIMEOUT_MS) {
    if (timerArmed) {
      publishStatus("OK:SLEEPING");
      enterLightSleep(static_cast<uint32_t>(timerMinutes) * 60U);
    } else {
      enterLightSleep();
    }
  }
  delay(10);
}
