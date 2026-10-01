# ChrissySDR 0.4.0

This milestone builds out the SoapyRemote and app architecture foundation.
ChrissySDR remains experimental and is not ready for general-use or
unattended transmission.

## What's new

- Capability-driven radio backend, foreground radio service, separate
  DSP/audio pipelines, and a Compose UI driven by radio state.
- Expanded RX/TX discovery: stream formats and full-scale values, sample-rate
  and bandwidth ranges, gains, antennas, AGC, duplex capability, driver
  settings, and device/channel sensors.
- Driver settings and sensor snapshots are available behind **Additional Radio
  Info** without lengthening the main radio summary.
- Native-aware `CS8`, `CS16`, and `CF32` receive decoding; automatic sample
  rate and hardware-bandwidth selection from reported capabilities.
- Clearer connection and protocol errors, bounded RX reconnection, stream
  status handling, cancellation, and device cleanup.

## Tested

- Automated unit tests and debug APK assembly passed.
- The operator reported that the `0.4.0-beta.2` information layout looks good.
- The FLEX-1500 via flex1500d and RTL-SDR Blog V4 were independently
  enumerated and probed through SoapyRemote. Their reported capabilities are
  recorded in `docs/soapyremote-radio-inventory-2026-09-30.md`.

## Important limitations

- The attached APK is a **debug/test build**, signed with an Android debug key;
  it is not a production-signed or Play Store build.
- AM receive and the confirmation-gated, 30-second AM transmit path are still
  experimental. Only appropriately licensed operators should transmit, on a
  trusted LAN and with a controlled test setup.
- Driver settings are displayed but cannot yet be changed in the app. Sensor
  values are inspection-time snapshots, not live meters.
- Voice DSP, spectrum display, and additional TX safety features remain on the
  roadmap.
