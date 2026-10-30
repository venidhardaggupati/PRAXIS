/*
  ===========================================================================
  LoRaLink - ESP32-S (classic ESP32) + Ra-02 (SX1278 433MHz) USB Serial Gateway
  ===========================================================================
  Works with the LoRaLink Android app (USB OTG) and with Arduino Serial Monitor.

  Wiring (Ra-02 -> board):
  NSS  --> GPIO 5      RST  --> GPIO 14     DIO0 --> GPIO 26
  SCK  --> GPIO 18     MISO --> GPIO 19     MOSI --> GPIO 23
  VCC --> 3V3 ONLY (never 5V!)      GND --> GND
  Add a 100-470uF capacitor across Ra-02 VCC/GND. Attach the antenna first.

  Arduino IDE: Board = "ESP32 Dev Module"
  Library: "LoRa" by Sandeep Mistry.   Baud: 115200
  Radio: 433 MHz, SF8, BW125k, CR4/5, sync word 0xF3 (must match the other node)
*/

#include <SPI.h>
#include <LoRa.h>

// Ra-02 pins - ESP32-S (classic ESP32, default VSPI)
#define PIN_SCK    18
#define PIN_MISO   19
#define PIN_MOSI   23
#define LORA_SS    5
#define LORA_RST   14
#define LORA_DIO0  26

// Default Radio Parameters for Ra-02 (433 MHz)
long currentFrequency = 433000000; // 433E6 (433 MHz)
int spreadingFactor   = 8;         // SF8 (must match the other node)
const int SYNC_WORD   = 0xF3;      // must match the other node
long signalBandwidth  = 125000;    // 125 kHz
int codingRateDenominator = 5;     // 4/5
int txPower           = 17;        // 17 dBm (Ra-02 max is 20 dBm)

// Serial line buffer
String serialBuffer = "";

void setup() {
  // Initialize USB OTG Serial port at 115200 baud
  Serial.begin(115200);
  delay(1500); // lets native-USB boards (C3) enumerate
  
  Serial.println("STATUS:BOOTING_ESP32_LORA_GATEWAY");
  
  // Configure SPI pins for ESP32
  SPI.begin(PIN_SCK, PIN_MISO, PIN_MOSI, LORA_SS);
  LoRa.setPins(LORA_SS, LORA_RST, LORA_DIO0);

  // Give the Ra-02 time to power up and stabilise (cold-boot fix)
  delay(500);

  // Initialize SX1278 at 433 MHz. Retry (never hang); restart if it keeps failing.
  int attempts = 0;
  while (!LoRa.begin(currentFrequency)) {
    Serial.println("STATUS:RETRYING_LORA_INIT");
    delay(500);
    if (++attempts >= 10) {
      Serial.println("STATUS:LORA_INIT_FAILED_RESTARTING");
      delay(200);
      ESP.restart();
    }
  }

  // Set default radio modem parameters
  LoRa.setSpreadingFactor(spreadingFactor);
  LoRa.setSignalBandwidth(signalBandwidth);
  LoRa.setCodingRate4(codingRateDenominator);
  LoRa.setTxPower(txPower);
  LoRa.setSyncWord(SYNC_WORD);
  LoRa.enableCrc();

  Serial.println("STATUS:LORA_READY");
  Serial.print("STATUS:FREQ:");
  Serial.println(currentFrequency);
}

void loop() {
  // 1. Check for incoming Serial commands from Smartphone over USB OTG
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (serialBuffer.length() > 0) {
        processPhoneCommand(serialBuffer);
        serialBuffer = "";
      }
    } else {
      if (serialBuffer.length() < 300) {
        serialBuffer += c;
      }
    }
  }

  // 2. Check for incoming RF packets over LoRa
  int packetSize = LoRa.parsePacket();
  if (packetSize) {
    String incomingPayload = "";
    while (LoRa.available()) {
      incomingPayload += (char)LoRa.read();
    }
    
    // Drop corrupted / non-text packets (random binary noise) before the phone sees them
    if (!isCleanText(incomingPayload)) {
      Serial.println("STATUS:DROPPED_BAD_PACKET");
      return;
    }

    int packetRssi = LoRa.packetRssi();
    float packetSnr = LoRa.packetSnr();

    // Report signal metrics back to phone
    Serial.print("RSSI:");
    Serial.println(packetRssi);
    Serial.print("SNR:");
    Serial.println(packetSnr, 1);

    // Forward packet payload to phone
    // If it is an ACK (e.g. ACK:1), audio chunk, or text message
    if (incomingPayload.startsWith("ACK:") || 
        incomingPayload.startsWith("CHK:") || 
        incomingPayload.startsWith("LOC:")) {
      Serial.println(incomingPayload);
    } else {
      // Standard text message
      Serial.print("MSG:");
      Serial.println(incomingPayload);
    }
  }
}

