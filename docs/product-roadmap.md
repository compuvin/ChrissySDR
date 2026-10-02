# ChrissySDR Product and Implementation Plan

## Goals

ChrissySDR will become a portrait-oriented Android amateur-radio client that:

- [ ] Connects to radios through SoapyRemote without requiring native SoapySDR Android binaries.
- [ ] Derives radio configuration from advertised Soapy capabilities rather than hardware-specific assumptions.
- [ ] Delivers a polished voice receiver supporting AM, narrowband FM, USB, and LSB.
- [ ] Provides a live spectrum with touch tuning and precise digit-based frequency entry.
- [ ] Continues receiving through an Android foreground media service.
- [ ] Adds carefully guarded Soapy transmit after the receiver is stable.
- [ ] Keeps a future proprietary flex1500d backend possible without coupling the core app to FLEX-specific behavior.

“Any Soapy device” means any compliant SoapyRemote device that offers a stream format ChrissySDR can decode. Unsupported capabilities must be reported clearly and never silently approximated.

## Roadmap and Feature Milestones

### 0.4 — Soapy and Architecture Foundation

- [x] Split the current activity into:
  - [x] A capability-driven `RadioBackend` interface.
  - [x] A SoapyRemote control and streaming implementation.
  - [x] Independent DSP/audio pipelines.
  - [x] A foreground radio service that owns connections and streams.
  - [x] A Compose UI driven by immutable state and events.
- [x] Expand discovery for each RX/TX channel to query:
  - [x] Native stream format and full-scale value.
  - [x] Available stream formats and stream arguments.
  - [x] Discrete sample rates and ranges.
  - [x] Frequency and bandwidth ranges.
  - [x] Antennas, gain elements/ranges, automatic gain support, and duplex capability.
  - [x] Driver-defined settings, including their types, ranges/options, and current values.
  - [x] Device and per-channel sensors, including available SWR and forward-power telemetry.
