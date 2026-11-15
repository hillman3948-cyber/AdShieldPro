# DNS upgrade & Video Safety

This document covers three connected changes: the DNS resolver upgrade (why a
CDN picks the wrong video server, and what to do about it), the Video Safety
guard (block the third-party video advert, keep the video), and the two
performance fixes for the "the app/site lags" and "the site comes up broken"
reports.

Everything here is a real, measured behaviour of this repository. Where a
capability is impossible, it says so instead of implying otherwise.

---

## 1. The DNS upgrade

### 1.1 Why DNS changes what a video does

An authoritative server picks a CDN edge from **the resolver's IP address**, not
from yours. If the resolver sits in another region, or does not forward a client
subnet, the CDN can answer with a distant edge: playback starts, then buffers, or
a manifest loads and segments stall. That is a DNS-caused video problem, and no
amount of ad-block tuning fixes it.

**EDNS Client Subnet (ECS)** is the mechanism: the resolver forwards a truncated
client subnet to authoritative servers that support it, so the CDN can pick a
nearby edge.

| Resolver | Endpoint | Forwards client subnet | Trade-off |
| --- | --- | --- | --- |
| Cloudflare 1.1.1.1 | `cloudflare-dns.com` | **No** | Best privacy, occasionally a distant video edge |
| Google Public DNS | `dns.google` | Yes | Anonymised logs, 24–48 h |
| Quad9 | `dns.quad9.net` | **No** | Malware blocking, no subnet |
| **Quad9 Secure + ECS** | `dns11.quad9.net` | Yes | Malware blocking **and** subnet forwarding |
| AdGuard DNS | `dns.adguard-dns.com` | No | Resolver-level ad blocking |
| Mullvad DNS | `dns.mullvad.net` | No | No query logging |

Policies above are quoted from each operator's published documentation. They are
not inferred, measured or guessed by this app, and the UI labels them as operator
claims.

### 1.2 What changed in the resolver

1. **`QUAD9_ECS` preset added** (`9.9.9.11`, `dns11.quad9.net`): malware blocking
   plus subnet forwarding — the closest thing to "the best DNS for video" among
   public resolvers that also filter.
2. **Per-provider ECS metadata** (`forwardsClientSubnet`, `ecsNote`) on
   `DnsProviderPreset`, shown in Settings and in the web DNS Lab. No capability is
   implied that the operator does not publish.
3. **Media-aware provider order** (`UpstreamDnsResolver.mediaAwareOrder`,
   `ResolverPool._mediaPreference`): for *media hostnames only*, providers that
   forward a client subnet are moved to the front of the plan. Ordinary browsing
   keeps the configured order. The setting is
   `preferGeoAccurateMediaDns` (default on) and is documented as revealing a
   truncated subnet to authoritative servers.
4. **Hedged failover instead of sequential timeouts**
   (`HEDGE_DELAY_MS = 250` in the native core, `raceDelayMs = 350` on the web).
   Previously a resolver that silently blackholed UDP/443 made *every* lookup wait
   out the full timeout (2.5 s UDP, 4 s DoH) before the next provider was tried.
   Now the runner-up starts after the head start and the first validated answer
   wins. A provider that loses a race is abandoned, **not** marked unhealthy.
5. **Circuit-breaker aware planning**: providers with an open circuit are skipped
   up front instead of being raced, so a known-bad resolver no longer delays the
   good one. When every circuit is open, the full plan is still tried — a real
   attempt beats a synthetic SERVFAIL.
