# ChrissySDR Test Log

This file records hands-on integration tests and notable observations. The
product roadmap remains a static statement of agreed work; partial progress and
individual experiments belong here instead.

## Entry format

- Date and app version
- Hardware and transport
- Purpose and configuration
- Result
- Observations or follow-up work

## 2026-09-30 — RTL-SDR V4 bandwidth selection

- **Radio:** RTL-SDR V4 (R828D) through SoapyRemote on the Pi.
- **Observation:** Discovery succeeded, but RX was unavailable with a 12 kHz
  passband. The device reported a 0–8 MHz bandwidth range and a minimum
  discrete sample rate of 250 kHz.
- **Cause:** ChrissySDR discarded bandwidth ranges whose minimum was zero.
- **Fix:** Accept zero as a reported range minimum while selecting a positive
  bandwidth that contains the requested passband. A unit test now covers the
  reported range; Android unit tests and debug APK assembly pass.
- **Device retest:** Successful. The operator received WWV at 10 MHz with the
  RTL-SDR V4 after installing the updated APK; the previous passband error did
  not recur. This provides a live RX test on a second Soapy radio. The reported
  frequency range did not include 10 MHz, so the successful reception also
  shows that this driver's advertised range is not exhaustive for this setup.
- **Not tested:** Other receive modes or transmit; the RTL-SDR V4 is RX-only.

## 2026-09-29 — Stream-format RX regression

- **App version:** `0.3.0-beta.1` debug build with new I/Q format handling.
- **Radio:** FLEX-1500 via flex1500d over SoapyRemote on the LAN.
- **Scope:** RX after adding `CS8`/`CS16`/`CF32` decoding, full-scale conversion,
  and capability-driven format selection.
- **Result:** Successful according to the operator. The app selected `CS16` for
  RX and receive audio sounded roughly as before.
- **Not tested:** TX and RX on a device selecting `CS8` or `CF32`.

## 2026-09-29 — Native stream discovery regression

- **App version:** `0.3.0-beta.1` debug build with discovery changes.
- **Scope:** Per-channel native stream format, full-scale value, and stream-argument
  discovery through SoapyRemote, alongside recent bandwidth selection changes.
- **Result:** The operator installed the updated app and reported that it tested
  fine. No specific capability values or stream-argument contents were recorded.
- **Automated result:** Unit tests and debug APK assembly passed.

## 2026-09-28 — Service and pipeline receiver regression

- **App version:** `0.3.0-dev.14`
- **Radio:** FLEX-1500 via flex1500d
- **Transport:** SoapyRemote over LAN
- **Scope:** Receiver regression after moving connection/stream ownership into
  the foreground radio service and separating Soapy transport, DSP, and Android
  audio components.
- **Result:** Successful. The operator confirmed that the receiver still works
  well.
- **Not explicitly tested:** Background longevity, notification stop action,
  transmit regression, TX-to-RX restoration, and process/service termination.

## 2026-09-28 — Capability discovery RX regression

- **App version:** `0.3.0-dev.11`
- **Radio:** FLEX-1500 via flex1500d
- **Transport:** SoapyRemote over LAN
- **Scope:** Regression test after adding optional-capability handling, gain
  ranges, AGC and duplex queries, and the per-radio unknown-TX-range override.
- **Result:** Receive tested successfully; audio was reported as clear and crisp.
- **Expected UI behavior:** No unknown-range transmit override was shown because
  this radio reports explicit TX frequency ranges. No other conspicuous UI
  changes were observed.
- **Not tested:** A radio with missing TX frequency ranges, persistence of that
  radio's override acknowledgment, transmit, and TX-to-RX restoration.

## 2026-09-28 — Experimental AM transmit

- **App version:** `0.3.0-dev.6`
- **Radio:** FLEX-1500 via flex1500d
- **Transport:** SoapyRemote over LAN
- **Mode:** Full-carrier AM using Android microphone audio
- **Interaction:** Confirmation-gated tap-to-latch TX with a 30-second safety limit
- **Result:** Successful. The operator confirmed that the transmit test worked.
- **Not recorded:** RF measurement details, output power, transmitted audio
  quality, and whether the automatic timeout was allowed to expire.
- **Follow-up:** Request microphone permission when a TX-capable radio is
  selected rather than immediately before key-up. This change is included in
  the `0.3.0-dev.7` build but has not yet been tested on a device.

## 2026-09-28 — Compose receiver shell

- **App version:** `0.3.0-dev.7`
- **Scope:** Compose migration, immutable radio UI state, spectrum placeholder,
  bottom operating bar, central TX control, and pull-up settings sheet.
- **Automated result:** Successful. JVM tests and debug APK assembly completed.
- **APK identity:** Verified as `com.kb1jdx.chrissysdr` version `0.3.0-dev.7`.
- **Device result:** The operator installed the build and confirmed that the new
  interface looks good. Discovery, RX audio, permission timing, and TX regression
  behavior were not explicitly reconfirmed during this visual review. ADB could
  not be run inside the development sandbox.
- **Follow-up:** Install on an Android device and verify discovery, device
  selection, RX audio, the earlier microphone permission request, bottom-sheet
  behavior, and AM TX regression behavior.

## 2026-09-28 — Capability-driven sample-rate selection

- **App version:** `0.3.0-dev.8`
- **Scope:** Automatic RX rate selection, capability-constrained override
  choices, ranged-rate alignment, and readback of applied RX/TX rates.
- **Automated result:** Successful. Unit tests cover fixed, discrete, ranged,
  and insufficient-rate capability sets. JVM tests, debug APK assembly, and
  Android lint completed successfully.
- **Device result:** Not yet tested against SoapyRemote hardware.
- **Follow-up:** Confirm that the FLEX-1500 shows automatic 48 kHz, starts RX,
  and reports 48 kHz as the applied rate.

## Backfilled integration results

These results were confirmed during development before this log was created.
Their original test dates and app versions were not recorded.

### SoapyRemote discovery

- **Radio:** FLEX-1500 via flex1500d
- **Transport:** SoapyRemote over LAN
- **Result:** Successful. ChrissySDR discovered the attached radio and reported
  one RX channel and one TX channel.

### Device inspection

- **Radio:** FLEX-1500 via flex1500d
- **Result:** Successful. The device opened and closed cleanly, and the app
  displayed its driver, hardware information, formats, gains, frequency ranges,
  sample rates, bandwidth ranges, and TX frequency ranges.
- **Reported stream capabilities:** `CF32` and `CS16`, with a fixed 48 kHz
  sample rate for this device.

### RX stream transport

- **Radio:** FLEX-1500 via flex1500d
- **Transport:** SoapyRemote TCP stream
- **Result:** The RX stream activated and transferred samples.
- **Observation:** An initial SoapyRemote `recvACK` failure was traced to a TCP
  ACK header being split across writes. Sending the complete 24-byte ACK in one
  write resolved that failure.
- **Audio status:** Successful audible AM reception was not explicitly confirmed
  before this log was created.

## Logging guidance

Add a new dated entry for each meaningful device test. Record failures as well
as successes, including the app version, radio and driver, SoapyRemote version,
selected format/rate, relevant server messages, and whether the behavior can be
reproduced.
