# AdShield Pro v2.1 — Google Play Console Release Checklist

Use this checklist before submitting `app-release.aab` (`com.adshield.pro`) to Google Play Production or Closed Testing tracks.

---

## 1. Google Play `VpnService` Policy Compliance

- [x] **Core Functionality:** The app's primary purpose is device-wide DNS privacy, tracker protection, and malicious domain filtering using a local `VpnService` interface.
- [x] **Explicit User Consent:** `VpnService.prepare(context)` is invoked on user interaction before starting `AdShieldVpnService`.
- [x] **No Undisclosed Traffic Tunneling:** Only virtual DNS server IPs (`10.215.173.2/32`, `fd00:ad5:1e1d::2/128`) are routed through the local TUN descriptor.
- [x] **No Root Certificate / HTTPS Interception:** The app never installs a user/system CA certificate and never decrypts third-party HTTPS traffic.
- [x] **Play Console Declaration:** In **App Content -> VPN Service**, declare that `VpnService` is used locally on-device to filter DNS queries and encrypt upstream DNS via DoH/DoT.

---

## 2. Android 14 / 15 Foreground Service (`FOREGROUND_SERVICE_SPECIAL_USE`) Declaration

- [x] Manifest declares `android.permission.FOREGROUND_SERVICE` and `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`.
- [x] `<service android:name=".service.AdShieldVpnService" android:foregroundServiceType="specialUse">` includes `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="local_dns_ad_and_tracker_filtering_vpn" />`.
- [ ] In Play Console **App Content -> Foreground Service Permissions**, submit the video walk-through showing the persistent notification ("AdShield Pro Active") and how the user can Pause or Stop the VPN directly from the notification or main dashboard.

---

## 3. `QUERY_ALL_PACKAGES` Permission Declaration

- [x] Used exclusively in `AdShieldRepository.getInstalledApps()` to populate the user-controlled **Per-App VPN Split Tunneling (Exclusions)** list.
- [x] Installed package names are never transmitted off the device.
- [ ] Alternatively, if targeting jurisdictions where Play restricts `QUERY_ALL_PACKAGES` for VPN split-tunneling, you may scope `<queries>` to `<intent><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent>`.

---

## 4. Free App Pricing

- [x] AdShield Pro is free and has no in-app purchases, subscriptions, or billing verifier.

---

## 5. Honest Store Listing & Marketing Compliance

- [x] Do **not** claim "100% ad blocking in all apps".
- [x] Clearly disclose that YouTube, Facebook, TikTok, and some streaming platforms multiplex video advertisements through first-party content servers and cannot be completely blocked by DNS filtering alone.