6. **Bounded DoH bootstrap**: resolving a DoH endpoint's own hostname is capped at
   1.5 s and known endpoints resolve from a literal-address table, so a broken
   network can never stall the DoH path. New providers (`dns11.quad9.net`,
   `dns.mullvad.net`, AdGuard's real `94.140.14.14`) are in that table.

Fail-closed behaviour is unchanged: no silent plaintext downgrade, no response
merging, and nothing that has not passed `DnsResponseValidator`.

---

## 2. Video Safety — block the advert, keep the video

### 2.1 The honest limit, stated first

A DNS filter cannot read HTTP responses. **Adverts stitched into the stream
server-side** (YouTube/Netflix/Twitch pre-rolls sold by the platform) arrive on the
same URL as the video and cannot be separated by DNS. No DNS product can do this;
claiming otherwise would be a lie. See `docs/UNSUPPORTED.md`.

What DNS *can* do is remove **third-party video advert infrastructure** — the
VAST/VPAID ads, outstream players and ad SDKs that a page or app loads separately —
without touching the media CDN.

### 2.2 The three rule shapes that break playback

| Rule shape | Example | Why it breaks the video |
| --- | --- | --- |
| Entity-level | `\|\|googlevideo.com^`, `\|\|google.com^` | Blocks the media CDN with every advert on it |
| Substring wildcard | `*adserver*`, `*telemetry.*` | Matches a *substring of a hostname*, e.g. the legitimate `srv-adserver-3.googlevideo.com` |
| Shared-CDN blanket | `\|\|akamaized.net^`, `\|\|cloudfront.net^` | Breaks playback (and downloads) for unrelated services, while catching no ad server |

`guard/video-safety.js` and `engine/filter/DnsMediaPlaybackGuard.kt` refuse all
three wherever a list is parsed, with a reason attached:

- feed lines are counted in `videoRejected` / `rejectedVideoUnsafeRules` and
  skipped, never silently applied;
- user rules return `false` and are listed in `rejectedRules` so the UI can
  explain the refusal.

### 2.2b The exemption is scoped to the rule, not the hostname

The exemption fires **only when the matching rule is the media domain itself**
(`||googlevideo.com^`). It never covers a rule that names a specific host.

This distinction is not cosmetic. An earlier revision exempted whole media
registrable domains, and an audit of the bundled light feed found that this
silently unblocked **195 real ad and telemetry hosts**, including:

```
log.byteoversea.com          ads-api.tiktok.com        adeventtracker.spotify.com
views.hulu.com               mcs.tiktokv.com           promoted.soundcloud.com
player-telemetry.vimeo.com   smetrics.disneyplus.com   log-gateway.zoom.us
```

Those are deliberate blocks by the upstream list, and they stay blocked. The
regression is covered by two tests, one per defence layer:

- `the exemption never covers a specific ad/telemetry host (both defence layers)`
- `no shipped list rule for a specific ad/telemetry host is exempted`

Audit result on the shipped list: **0 apex-level rules on media domains, 211
specific host rules — all still enforced.**

### 2.3 What remains blocked

The exemption never covers advert delivery. These stay blocked (the supplement and
the Android baseline now carry the video-ad hosts explicitly):

```
pubads.g.doubleclick.net      securepubads.g.doubleclick.net
static.doubleclick.net        stats.g.doubleclick.net
s0.2mdn.net / s1.2mdn.net     pagead2.googlesyndication.com
imasdk.googleapis.com         (the video-ad SDK that plays in-player ads)
fwmrm.net / innovid.com / springserve.com / tremorhub.com
cdn.adsafeprotected.com       ads.twitch.tv, analytics.tiktok.com, ...
```

Verified matrix (from `tests/unit/video-safety.test.mjs` against the bundled
lists):

| Host | Third-party video ad | Result |
| --- | --- | --- |
| `pubads.g.doubleclick.net` | yes | **blocked** |
| `s0.2mdn.net` | yes | **blocked** |
| `imasdk.googleapis.com` | yes | **blocked** |
| `ads.twitch.tv` | yes | **blocked** |
| `rr3---sn-x.googlevideo.com` | no | allowed |
| `usher.ttvnw.net` | no | allowed |
| `ipv4-c001.nflxvideo.net` | no | allowed |
| `video.xx.fbcdn.net` | no | allowed |
| `vod-abc.akamaized.net` | no | allowed |

### 2.4 Why "don't break websites" is a hard rule

The three refused shapes are the same shapes that break ordinary websites:
`*telemetry.*`, `||akamaized.net^` and `||google.com^` take out logins, checkout
flows, script bundles and asset delivery. The guard therefore protects playback
*and* page functionality with one policy, and every refusal carries a one-sentence
explanation.

---

## 3. Performance: the lag and the broken-looking start

Both symptoms had the same shape: one long synchronous block, or one disk write,
on the path that every page load crosses.

| Symptom | Cause found | Fix | Measured |
| --- | --- | --- | --- |
| Web app freezes while it boots | the 80k-line list was parsed in one blocking pass | `loadFilterListAsync` parses in chunks and yields; a cut never lands inside a rule, so an `@@` exception can never become a block | 505 ms blocking → **84 ms longest block**, same rules loaded |
| Android DNS slow under load | settings were re-read from DataStore *and every JSON preference decoded* for every DNS packet | in-memory `cachedSettings` snapshot, invalidated on write | one decode per change instead of per packet |
| Android stutter / battery drain | daily statistics did a SQLite read-modify-write **per DNS query** | in-memory counter, one transaction per second, flushed on shutdown | ~60 transactions per page load → 1 |
| Slow first lookup after a network change | DoH bootstrap did an unbounded system lookup | 1.5 s bound + literal table for every shipped provider | bounded |

---

## 4. Configuration that actually gets the result

**Android app → Settings → DNS**
1. Provider: **Quad9 Secure + ECS (9.9.9.11)** — malware filtering *and*
   subnet forwarding. If you prefer Google, `Google Public DNS (8.8.8.8)` also
   forwards a subnet.
2. Protocol: **DoH** (or DoT if your network blocks it).
3. Keep **Geo-accurate DNS for video (ECS)** on.
4. Enable **failover** with a second provider — the hedge means this no longer
   costs latency.
5. Protection mode: **Standard** (not Strict) if any page or app breaks; Strict
   blocks telemetry endpoints that some login flows require.
6. If one specific app misbehaves, per-app DNS policy or an allow rule is the fix,
   not disabling filtering.

**Web app (omnishield-x) → DNS Lab**: the resolver table now shows each
provider's subnet policy, the health/EWMA per resolver, and the media preference
note. No configuration is needed — the policy is on by default.

---

## 5. What this does not do

- Not a VPN-grade firewall: only DNS addresses are routed.
- Cannot remove server-side stitched ads (see 2.1).
- Does not validate DNSSEC locally; it reports the upstream AD bit (unchanged).
- ECS is not free: it reveals a truncated subnet to authoritative servers. The
  toggle exists so that cost is the user's decision, not a default.
