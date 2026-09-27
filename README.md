# ChrissySDR

ChrissySDR is an Android SDR client. Its first radio backend is a Kotlin
implementation of the SoapyRemote network protocol, allowing Android to use a
remote SoapySDR server without packaging native SoapySDR binaries.

The current foundation contains an installable Android application, a server
address screen, version negotiation, and remote device enumeration. Transmit support will remain disabled
until discovery, capability negotiation, receive streaming, and explicit TX
safety controls have been implemented and tested.

## Build

Set `ANDROID_HOME` to the Android SDK and run:

```sh
./gradlew test assembleDebug
```
