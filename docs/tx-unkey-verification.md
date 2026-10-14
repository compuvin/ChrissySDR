# TX unkey verification and limits

The roadmap's immediate-unkey item covers timeout, disconnect, stream failure,
service shutdown, audio-recording failure, and application fault. This record
separates observed results from what an Android client cannot guarantee.

## Tests completed

- On 2026-10-14, the operator tested a 30-second TX timeout, the in-app TX
  button, and the notification Stop Audio action on a FLEX-6000 into a dummy
  load. Each stopped RF promptly; RX resumed after the timeout and in-app stop.
  Notification Stop Audio stopped RX/audio as designed, but subsequent TX
  preparation became stuck (tracked separately as GitHub issue #6).
- Local fake-SoapyRemote tests with synthetic microphone input verified that
  a reported TX stream error and a microphone read exception both reached the
  TX error path and completed `deactivateStream`/`closeStream` cleanup.
- A local fake-SoapyRemote test verified that an exception in an app callback
  immediately after TX activation triggers stream cleanup.
- The operator connected the Android app to a radio-free fake SoapyRemote
  server at 192.168.1.108:55133 and force-stopped it during virtual TX.
  Server log: TX activated at 19:05:02.721849 UTC; first samples received at
  19:05:02.841971; TX data and control sockets reset at 19:05:16.887–.888;
  all virtual streams were released at 19:05:16.888444. The server saw no
  normal `deactivateStream` request after force-stop. This verifies transport
  closure and server-side release after the process was stopped, not RF unkey
  timing on actual hardware. On reopening the app, the operator confirmed
  that neither RX nor TX resumed automatically.

## Limits and failure observations

- Android may terminate an app process without running `onDestroy()`. The app
  therefore cannot promise to send `deactivateStream` on a fatal crash or
  force-stop. A SoapyRemote server and its radio must fail safe when their
  client connection disappears. ChrissySDR can close streams on handled
  failures, but cannot independently guarantee RF unkey after its own process
  ceases to execute.
- Unplugging the FLEX-6000 Ethernet cable during TX was a different failure:
  the radio reportedly unkeyed after about 10 seconds, ChrissySDR could not
  confirm TX shutdown, and SoapyRemote appeared stuck. That did not test loss
  of the app-to-SoapyRemote connection and did not establish immediate unkey.
- The fake-server test exercises no RF path. Its server-side release proves
  only that the virtual TX stream is cleaned up when the app sockets vanish.

The app must never treat an unconfirmed shutdown as confirmed RF-off. On an
actual connection failure, the operator should check the radio/server state.
