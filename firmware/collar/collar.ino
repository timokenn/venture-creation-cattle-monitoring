/**
 * Cattle collar — Path A: direct HTTPS POST to Supabase (no Firebase).
 *
 * Board: ESP32 Dev Module
 * Libraries (Arduino IDE → Library Manager):
 *   - Adafruit MPU6050  (+ dependencies: Adafruit Unified Sensor, BusIO)
 *   - OneWire, DallasTemperature
 * WiFi/HTTPClient/WiFiClientSecure ship with the ESP32 board package.
 *
 * Writes ONE row per reading into the existing public.readings table via
 * the ingest-reading Edge Function (x-collar-key shared secret + per-device
 * rate limit; the old open anon INSERT on readings was revoked).
 * The DB fills in id/timestamp; a webhook then classifies the reading
 * against the cow's learned baseline. No Firebase, no cron, no bridge.
 *
 * Config: set DEVICE_ID to a registered cow's device_id — the sketch
 * resolves that cow's UUID once at boot (RPC lookup_cow_id), so you never
 * hardcode a UUID.
 */

#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <OneWire.h>
#include <DallasTemperature.h>

// ---- credentials (inline for easy sharing) -------------------------------
#define WIFI_SSID     "ATMO SR 2"
#define WIFI_PASSWORD "ISI_PASSWORD_WIFI"   // ← only value you must fill in
#define SUPABASE_URL  "https://egzczdexooiurlaadwci.supabase.co"
#define SUPABASE_KEY  "sb_publishable_nfrp3Eo15vd4wwBR8qJJ8A_T8MNUbYT"  // publishable key (RPC lookup)
#define COLLAR_INGEST_KEY "ISI_COLLAR_INGEST_KEY"  // shared device secret — set with `supabase secrets set COLLAR_INGEST_KEY=...`
#define DEVICE_ID     "esp32-demo-normal"   // must match cows.device_id in Supabase (Bella)
// --------------------------------------------------------------------------

#define SDA_PIN 21
#define SCL_PIN 22
#define ONE_WIRE_PIN 4
#define USE_INTERNAL_PULLUP false

// Battery sense: voltage divider (100k top / 100k bottom) from BAT pin to
// GPIO 34 (ADC1_CH6, works with WiFi). scale = (100k+100k)/100k = 2.0.
#define BATTERY_ADC_PIN 34
const float ADC_REF_V = 3.3;    // default full-scale; ~3.9 off USB power
const float ADC_DIVIDER = 2.0;  // (Rtop + Rbottom) / Rbottom
const float BATTERY_FULL_V = 4.2;   // Li-ion / LiPo
const float BATTERY_EMPTY_V = 3.3;

int readBatteryPercent() {
  // 10 mV sigma-delta read is noisy; oversample.
  uint32_t sum = 0;
  for (int i = 0; i < 8; i++) {
    sum += analogReadMilliVolts(BATTERY_ADC_PIN);
    delay(2);
  }
  const float pinV = (sum / 8.0f) / 1000.0f;
  const float cellV = pinV * ADC_DIVIDER;
  const float pct = (cellV - BATTERY_EMPTY_V) / (BATTERY_FULL_V - BATTERY_EMPTY_V) * 100.0f;
  return (int)constrain(pct, 0.0f, 100.0f);
}

// MPU6050 bias calibration (measured zero-motion offset).
float offsetX = 0.74;
float offsetY = -0.16;

// Adafruit's acceleration is m/s² (static az ≈ 9.8) but the pipeline's
// activity index expects g (static ≈ 1.0) — convert before sending.
const float MS2_TO_G = 9.80665;

// Match EXPECTED_SEND_INTERVAL_SECONDS in supabase/functions/_shared/thresholds.ts:
// the offline cron flags a cow silent for 3× this interval.
const unsigned long READ_INTERVAL_MS = 20000;

Adafruit_MPU6050 mpu;
OneWire oneWire(ONE_WIRE_PIN);
DallasTemperature ds18(&oneWire);

bool mpuOk = false;
unsigned long lastRead = 0;
String cowUuid;  // resolved once at boot from DEVICE_ID

bool tryInitMPU() {
  if (mpu.begin(0x68, &Wire)) {
    mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
    mpu.setGyroRange(MPU6050_RANGE_500_DEG);
    mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
    return true;
  }
  return false;
}

void connectWiFi() {
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Menyambung ke WiFi");
  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 20000) {
    delay(300);
    Serial.print(".");
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("WiFi tersambung: " + WiFi.localIP().toString());
  } else {
    Serial.println("Gagal konek WiFi, akan dicoba lagi nanti.");
  }
}

/**
 * One-time boot lookup: DEVICE_ID → cow UUID, via the lookup_cow_id RPC
 * (SECURITY DEFINER). Cows SELECT is now per-owner (cows.owner_id), so a
 * direct anon GET on /rest/v1/cows returns zero rows — the RPC exists
 * precisely so the collar can still resolve its cow with the publishable key.
 */
