# Works with ChrissySDR

ChrissySDR operates radios through SoapyRemote. The radios below have
SoapySDR drivers and should work with ChrissySDR when the appropriate driver
is installed on the SoapyRemote server. RX/TX describes the radio's
capability; the final column shows what has actually been tested in
ChrissySDR. Available modes and controls depend on what the driver reports.

| Radio or family | RX | TX | SoapySDR driver | Tested with ChrissySDR |
| --- | --- | --- | --- | --- |
| FLEX-1500 via flex1500d | Yes | Yes | [flex1500d Soapy adapter](https://github.com/compuvin/flex1500d) | RX and AM, NFM, USB, LSB TX |
| RTL-SDR Blog V4 | Yes | No | [SoapyRTLSDR](https://github.com/pothosware/SoapyRTLSDR) | RX |
| FLEX-6700 | Yes | Yes | [SoapyFlex6000](https://github.com/compuvin/SoapyFlex6000) | RX and TX |
| Other RTL-SDR-compatible receivers | Yes | No | [SoapyRTLSDR](https://github.com/pothosware/SoapyRTLSDR) | Not yet |
| HackRF | Yes | Yes | [SoapyHackRF](https://github.com/pothosware/SoapyHackRF) | Not yet |
| Airspy receivers | Yes | No | [SoapyAirspy](https://github.com/pothosware/SoapyAirspy) | Not yet |
| Airspy HF+ receivers | Yes | No | [SoapyAirspyHF](https://github.com/pothosware/SoapyAirspyHF) | Not yet |
| SDRplay RSP receivers | Yes | No | [SoapySDRPlay3](https://github.com/pothosware/SoapySDRPlay3) | Not yet |
| ADALM-Pluto / PlutoSDR | Yes | Yes | [SoapyPlutoSDR](https://github.com/pothosware/SoapyPlutoSDR) | Not yet |
| bladeRF | Yes | Yes | [SoapyBladeRF](https://github.com/pothosware/SoapyBladeRF) | Not yet |
| LimeSDR boards supported by Lime Suite | Yes | Yes | [SoapyLMS7](https://wiki.myriadrf.org/Lime_Suite) | Not yet |
| UHD-supported radios, including USRP models | Yes | Yes | [SoapyUHD](https://github.com/pothosware/SoapyUHD) | Not yet |
| Red Pitaya | Yes | Yes | [SoapyRedPitaya](https://github.com/pothosware/SoapyRedPitaya) | Not yet |
| NetSDR-protocol receivers | Yes | No | [SoapyNetSDR](https://github.com/pothosware/SoapyNetSDR) | Not yet |
| Audio-device-based SDR setups | Depends on setup | Depends on setup | [SoapyAudio](https://github.com/pothosware/SoapyAudio) | Not yet |

Have another SoapySDR radio, or results for one listed here? Please
[open a ChrissySDR issue](https://github.com/compuvin/ChrissySDR/issues/new)
with the radio model, Soapy driver, RX/TX results, and any limitations.
This helps us turn expected compatibility into tested compatibility.

Detailed results for radios already tested are in the
[ChrissySDR test log](test-log.md).
