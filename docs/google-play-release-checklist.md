# Google Play Release Checklist

For ChrissySDR's first paid Google Play release. This is a publishing checklist,
separate from the [product roadmap](product-roadmap.md). Recheck Play Console
requirements when submitting; Google may change them.

## Account and pricing

- [ ] Create and verify a [Google Play developer account](https://support.google.com/googleplay/android-developer/answer/6112435). Complete any identity and device-verification tasks shown in Play Console.
- [ ] Set up a [payments profile](https://support.google.com/googleplay/android-developer/answer/3092739) for paid-app revenue.
- [ ] Create ChrissySDR in Play Console as a **paid app** with package name `com.kb1jdx.chrissysdr`; choose its price and countries. A published free app cannot later be changed to paid under the same package name. [Pricing rules](https://support.google.com/googleplay/android-developer/answer/6334373)

## App and policy readiness

- [ ] Audit the microphone-originated TX data sent over the current cleartext SoapyRemote LAN connection against Google's [user-data security policy](https://support.google.com/googleplay/android-developer/answer/10144311). Determine a compliant transport and disclosures before submission.
- [ ] Provide a safe way for Google reviewers to inspect the app without access to the operator's LAN radio or an internet-exposed transmitter; document [review access instructions](https://support.google.com/googleplay/android-developer/answer/15748846).
- [ ] Publish a ChrissySDR privacy policy and link it both in the app and in Play Console. Describe microphone use, LAN radio traffic, local profiles and QSO logs, exports, retention, and deletion accurately. [Privacy policy requirements](https://support.google.com/googleplay/android-developer/answer/18258653)
- [ ] Complete the [Data safety form](https://support.google.com/googleplay/android-developer/answer/10787469) consistently with the app's actual data flows and privacy policy.
- [ ] Review and resolve release-relevant open issues, including [Stop Audio leaving TX unavailable after RX restart](https://github.com/compuvin/ChrissySDR/issues/6).

## Play build and listing

- [ ] Create and securely back up an upload key; configure [Play App Signing](https://developer.android.com/studio/publish/app-signing).
- [ ] Build and test a release-signed **Android App Bundle (`.aab`)**. Do not upload the debug-signed GitHub APK. Confirm the package name, version, permissions, and target API against [current Play requirements](https://support.google.com/googleplay/android-developer/answer/11926878).
- [ ] Capture current app screenshots, and prepare the store icon and feature graphic to [Play's asset specifications](https://support.google.com/googleplay/android-developer/answer/9866151).
- [ ] Complete the store description, contact details, target audience, content rating, ads declaration, and other [App content](https://support.google.com/googleplay/android-developer/answer/9859455) questions.
- [ ] Complete the [foreground media-service declaration](https://support.google.com/googleplay/android-developer/answer/13392821), including the required demonstration video and explanation of background RX audio.

## Testing and launch

- [ ] Upload the bundle to a Play testing track and test the Play-delivered installation, including RX, TX, profiles, QSO log, permissions, notifications, and updates. [Release tracks](https://support.google.com/googleplay/android-developer/answer/9859348)
- [ ] If the account is a personal account created after November 13, 2023, run the required [closed test](https://support.google.com/googleplay/android-developer/answer/14151465) with at least 12 testers opted in continuously for 14 days, then apply for production access.
- [ ] Warn existing GitHub APK testers that the Play-signed app may not update their debug-signed installation in place. Have them export QSO logs and preserve other important local data before changing installations. [Android signing](https://developer.android.com/studio/publish/app-signing)
- [ ] Submit the paid production release for review, address any Play Console findings, and verify the live listing, price, install, and automatic-update path. [Production release process](https://support.google.com/googleplay/android-developer/answer/9859348)
