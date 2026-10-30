# LoRaLink – Android Studio project (ESP32 + Ra-02 LoRa USB OTG messenger)

## Build and install on your phone
1. Install Android Studio (current stable). Use *File > Open* and pick this folder (LoRaLink-Android).
2. Let Gradle sync (needs internet the first time; it downloads Gradle 8.11.1 and the libraries).
   If Android Studio offers to upgrade the Android Gradle Plugin, you can click "Remind me later".
3. On the phone: Settings > About phone > tap Build number 7 times, then enable Developer options > USB debugging.
4. Connect the phone to the PC with a USB cable, accept the "Allow USB debugging" prompt, then press Run.

## Use
1. Flash the ESP32 or ESP32-C3 with firmware/LoRaLink_ESP32S (classic ESP32) or firmware/LoRaLink_ESP32C3 (C3 needs Tools > USB CDC On Boot > Enabled).
2. Wire the Ra-02 to the ESP32: NSS-GPIO5, RST-GPIO14, DIO0-GPIO2, SCK-GPIO18, MISO-GPIO19, MOSI-GPIO23, GND-GND.
   VCC goes to 3V3 only (never 5V). Attach the antenna before transmitting.
3. Plug the ESP32 into the phone with a USB OTG cable, open LoRaLink, allow USB access, and you are connected at 115200 baud.
4. Both radios must use the same frequency (default 433.00 MHz).

## Changes from the original files
- MainActivity.kt: the USB permission PendingIntent now has setPackage(packageName). Android 14+ (targetSdk 34+) rejects
  a mutable PendingIntent with an implicit Intent and the app would crash on connect.
- Manifest: custom theme (no extra Material library needed) and the missing backup XML references removed.
- Added the missing res/xml/device_filter.xml, launcher icons, theme, settings.gradle.kts and gradle files.
- Firmware: CMD:PING and CMD:BW are now handled, and unknown CMD: lines are no longer broadcast over the radio.
