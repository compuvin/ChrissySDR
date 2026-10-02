# ChrissySDR 0.5.1

This is a development/test release for licensed amateur-radio operators using
SoapyRemote on a trusted LAN. The attached APK is debug-signed, not a
production-signed or Google Play build.

## What's new since 0.4.0

- Save named radio profiles in a local Room database. Loading a profile
  reconnects to the selected device and starts receive.
- Streaming anti-aliasing polyphase IQ resampling for different radio sample
  rates, replacing sample-dropping decimation.
- Improved AM envelope detection, DC removal, and audio AGC. AM defaults to a
  6 kHz total RF passband; weak-signal gain was adjusted after operator feedback.
- USB and LSB receive with sideband-selective product detection and a 3 kHz
  default sideband passband. Tuning is referenced to the suppressed carrier.
- Frequency and passband edits reopen RX automatically after a 600 ms typing
  pause. Mode changes reopen RX immediately; no manual stop/start is required.
- The roadmap and test log reflect completed work and outstanding device tests.

## Verification and limitations

- 68 unit tests and debug APK assembly pass. The APK identifies as
  `com.kb1jdx.chrissysdr`, version code 15, version name `0.5.1`.
- The operator reported that the revised AM gain sounds better. USB/LSB audio,
  profile loading, and live tuning/filter changes still need hardware testing.
- The main spectrum remains a visual placeholder. NFM receive, live spectrum,
  digit-step tuning, and gain/antenna controls are not yet implemented.
- Transmit remains a confirmation-gated, 30-second experimental AM test path.
  Only licensed operators should transmit, within their operating privileges,
  using a controlled setup. USB/LSB transmit is not implemented.