- [x] Follow the upstream Soapy APIs for [native stream format/full-scale negotiation](https://github.com/pothosware/SoapySDR/blob/master/include/SoapySDR/Device.hpp) and [SoapyRemote capability calls](https://github.com/pothosware/SoapyRemote/blob/master/common/SoapyRemoteDefs.hpp).
- [x] Support `CS8`, `CS16`, and `CF32` decoding with format-specific byte order and full-scale conversion.
- [x] Prefer a supported native format when efficient; otherwise prefer `CS16`, then `CF32`, then `CS8`.
- [x] Replace free-form sample-rate entry:
  - [x] Automatically select the lowest advertised rate sufficient for the active mode and filter.
  - [x] Query the actual applied rate after configuration.
  - [x] Offer only capability-valid rates in an advanced override.
  - [x] For ranged capabilities, generate choices within the reported minimum, maximum, and step.
- [x] Select hardware bandwidth automatically as the smallest supported value that contains the requested DSP passband.
- [x] Add structured errors, connection state, clean cancellation, reconnection, stream-status handling, and guaranteed device cleanup.

### 0.5 — Voice Receiver and Spectrum

- [x] Add mode-specific DSP:
  - [x] AM envelope detection with DC removal and audio AGC.
  - [x] NFM discriminator with configurable filtering and deemphasis.
  - [x] USB and LSB product detection with carrier/BFO handling.
- [x] Add a proper anti-aliasing polyphase resampler between arbitrary radio rates and Android audio rates; remove sample-dropping decimation.
- [x] Implement a preallocated radix-2 FFT pipeline for the spectrum:
  - [x] Adjustable averaging and dB range.
  - [x] Center-frequency and passband overlays.
  - [x] Tap to tune and drag to tune.
  - [x] No waterfall in this milestone.
- [x] Build the portrait Compose receiver screen:
  - [x] Spectrum occupies the main area.
  - [x] Persistent bottom bar shows current frequency and mode.
  - [x] Central TX button is visible but disabled and marked unavailable.
  - [x] Tapping frequency digits permits step-aware tuning.
  - [x] Pulling up the bottom sheet exposes frequency, mode, filter width, tuning step, gain/AGC, antenna, spectrum span, and advanced sample-rate selection.
- [x] Clamp all controls to advertised radio capabilities and explain unavailable controls.

### 0.6 — Complete Receiver Release

- [ ] Move active RX into a foreground media service with:
  - [x] Media-style notification showing frequency and mode.
  - [x] Stop control.
  - [ ] Audio focus handling.
  - [ ] Headphone/Bluetooth route-change handling.
  - [ ] Reliable cleanup on app removal, service stop, connection loss, or radio error.
- [x] Persist the last server, port, device, frequency, mode, filter, gains, and advanced overrides locally.
- [x] Add reconnect and restore-session behavior, without automatically reopening or keying hardware after an app restart.
- [x] Provide an in-app diagnostics view with copy/share support for:
  - [x] Server and device information.
  - [x] Negotiated format/rate/bandwidth.
  - [x] Stream rate, underruns/overruns, sequence gaps, and recent errors.
- [x] Produce reproducible personal/test APKs with visible semantic development versions.
- [ ] Acceptance gate: sustained AM/NFM/USB/LSB reception, stable background audio, correct spectrum tuning, and clean start/stop behavior on the FLEX-1500 plus a second device or simulated server using a substantially different sample rate.

### 0.7 — Safe Soapy Voice Transmit

- [ ] Implement SoapyRemote TX streaming for microphone-originated AM, NFM, USB, and LSB.
- [ ] Configure TX format, sample rate, frequency, bandwidth, antenna, and gain exclusively from advertised TX capabilities.
- [x] Validate the requested frequency against the device’s TX ranges before arming.
- [ ] Use tap-to-latch PTT with:
  - [ ] A separate explicit TX-armed state.
  - [ ] Strong visual and audible transitions into and out of transmit.
  - [ ] A default three-minute timeout configurable to 30 seconds, 1, 3, 5, or 10 minutes.
  - [ ] Immediate unkey on timeout, disconnect, stream failure, service shutdown, audio-recording failure, or application fault.
  - [ ] No automatic return to transmit after reconnect or restart.
- [ ] Request microphone permission only when TX is first configured.
- [ ] Disable TX when the radio is receive-only, the device is not the station owner, capabilities are incomplete, or validation fails.
- [x] Test TX initially into a dummy load or controlled test endpoint, not over the air.

### Later Enhancements

After safe Soapy TX:

- [ ] Saved frequencies, named stations/channels, and band presets.
- [ ] Quick QSO log entry for recording a contact on the go; not a replacement for a full logging program.
- [ ] Waterfall display.
- [ ] CW receive and additional digital-mode plumbing.
- [ ] Direct proprietary flex1500d backend behind `RadioBackend`.
- [ ] Additional screen sizes/orientations, external PTT controls, and broader hardware validation.
- [ ] Public-release work such as signing, accessibility review, privacy documentation, and store compliance.
- [ ] Repeater options - +/- offset, PL tone

## Core Interfaces and Data Flow

- [ ] `RadioBackend` exposes discovery, capability inspection, open/close, configuration, RX streams, TX streams, and status events without exposing Soapy RPC details.
- [ ] `RadioCapabilities` contains per-channel formats, rates, ranges, gains, antennas, duplex state, and device metadata.
- [ ] `ReceiverConfig` contains frequency, mode, passband, gain/AGC, antenna, and optional validated advanced overrides.
- [ ] `TransmitterConfig` contains frequency, mode, passband, gain, antenna, and timeout.
- [ ] The foreground service owns the backend and publishes `StateFlow<RadioState>` to the Compose UI.
- [ ] RX data flows through format conversion, spectrum tap, demodulation, filtering, resampling, audio AGC, and `AudioTrack`.
- [ ] TX data flows from `AudioRecord` through audio conditioning, modulation, resampling, format conversion, and the SoapyRemote TX stream.
- [ ] DSP and protocol layers remain Android-independent where possible so they can be unit tested on the JVM.

## Testing and Acceptance

- [ ] Protocol tests cover fragmented reads/writes, all supported sample formats, endian conversion, full-scale normalization, RPC exceptions, stream acknowledgements, status events, and cleanup.
- [ ] Capability-policy tests cover fixed rates, discrete rates, continuous ranges, missing values, driver rounding, unsupported formats, and invalid TX ranges.
- [ ] DSP tests use generated signals to verify frequency response, demodulation accuracy, rejection, resampling, and clipping for every mode.
- [ ] Service tests cover rotation, backgrounding, screen lock, network loss, audio-route changes, repeated start/stop, and process/service termination.
- [ ] Compose tests cover bottom-sheet controls, disabled TX state, tuning gestures, validation, and error presentation.
- [ ] Each milestone requires a clean `test assembleDebug` build plus live FLEX-1500 verification.
- [x] Generic compatibility is not claimed solely from FLEX testing; the receiver release also requires a second-rate integration fixture or another Soapy radio.

## Assumptions and Defaults

- [x] Initial distribution is a personal/test APK, not a public store release.
- [x] The first release targets portrait phones.
- [x] SoapyRemote is the only radio transport through the complete receiver and initial TX milestones.
- [x] FLEX-1500 is the first validation device but contributes no hardcoded rate, bandwidth, format, frequency, or gain assumptions.
- [x] Sample rate is normally automatic; operator override is advanced and capability-constrained.
- [x] “FM” initially means narrowband voice FM, not broadcast WFM or stereo.
- [x] Spectrum is included before waterfall.
- [x] Memories, CW, waterfall, and the proprietary flex1500d backend follow safe Soapy transmit.