// Returns true only for valid printable UTF-8 text (no control characters).
// Every legitimate app packet (TX text, LOC:, CHK:, ACK:) passes this check.
bool isCleanText(const String &p) {
  size_t n = p.length();
  if (n == 0) return false;
  for (size_t i = 0; i < n; ) {
    uint8_t c = (uint8_t)p[i];
    if (c < 0x20 || c == 0x7F) return false;          // control chars = garbage
    if (c < 0x80) { i++; continue; }                  // plain ASCII
    int extra;
    if ((c & 0xE0) == 0xC0) extra = 1;
    else if ((c & 0xF0) == 0xE0) extra = 2;
    else if ((c & 0xF8) == 0xF0) extra = 3;
    else return false;                                // invalid UTF-8 lead byte
    if (i + extra >= n) return false;                 // truncated sequence
    for (int k = 1; k <= extra; k++) {
      if (((uint8_t)p[i + k] & 0xC0) != 0x80) return false;
    }
    i += extra + 1;
  }
  return true;
}

// Parse commands received from the Phone's USB Serial port
void processPhoneCommand(String cmd) {
  cmd.trim();
  
  // Connection check from the app (do NOT broadcast over radio)
  if (cmd == "CMD:PING") {
    Serial.println("STATUS:PONG");
    return;
  }

  // Bandwidth: CMD:BW:125000
  if (cmd.startsWith("CMD:BW:")) {
    long bw = cmd.substring(7).toInt();
    if (bw >= 7800 && bw <= 500000) {
      signalBandwidth = bw;
      LoRa.setSignalBandwidth(signalBandwidth);
      Serial.print("STATUS:BW_CHANGED:");
      Serial.println(signalBandwidth);
    }
    return;
  }

  // Frequency Change Command: CMD:FREQ:433E6 or CMD:FREQ:433000000
  if (cmd.startsWith("CMD:FREQ:")) {
    String freqStr = cmd.substring(9);
    long newFreq = 0;
    
    if (freqStr.endsWith("E6") || freqStr.endsWith("e6")) {
      float mhz = freqStr.substring(0, freqStr.length() - 2).toFloat();
      newFreq = (long)(mhz * 1000000L);
    } else {
      newFreq = freqStr.toInt();
    }
    
    if (newFreq >= 410000000L && newFreq <= 525000000L) {
      currentFrequency = newFreq;
      LoRa.sleep();
      LoRa.setFrequency(currentFrequency);
      LoRa.idle();
      Serial.print("STATUS:FREQ_CHANGED:");
      Serial.println(currentFrequency);
    } else {
      Serial.println("STATUS:ERR_INVALID_FREQUENCY_RANGE");
    }
    return;
  }
  
  // Radio Parameter: Spreading Factor CMD:SF:7..12
  if (cmd.startsWith("CMD:SF:")) {
    int sf = cmd.substring(7).toInt();
    if (sf >= 6 && sf <= 12) {
      spreadingFactor = sf;
      LoRa.setSpreadingFactor(spreadingFactor);
      Serial.print("STATUS:SF_CHANGED:");
      Serial.println(spreadingFactor);
    }
    return;
  }
  
  // Radio Parameter: TX Power CMD:POW:2..20
  if (cmd.startsWith("CMD:POW:")) {
    int pow = cmd.substring(8).toInt();
    if (pow >= 2 && pow <= 20) {
      txPower = pow;
      LoRa.setTxPower(txPower);
      Serial.print("STATUS:POW_CHANGED:");
      Serial.println(txPower);
    }
    return;
  }

  // Outbound Audio Chunk: CHK:1/50:[BASE64_DATA]
  if (cmd.startsWith("CHK:")) {
    LoRa.beginPacket();
    LoRa.print(cmd);
    LoRa.endPacket();
    Serial.println("STATUS:CHUNK_TRANSMITTED");
    return;
  }

  // Outbound Chunk Acknowledgment: ACK:1
  if (cmd.startsWith("ACK:")) {
    LoRa.beginPacket();
    LoRa.print(cmd);
    LoRa.endPacket();
    Serial.println("STATUS:ACK_TRANSMITTED");
    return;
  }

  // Outbound GPS Location: LOC:17.4933,78.3914
  if (cmd.startsWith("LOC:")) {
    LoRa.beginPacket();
    LoRa.print(cmd);
    LoRa.endPacket();
    Serial.println("STATUS:LOC_TRANSMITTED");
    return;
  }

  // Outbound Standard Text: TX:[Message]
  if (cmd.startsWith("TX:")) {
    String msg = cmd.substring(3);
    LoRa.beginPacket();
    LoRa.print(msg);
    LoRa.endPacket();
    Serial.println("STATUS:TX_TRANSMITTED");
    return;
  }

  // Never radiate unknown commands
  if (cmd.startsWith("CMD:")) {
    Serial.println("STATUS:ERR_UNKNOWN_CMD");
    return;
  }

  // Fallback raw broadcast
  LoRa.beginPacket();
  LoRa.print(cmd);
  LoRa.endPacket();
  Serial.println("STATUS:RAW_TRANSMITTED");
}
