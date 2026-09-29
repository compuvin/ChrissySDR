# ChrissySDR

ChrissySDR is an open-source Android radio client designed specifically for
licensed amateur-radio operators. Its primary purpose is to provide safe,
convenient receive and transmit operation from an Android device while the app
and radio server are connected to the same local-area network (LAN).

The first radio interface is built around
[SoapyRemote](https://github.com/pothosware/SoapyRemote), allowing an Android
device to operate a radio through a SoapySDR server without packaging native
SoapySDR binaries in the app. ChrissySDR is not intended to expose or operate a
radio over the public Internet.

The long-term goal is a practical, capability-driven amateur-radio interface
that works with many Soapy devices instead of assuming the behavior of one
radio. Device formats, sample rates, frequency limits, bandwidths, antennas,
gains, and transmit capabilities will be derived from the information reported
by Soapy.

Transmit operation is intended only for appropriately licensed operators using
frequencies, modes, power levels, and operating practices permitted by their
license and local regulations.

## Project status

ChrissySDR is under active development and is not ready for general use.

The current development build can:

- Connect to a SoapyRemote server.
- Discover and inspect attached Soapy devices.
- Display RX and TX capabilities reported by the radio.
- Open an RX stream using `CS16` or `CF32` samples.
- Play an experimental AM receive stream through Android audio.
- Send a confirmation-gated, 30-second experimental AM microphone transmission
  on radios that report compatible TX capabilities.
- Report basic stream and signal statistics.

Transmit is the central long-term purpose of ChrissySDR. The current AM transmit
path is an early controlled test facility, not a production-ready transmitter.
Additional capability negotiation, LAN connection handling, safety mechanisms,
and on-air validation remain under development.

## Direction

The first complete receiver is planned to include:

- AM, narrowband FM, USB, and LSB voice reception.
- A live spectrum with touch tuning.
- Precise frequency entry and mode-aware filters.
- Capability-driven sample-rate, bandwidth, gain, and antenna controls.
- Background reception through an Android foreground media service.
- A modern portrait-oriented interface built with Jetpack Compose.

Safe SoapyRemote voice transmit will follow the complete receiver. Later work
is expected to add memories, a waterfall, CW support, additional device
validation, and a separate direct flex1500d backend.

The detailed milestones and technical plan are in
[docs/product-roadmap.md](docs/product-roadmap.md).
Hands-on device and integration results are recorded in
[docs/test-log.md](docs/test-log.md).

## Open source and Google Play

ChrissySDR is intended to remain open source. Users will be able to inspect the
source, build the application themselves, and install their own builds.

A release is also planned for Google Play at a small purchase price. The Play
Store version will provide convenient installation and automatic updates, while
its purchase will help support continued development and maintenance. Paying
for the Play Store version will not unlock a separate set of application
features.

## Building

Requirements:

- JDK 21
- Android SDK with API 37 installed
- `ANDROID_HOME` set to the Android SDK directory

Build and test the debug APK with:

```sh
./gradlew test assembleDebug
```

The resulting APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

ChrissySDR currently expects a SoapyRemote server on the same trusted LAN,
normally on TCP port `55132`, with a Soapy-supported radio attached to that
server. Do not expose the SoapyRemote service directly to the public Internet.

## Contributing

Bug reports, protocol findings, hardware compatibility results, documentation,
and code contributions will be welcome as the project matures. In particular,
testing against radios with different formats and sample-rate capabilities will
help ensure the app remains genuinely device-independent.

Transmit functionality is for licensed amateur-radio operators. Operators are
responsible for complying with their license conditions and all applicable
laws and regulations. Keep transmit testing controlled and lawful; initial
transmit work should be tested into a dummy load or other controlled endpoint
before any over-the-air use.

## License

An open-source license will be selected and added before the first public
release. Until that license file is present, the repository contents should not
be assumed to grant redistribution rights.
