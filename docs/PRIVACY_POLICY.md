# AdShield Pro v2.1 — Privacy Policy & Google Play Data Safety Disclosure

**Effective Date:** October 24, 2026  
**Application:** AdShield Pro (`com.adshield.pro`)

---

## 1. Core Privacy Commitment

AdShield Pro is engineered from the ground up as a local, on-device privacy and ad-blocking firewall for Android.
- **No Root Required:** AdShield Pro operates exclusively in standard Android user space.
- **No Browsing Data Collection or Sale:** We do **not** collect, transmit, log, or sell your browsing history, DNS queries, IP addresses, or personal data to any remote analytics or advertising server.
- **No HTTPS Interception or Root Certificates:** AdShield Pro never installs a trusted root Certificate Authority (CA) and never decrypts, inspects, or modifies encrypted TLS/HTTPS application payloads.

---

## 2. How Android `VpnService` Is Used

AdShield Pro uses the Android `android.net.VpnService` API strictly to establish a **local on-device virtual DNS interface** (`10.215.173.1/30` and `fd00:ad5:1e1d::1/126`):
1. **DNS-Only Routing:** Only traffic destined for the virtual DNS addresses (`10.215.173.2/32` and `fd00:ad5:1e1d::2/128`) is routed into the local TUN interface. All non-DNS TCP, UDP, and QUIC application traffic flows directly over your physical Wi-Fi or mobile data interface without passing through a remote VPN server.
2. **Local Rule Evaluation:** Each DNS query is evaluated locally in memory against your enabled blocklists and custom rules.
   - **Blocked Domains:** Immediately answered on-device with `NXDOMAIN` or `0.0.0.0` / `::` sinkhole in `< 1 ms`.
   - **Allowed Domains:** Forwarded using the protocol you select: encrypted **DNS-over-HTTPS (DoH, RFC 8484)** or **DNS-over-TLS (DoT, RFC 7858)**, or unencrypted UDP/TCP port 53. If DoH or DoT is unavailable, the app returns DNS `SERVFAIL` instead of silently downgrading that query to plaintext.

---

## 3. Upstream Encrypted DNS Providers

When a DNS query is allowed by your local filtering rules, it is resolved by your selected upstream DNS provider:
- **Cloudflare (`1.1.1.1`)** — Subject to the [Cloudflare 1.1.1.1 Privacy Commitment](https://developers.cloudflare.com/1.1.1.1/privacy/public-dns-resolver/).
- **Quad9 (`9.9.9.9`)** — Subject to the [Quad9 Privacy Policy](https://www.quad9.net/privacy/policy/).
- **AdGuard DNS** — Subject to the [AdGuard DNS Privacy Notice](https://adguard-dns.io/en/privacy.html).
- **Google Public DNS (`8.8.8.8`)** — Subject to the [Google Public DNS Privacy Notice](https://developers.google.com/speed/public-dns/privacy).
- **Custom DNS Resolver** — Subject to the privacy policy of the operator you configure.

---

## 4. Local Activity Logging & User Deletion Controls

- **Optional Local Logging:** Per-domain DNS query logs (`dns_logs` SQLite table) are stored **exclusively on your device** and can be toggled off at any time in the **Activity** tab.
- **Automatic Retention Pruning:** Local DNS logs are automatically purged after your selected retention window (`1`, `7`, or `30` days).
- **One-Tap Data Deletion:** Tapping **Clear History** in the **Activity** tab permanently deletes all local DNS logs and historical statistics from your device.
- **Platform Backups:** App-private data is excluded from Android cloud backup and device transfer. The manual JSON configuration export does not include DNS query history.

---

## 5. Google Play Data Safety Section Summary

| Data Category | Collected Off-Device? | Shared with Third Parties? | Purpose |
| :--- | :---: | :---: | :--- |
| Web Browsing / DNS Queries | **No** (Processed locally; allowed queries sent to user-selected DNS resolver) | **No** | Core Local Ad & Tracker Blocking |
| Installed App List (`QUERY_ALL_PACKAGES`) | **No** (Never leaves device) | **No** | Per-App VPN Split Tunneling Controls |
| Diagnostics / Crash Tracking SDKs | **None Included** | **No** | Zero third-party tracking SDKs |
