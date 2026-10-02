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

## 2026-10-02 — Spectrum tap and drag tuning

- **App version:** `0.5.2-dev.2` local debug build; not published.
- **Scope:** Tapping the live spectrum selects the frequency at that position.
  Dragging previews a pan-adjusted target and retunes once on release. Targets
  snap to the selected tuning step (1 Hz–10 kHz) and respect reported RX
  frequency ranges. The step control is in the radio-controls sheet.
- **Automated result:** Pure mapping tests cover tap placement, drag direction,
  step snapping, and invalid geometry; unit tests and debug build pass.
- **Device result:** Pending operator test of gesture recognition and tuning.

## 2026-10-02 — Live RX spectrum foundation

- **App version:** `0.5.2-dev.1` local debug build; not published.
- **Scope:** IQ from the RX stream feeds a preallocated 1,024-point radix-2
  FFT, throttled to about 10 frames/second. Radio rates above 48 kHz use a
  separate anti-aliasing resampler for a maximum 48 kHz spectrum span. The
  portrait main panel now shows live dBFS levels, center and passband overlays,
  and adjustable span, averaging, floor, and range. No waterfall or touch
  tuning has been added.
- **Automated result:** Unit tests cover positive/negative IQ tone placement,
  level, frame readiness, and frame-rate limiting; debug build passes.
- **Device result:** Pending. The operator is not available to test now.

## 2026-10-01 — 0.5.1 release candidate

- **App version:** `0.5.1` debug APK.
- **Scope:** Passband edits now reopen RX after a 600 ms typing pause, just as
  frequency edits do. Invalid or unsupported passbands leave the current stream
  running and display an error; changing modes continues to reopen RX at once.
- **Device result:** Operator confirmed dev.4 AM audio sounded better, received
  a station on 40 meters in LSB with the `0.5.1` APK, and successfully changed
  the passband during reception. USB reception and live frequency/mode changes
  remain unverified on radio. Signal quality and exact frequency were not
  recorded.
- **Automated result:** 68 unit tests and debug APK assembly passed; APK
  identifies as `com.kb1jdx.chrissysdr` version code 15 / name `0.5.1`.

## 2026-10-01 — SSB passband and live RX controls

- **App version:** `0.5.0-dev.5` debug build.
- **Scope:** USB/LSB now display a 3 kHz one-sideband passband by default;
  hardware bandwidth and rate selection still account for the 6 kHz centered
  IQ span. Mode changes while receiving automatically reopen RX with the new
  detector. Frequency edits reopen RX after a 600 ms typing pause. No manual
  Stop RX / Start RX cycle is required.
- **AM feedback:** The operator reports that dev.4 AM audio sounds better.
  Whether the restored gain also raises empty-channel noise needs listening
  evaluation.
- **Automated result:** Unit tests and debug APK assembly passed.
- **Device result:** Live mode/frequency switching and SSB audio pending test.

## 2026-10-01 — USB/LSB receive and weak-signal AM gain correction

- **App version:** `0.5.0-dev.4` debug build.
- **Scope:** Added USB/LSB product detection with one-sided IQ filtering and a
  suppressed-carrier/BFO reference at the tuned frequency. The mode selector
  resets RF passband to its default while leaving it manually editable. Saved
  profiles now retain mode; existing profiles migrate to AM. TX remains AM-only.
- **AM feedback:** The operator reported very quiet reception with dev.3. The
  new AM AGC's 20× maximum gain and 0.005 floor could attenuate weak radio IQ;
  dev.4 raises the gain ceiling and restores a lower AGC floor. This is a
  probable cause, not yet confirmed by a listening test.
- **Automated result:** USB/LSB sideband selection, opposite-sideband rejection,
  BFO offset, carrier leakage, and weak-signal AM gain tests passed; debug APK
  assembly passed.
- **Device result:** AM gain correction and USB/LSB audio pending operator test.

## 2026-10-01 — AM detector and default passband

- **App version:** `0.5.0-dev.3` debug build.
- **Scope:** AM defaults to a 6 kHz total RF passband (approximately ±3 kHz
  audio), remains manually editable, and will reset to a mode-specific default
  when a supported mode changes. Other modes remain unavailable until their
  DSP is implemented. AM envelope detection now uses sample-rate-derived DC
  blocking and bounded audio AGC with attack/release timing.
- **Automated result:** Unit tests and debug APK assembly passed. Generated
  signals verify audio recovery with carrier offset, DC rejection, passband
  rejection, and AGC leveling without clipping.
- **Device result:** Operator reported markedly quiet/attenuated audio in dev.3;
  see dev.4 correction above. No device-side root cause confirmed yet.

## 2026-10-01 — Polyphase RX resampling

- **App version:** `0.5.0-dev.2` debug build.
- **Scope:** Replace AM RX sample-dropping with a streaming complex-IQ
  resampler. Staged half-band filtering handles high radio rates; a polyphase
  fractional stage handles non-integer rates and low-rate interpolation to
  48 kHz Android audio. AM demodulation now runs at the audio rate.
- **Automated result:** Unit tests and debug APK assembly passed. Generated
  tones verify passband preservation, alias/image rejection, packet-boundary
  continuity, and AM recovery at a non-integer radio rate.
- **Device result:** The operator tested AM receive with both the FLEX-1500
  and RTL-SDR V4 and reported that both still sounded good. The exact RTL-SDR
  sample rate and any quantitative audio measurements were not recorded.

## 2026-10-01 — Saved radio profiles

