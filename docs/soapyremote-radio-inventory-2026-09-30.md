# SoapyRemote radio inventory — 2026-09-30

Read-only enumeration and probes of `192.168.1.116:55132` using the local
`SoapySDRUtil` client. This is a snapshot of what the drivers reported, not a
promise that every setting works or a record of live stream performance. The
listed setting defaults are driver metadata, **not measured current values**.
No settings were changed and no streams were started.

## FLEX-1500 via flex1500d

Discovery arguments: `driver=remote,remote=tcp://192.168.1.116:55132,remote:driver=flex1500`
(the remote discovery result also included `host=127.0.0.1,port=15000`).

- Identification: `driver=flex1500`; hardware `FLEX-1500 via flex1500d`;
  adapter version `0.2.2`, revision `8bdf6848`; transport `flex1500d API v1`;
  daemon `127.0.0.1:15000`; SoapyRemote version `0.6.0-gc50f3d37`.
- Radio metadata: `station_owner=true`, `daemon_transmit_enabled=true`,
  `receive_only=false`.
- Channels: 1 RX, 1 TX; no timestamps; not full-duplex; no RX AGC.
- Device-wide setting: **RX squelch**, key `squelch_db`, float, units `dBFS`,
  range `-120` to `0` in steps of `1`, default `-120`. Driver description:
  “Host-side receive squelch threshold; -120 is open.” This is the setting
  visible in ChrissySDR's Additional Radio Info view; the app does not yet
  provide a control for it.
- RX 0: `CF32`, `CS16`; native `CF32`, full-scale `32768`; gain range
  `-10` to `30` dB, step `10`; reported RX gain element range `0` to `0` dB;
  frequency `100 kHz` to `54 MHz`; sample rate `48 kS/s`; filter bandwidth
  `100 Hz` to `20 kHz`.
- TX 0: `CF32`, `CS16`; native `CS16`, full-scale `32768`; gain range
  `1` to `100` dB, step `1`; reported TX gain element range `0` to `0` dB;
  sample rate `48 kS/s`; filter bandwidth `48 kHz`. The driver reports TX
  frequency windows (MHz): `1.824–1.976`, `3.524–3.976`, `7.024–7.276`,
  `14.024–14.326`, `18.092–18.144`, `21.024–21.426`, `24.914–24.966`,
  `28.024–29.676`, `50.024–53.976`.
- Neither the local probe output nor the operator's app inspection showed
  device or per-channel sensors.

## RTL-SDR Blog V4

Discovery arguments: `driver=remote,remote=tcp://192.168.1.116:55132,remote:driver=rtlsdr,serial=00000001`.

- Discovery label: `Generic RTL2832U OEM :: 00000001`; manufacturer
  `RTLSDRBlog`; product `Blog V4`; tuner `Rafael Micro R828D`.
- Identification: `driver=RTLSDR`; hardware `R828D`; index `0`;
  SoapyRemote version `0.6.0-gc50f3d37`.
- Channels: 1 RX, 0 TX; timestamps supported with `sw_ticks`; RX AGC
  supported; not full-duplex.
- Device-wide settings: `direct_samp` (string, options `0`, `1`, `2`, default
  `0`); `offset_tune` (boolean, default `false`); `iq_swap` (boolean, default
  `false`); `digital_agc` (boolean, default `false`); `biastee` (boolean,
  default `false`). The driver's displayed names are Direct Sampling, Offset
  Tune, I/Q Swap, Digital AGC, and Bias Tee.
- RX 0: `CS8`, `CS16`, `CF32`; native `CS8`, full-scale `128`; antenna `RX`;
  tuner/full gain range `0` to `49.6` dB; full frequency range `23.999` to
  `1764 MHz`, RF range `24` to `1764 MHz`, correction range `-1` to `1 kHz`;
  sample-rate ranges `225001–300000 S/s` and `900001–3200000 S/s`;
  filter-bandwidth range `0–8 MHz`.
- Neither the local probe output nor the operator's app inspection showed
  device or per-channel sensors.

## Remote stream arguments reported by the probes

Both radios expose SoapyRemote stream arguments for remote format, scale,
MTU (default `1500` bytes), kernel receive window (default `44040192` bytes),
priority (default `0.5`, range `-1` to `1`), and protocol (default `udp`;
options `udp`, `tcp`, `none`). The flex1500d remote format options are `CF32`
and `CS16`; the RTL-SDR options are `CS8`, `CS16`, and `CF32`. The RTL-SDR
also reports stream buffer size (default `262144` bytes), ring buffers
(default `15`), and asynchronous USB buffers (default `0`).

These are driver-reported capabilities. In particular, a reported squelch
setting or TX gain range does not mean ChrissySDR is currently configuring it.
