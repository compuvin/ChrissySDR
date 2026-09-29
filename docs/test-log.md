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
