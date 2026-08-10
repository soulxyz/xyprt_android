# 错题小印 Revival / LaBLEr BY-288 port — alpha1

This is an experimental BY-288-focused fork of LaBLEr 1.1.0.

## Alpha1 scope

The goal of alpha1 is to preserve LaBLEr's polished editor/template/history/backup UX while replacing the printer path with Beeprt BY-288 / 错题小印 X1 support.

### Printer transport

- Android Bluetooth Classic RFCOMM / SPP (not BLE GATT)
- UUID: `00001101-0000-1000-8000-00805F9B34FB`
- Discovery shows bonded Classic devices immediately and then performs Bluetooth inquiry.
- Known printer-name prefixes include `Qring`, `BY-288`, `Beeprt`, `FlashToy`, and `F2`.

### BY-288 print geometry

- 384 dots across the head
- 8 dots/mm (203 dpi)
- 48 bytes per raster row
- Default editor tape width: 48 mm
- Default label length: 60 mm
- Default medium: continuous paper

### Print job

```
10 FF F1 02
1F B2 10
10 FF 10 00 01
00 00 00 00 00 00 00 00 00 00 00 00
1B 4A 0A
1D 76 30 00 30 00 yL yH [raster]
1B 4A 64                # continuous mode
10 FF F1 45
```

Die-cut mode uses the known `1D 0C` paper-position command instead of the continuous post-feed.

### Queries included

- status `10 FF 40`
- all-info `10 FF 70`
- battery `10 FF 50 F1`
- model `10 FF 20 F0`
- firmware `10 FF 20 F1`
- serial `10 FF 20 F2`
- BT version/name/MAC `10 FF 30 10/11/12`
- learn gap `10 FF 03`

### Deliberately not implemented

Reverse feed is NOT exposed. The BY-288 physical unit did not reverse with the previously tested `ESC K`, `ESC e`, or sibling-SDK `1F 11 11 n` candidates.

Firmware flashing/updating is also intentionally excluded from this app build.

## Local persistence change

Upstream LaBLEr uses Room/KSP. The portable offline environment used for this alpha has Kotlin 2.0.20 but no matching modern KSP plugin for this source revision. To keep template/history/backup features and avoid fragile compiler mixing, alpha1 replaces the two small Room DAOs with a local SharedPreferences-backed store exposing the same repository-facing API.

This is an implementation detail; templates and print history still persist locally and still feed the same UI flows.

## QR / barcode build workaround

The verified offline Maven cache did not include ZXing Core 3.5.3. Alpha1 therefore:

1. compiles against a tiny API-only ZXing stub jar;
2. extracts the real `com.google.zxing.*` classes already bundled in the user-supplied upstream LaBLEr 1.1.0 APK;
3. appends those Apache-licensed classes as an additional DEX before final APK signing.

The final APK contains 291 ZXing classes. See `tools/ExtractPackageDex.java` and `tools/build-by288-alpha1.sh`.

## License

The fork remains under LaBLEr's GPL-3.0 license. ZXing is Apache-2.0 licensed.
