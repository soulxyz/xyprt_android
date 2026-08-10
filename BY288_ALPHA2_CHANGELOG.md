# 错题小印 BY-288 α2

This fork keeps LaBLEr's editor/template/history/backup foundation but changes the normal product workflow from a 12-mm BLE label printer to the BY-288 continuous-roll 384-dot printer.

## α2 user-facing changes
- Chinese-only UI; app name changed to `错题小印` (debug label `错题小印 α2`).
- Printer status moved below the app title instead of competing for top-bar width.
- Added Quick Print entry points: text, image, PDF.
- Added Android SEND / SEND_MULTIPLE / VIEW integration for image, PDF and plain text so other apps can share/open content with 错题小印.
- Normal free-form layout is portrait: fixed 384-dot printable width, vertical feed direction.
- Removed user-configurable paper width from the normal creation flow. 384 dots is the hardware printable width (~47.3 mm at 203 dpi); physical roll width may be larger.
- Added automatic-length mode: editing uses a 100-mm work canvas and continuous-paper printing trims trailing blank rows automatically.
- Fixed-length layouts remain available for users who need them.
- Normal printing is forced to continuous paper; die-cut/gap calibration is hidden from the regular UI.
- Geometry test / gap-learning experiments removed from the normal settings flow.
- Editor preview increased to 320 dp high for portrait usability.

## BY-288 transport/protocol retained
- Bluetooth Classic RFCOMM/SPP UUID `00001101-0000-1000-8000-00805F9B34FB`.
- 384 dots / 48 bytes per GS-v-0 raster row.
- BY-288 private start, density, wake, feed, stop and status/info queries retained.
- Reverse feed remains intentionally absent because all tested candidates failed on the real BY-288.

## Quick Print
- Text: multiline text -> 384-dot portrait monochrome document, automatic length.
- Images: one or multiple images; width-fit; Floyd-Steinberg, threshold or Atkinson modes.
- PDF: Android PdfRenderer, up to 20 pages per import, pages stacked in feed direction.
- External intents: image/*, application/pdf, text/plain; multiple images through ACTION_SEND_MULTIPLE.
- Word/DOCX is not implemented in α2.

## Validation
- Offline Gradle/AGP/Kotlin/Compose build succeeds.
- 15 JVM unit tests pass, including two new BY-288 raster packing/auto-trim tests.
- Final APK contains the upstream GPL-compatible ZXing classes required by LaBLEr QR/barcode features.
- zipalign, APK Signature Scheme v2/v3 and ZIP integrity verified.

## Physical test still required
α1 confirmed real SPP connection to `Qring_5CEA`. α2 changes raster orientation to normal portrait paper coordinates, so first physical print should verify left/right orientation and feed direction before we call the raster transform final.