- **App version:** `0.5.0-dev.1` debug build.
- **Scope:** Room-backed named radio profiles store server, port, device
  identity, frequency, AM passband, and optional sample-rate override. Tapping
  a saved radio discovers and inspects that specific device, then starts RX;
  it never starts TX. Profiles are not auto-connected after app restart.
- **Automated result:** Room code generation, 50 unit tests, and debug APK
  assembly passed. Profile matching tests cover exact identity, changed labels,
  and missing or ambiguous radios.
- **Device result:** Pending save, restart, and one-tap load/RX checks.

## 2026-09-30 — 0.4.0 milestone release

- **App version:** `0.4.0` debug/test build.
- **Scope:** SoapyRemote capability and stream-format negotiation, connection
  lifecycle hardening, driver settings/sensor discovery, and the Additional
  Radio Info view.
- **Device result:** The operator reported that the `0.4.0-beta.2` Additional
  Radio Info layout looks good. The flex1500d and RTL-SDR Blog V4 devices
  were also independently enumerated and probed through SoapyRemote; see
  [radio inventory](soapyremote-radio-inventory-2026-09-30.md). No claim is
  made that every 0.4 feature was retested on hardware after the final
  version-label change.
- **Automated result:** Final `0.4.0` unit tests and debug APK assembly passed.
  APK identity verified as `com.kb1jdx.chrissysdr`, version code `9`,
  version name `0.4.0`.

## 2026-09-30 — Additional radio information view

- **App version:** `0.4.0-beta.2` debug build.
- **Scope:** Moved the newly discovered device and channel settings/sensors out of the main radio summary into an Additional Radio Info dialog. The button appears at the bottom of the summary only after selecting a connected radio.
- **Automated result:** Unit tests and debug APK assembly passed.
- **Device result:** Pending visual check with flex1500d and RTL-SDR.

## 2026-09-30 — Driver settings and sensor discovery

- **App version:** `0.4.0-beta.1` debug build.
- **Scope:** Read-only device and per-channel settings (including type,
  range/options, default, and current value) and sensors (metadata, units, and
  current value). If a radio exposes SWR or forward-power sensors, they appear
  in the device information. Values are snapshots at inspection, not live
  transmit telemetry.
- **Automated result:** Unit tests and debug APK assembly passed. Mock
  SoapyRemote tests cover device settings, RX-channel settings, device SWR,
  TX-channel forward power, and unsupported optional queries.
- **Device result:** Pending. Reinspect available radios and confirm their
  reported settings/sensors display without breaking RX. A radio with no
  sensors should show none or not reported, not an inspection failure.

## 2026-09-30 — Upstream Soapy capability and format audit

- **App version:** `0.3.0-beta.5` debug build.
- **References:** [SoapySDR stream API](https://github.com/pothosware/SoapySDR/blob/master/include/SoapySDR/Device.hpp),
  [SoapyRemote call IDs](https://github.com/pothosware/SoapyRemote/blob/master/common/SoapyRemoteDefs.hpp),
  [server capability replies](https://github.com/pothosware/SoapyRemote/blob/master/server/ClientHandler.cpp),
  and [client stream setup](https://github.com/pothosware/SoapyRemote/blob/master/client/Streaming.cpp).
- **Audit result:** Per-channel requests use the upstream direction/channel
  argument order. Native stream format replies contain a format string followed
  by its full-scale value; stream argument replies use the upstream argument-info
  layout. The selected format is advertised and decoded with native full scale
  only when it matches the native format. ChrissySDR talks directly to the
  server, so it does not use the C++ client's local `remote:scale` conversion.
- **Changes:** Reject RPC versions older than the range-step wire format, cap
  reply size, and reject trailing reply fields instead of silently ignoring
  protocol mismatches. Added format and frame tests.
- **Automated result:** Unit tests and debug APK assembly passed.
- **Device result:** The operator reports that the app tested well on the last
  `0.3.0-beta.5` build. The exact radio and individual test scenarios were not
  specified for this regression report.

## 2026-09-30 — Connection and stream lifecycle hardening

- **App version:** `0.3.0-beta.2` debug build for the live reconnect test;
  `0.3.0-beta.3` adds a Stop RX control during opening and reconnecting;
  `0.3.0-beta.4` limits retries to streams that were previously established.
- **Scope:** Structured failure categories and connection states; bounded RX-only
  retries after network or stream errors (1, 2, and 4 seconds); cancellation of
  pending RX socket opens; RX/TX stream-status errors; and cleanup after partial
  opens, startup failures, and stream failures. TX never retries automatically.
- **Automated result:** Unit tests and debug APK assembly passed. Tests cover
  failure classification, retry limits, status packets, and pending-open socket
  cancellation.
- **Device result:** The operator monitored WWV at 5 MHz, stopped SoapyRemote,
  and restarted it immediately; ChrissySDR reconnected and WWV audio resumed. On a
  second attempt, SoapyRemote stayed down longer; ChrissySDR exhausted its
  bounded retries and displayed a timeout message. Both outcomes are expected.
- **Further device result:** The operator reports that Stop during a pending
  retry, manual RX restart after retry exhaustion, and repeated start/stop
  cycles passed in `0.3.0-beta.3`.
- **New issue:** Pressing Start RX while SoapyRemote was down also triggered
  three automatic retries, even though RX had never been established. In
  `0.3.0-beta.4`, the first failed open reports its error once; bounded retries
  remain available after an established RX stream is lost. Device retest of
  that correction is pending. TX was not tested.

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