bool resolveCowUuid() {
  WiFiClientSecure client;
  client.setInsecure();  // TLS without cert pinning — fine with the publishable key
  HTTPClient http;
  String url = String(SUPABASE_URL) + "/rest/v1/rpc/lookup_cow_id";
  if (!http.begin(client, url)) return false;
  http.addHeader("apikey", SUPABASE_KEY);
  http.addHeader("Authorization", "Bearer " + String(SUPABASE_KEY));
  http.addHeader("Content-Type", "application/json");
  const String payload = String("{\"p_device_id\":\"") + DEVICE_ID + "\"}";
  const int code = http.POST(payload);
  const String body = http.getString();
  http.end();
  if (code != 200) {
    Serial.printf("cow lookup failed: HTTP %d %s\n", code, body.c_str());
    return false;
  }
  // Response is a JSON string: "50f5f9b1-..." (or null when not found).
  const int p = body.indexOf('"');
  const int q = body.indexOf('"', p + 1);
  if (p < 0 || q < 0) {
    Serial.println("device_id not found — register this cow first (device_id="
                   DEVICE_ID ")");
    return false;
  }
  cowUuid = body.substring(p + 1, q);
  Serial.println("cow UUID: " + cowUuid);
  return true;
}

void sendReading(float tempC, float ax, float ay, float az,
                 float gx, float gy, float gz) {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("WiFi tidak tersambung, skip kirim.");
    return;
  }
  // No timestamp field: the DB default now() stamps server time. Sent
  // through the hardened ingest-reading function (x-collar-key): the open
  // anon INSERT on readings was revoked.
  int batteryPct = readBatteryPercent();
  String payload = "{";
  payload += "\"device_id\":\"" + String(DEVICE_ID) + "\",";
  payload += "\"temperature\":" + String(tempC, 2) + ",";
  payload += "\"battery_level\":" + String(batteryPct) + ",";
  payload += "\"accel_x\":" + String(ax, 4) + ",";
  payload += "\"accel_y\":" + String(ay, 4) + ",";
  payload += "\"accel_z\":" + String(az, 4) + ",";
  payload += "\"gyro_x\":" + String(gx, 4) + ",";
  payload += "\"gyro_y\":" + String(gy, 4) + ",";
  payload += "\"gyro_z\":" + String(gz, 4);
  payload += "}";

  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;
  String url = String(SUPABASE_URL) + "/functions/v1/ingest-reading";
  if (!http.begin(client, url)) return;
  http.addHeader("Content-Type", "application/json");
  http.addHeader("x-collar-key", COLLAR_INGEST_KEY);

  const int code = http.POST(payload);
  if (code == 200) {
    Serial.printf("terkirim: T=%.2fC a=(%.2f,%.2f,%.2f)g\n", tempC, ax, ay, az);
  } else if (code > 0) {
    Serial.printf("Supabase HTTP %d: %s\n", code, http.getString().c_str());
  } else {
    Serial.printf("gagal kirim: %s\n", http.errorToString(code).c_str());
  }
  http.end();
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println();
  Serial.println("=== ESP32 MPU6050 + DS18B20 -> Supabase (direct) ===");
  Serial.println("firmware v2-bench — cow-range guard DISABLED for testing");

  connectWiFi();
  while (!resolveCowUuid()) {   // hard-fail until the cow exists
    delay(3000);
    if (WiFi.status() != WL_CONNECTED) connectWiFi();
  }

  Wire.begin(SDA_PIN, SCL_PIN);
  mpuOk = tryInitMPU();
  Serial.println(mpuOk ? "MPU6050 OK" : "MPU6050 TIDAK terdeteksi");

  if (USE_INTERNAL_PULLUP) pinMode(ONE_WIRE_PIN, INPUT_PULLUP);
  ds18.begin();
  Serial.print("DS18B20 terdeteksi: ");
  Serial.println(ds18.getDeviceCount());
}

void loop() {
  if (millis() - lastRead < READ_INTERVAL_MS) return;
  lastRead = millis();

  if (WiFi.status() != WL_CONNECTED) connectWiFi();
  if (!mpuOk) mpuOk = tryInitMPU();  // retry on a timer — headers go bad

  float ax = 0, ay = 0, az = 0, gx = 0, gy = 0, gz = 0;
  if (mpuOk) {
    sensors_event_t a, g, t;
    if (mpu.getEvent(&a, &g, &t)) {
      ax = (a.acceleration.x - offsetX) / MS2_TO_G;  // bias first, then → g
      ay = (a.acceleration.y - offsetY) / MS2_TO_G;
      az =  a.acceleration.z / MS2_TO_G;
      gx = g.gyro.x; gy = g.gyro.y; gz = g.gyro.z;
    } else {
      mpuOk = false;
    }
  }
  if (!mpuOk) {
    Serial.println("MPU down — skipping (no fake data)");
    return;
  }

  ds18.requestTemperatures();
  float tempC = ds18.getTempCByIndex(0);

  // TESTING: the normal 30–45°C cow-range guard is intentionally disabled so
  // room-temperature probe readings (~24°C) can flow for bench tests.
  // NOTE: streaming room-temp readings WILL pull Bella's learned baseline
  // downward (EMA) — reset the baseline after bench testing.
  // Still refuse a disconnected probe: −127 is meaningless and the DB CHECK
  // would reject it with 400.
  if (tempC == DEVICE_DISCONNECTED_C) {
    Serial.println("DS18B20: -127 (probe lepas) — tidak dikirim");
    return;
  }

  sendReading(tempC, ax, ay, az, gx, gy, gz);
  Serial.println("-----");
}
