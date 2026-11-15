#!/usr/bin/env python3
"""Probe every free threat-intelligence / emergency endpoint OMNISHIELD X intends to use.

The Android app has to make a claim about each connector: is it reachable without a payment,
an account or an API key? That claim cannot be made from documentation alone, so this script
performs a real HTTP request from a clean CI runner and records exactly what came back.

It never sends credentials. It never writes endpoint data into the APK. Results are committed
as evidence (docs/intel/live-probe.json + docs/INTEL_LIVE_SOURCES.md) so the connector catalogue
can be regenerated from measurement instead of assertion.

Usage:
  python3 tools/probe_intel_endpoints.py [--json out.json] [--md out.md] [--timeout 20]
                                         [--workers 6] [--only id1,id2]
"""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_JSON = ROOT / "docs/intel/live-probe.json"
DEFAULT_MD = ROOT / "docs/INTEL_LIVE_SOURCES.md"

UA = "OmniShieldX-ConnectorProbe/1.0 (+https://github.com/yawiyuson231-oss/AdShieldPro)"
MAX_BYTES = 400_000

# Candidates are grouped by the module that would consume them. `expect` records what the
# connector would do with the body, not a promise that the provider keeps it stable.
CANDIDATES: list[dict] = [
    # ---------------- CYBER / FIN: abuse.ch family ----------------
    dict(id="urlhaus-recent-api", module="CYBER", method="POST",
         url="https://urlhaus-api.abuse.ch/v1/urls/recent/",
         data="", expect="JSON {query_status, urls[]}",
         terms="abuse.ch free use, no account tier for this endpoint"),
    dict(id="urlhaus-csv-recent", module="CYBER", method="GET",
         url="https://urlhaus.abuse.ch/downloads/csv_recent/",
         expect="CSV, comment header (#)",
         terms="abuse.ch public download"),
    dict(id="urlhaus-text-recent", module="CYBER", method="GET",
         url="https://urlhaus.abuse.ch/downloads/text_recent/",
         expect="plain text, one URL per line",
         terms="abuse.ch public download"),
    dict(id="malwarebazaar-recent", module="CYBER", method="POST",
         url="https://mb-api.abuse.ch/api/v1/", data="query=get_recent&selector=50",
         expect="JSON {query_status, data[]}",
         terms="abuse.ch; API queries may require a free Auth-Key"),
    dict(id="malwarebazaar-export", module="CYBER", method="GET",
         url="https://bazaar.abuse.ch/export/csv/recent/",
         expect="ZIP/CSV export",
         terms="abuse.ch public export"),
    dict(id="threatfox-api", module="CYBER", method="POST",
         url="https://threatfox-api.abuse.ch/api/v1/",
         data='{"query":"get_iocs","days":1}',
         headers={"Content-Type": "application/json"},
         expect="JSON {query_status, data[]}",
         terms="abuse.ch; API may require a free Auth-Key"),
    dict(id="feodo-json", module="CYBER", method="GET",
         url="https://feodotracker.abuse.ch/downloads/ipblocklist.json",
         expect="JSON array of C2 IP records",
         terms="abuse.ch public download"),
    dict(id="feodo-csv", module="NET", method="GET",
         url="https://feodotracker.abuse.ch/downloads/ipblocklist.csv",
         expect="CSV with # header",
         terms="abuse.ch public download"),
    dict(id="sslbl-ja3", module="CYBER", method="GET",
         url="https://sslbl.abuse.ch/blacklist/ja3_fingerprints.csv",
         expect="CSV with # header",
         terms="abuse.ch public download"),
    dict(id="sslbl-ip", module="NET", method="GET",
         url="https://sslbl.abuse.ch/blacklist/sslipblacklist.txt",
         expect="plain text",
         terms="abuse.ch public download"),

    # ---------------- CYBER: vulnerability intelligence ----------------
    dict(id="cisa-kev", module="CYBER", method="GET",
         url="https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json",
         expect="JSON {vulnerabilities[]}",
         terms="US Government public domain"),
    dict(id="first-epss", module="CYBER", method="GET",
         url="https://api.first.org/data/v1/epss?cve=CVE-2024-3400",
         expect="JSON {data[]}",
         terms="FIRST EPSS free API"),
    dict(id="nvd-cve", module="CYBER", method="GET",
         url="https://services.nvd.nist.gov/rest/json/cves/2.0?cveId=CVE-2024-3400",
         expect="JSON {vulnerabilities[]}",
         terms="NIST public API, rate limited without key"),
    dict(id="osv-query", module="CYBER", method="POST",
         url="https://api.osv.dev/v1/query",
         data='{"package":{"name":"okhttp","ecosystem":"Maven"},"version":"4.12.0"}',
         headers={"Content-Type": "application/json"},
         expect="JSON {vulns[]}",
         terms="OSV.dev free, no key"),
    dict(id="circl-hashlookup", module="CYBER", method="GET",
         url="https://hashlookup.circl.lu/lookup/sha256/e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
         expect="JSON {message} or 404 JSON",
         terms="CIRCL free, rate limited"),
    dict(id="misp-warninglists-tranco", module="FIN", method="GET",
         url="https://raw.githubusercontent.com/MISP/misp-warninglists/main/lists/tranco/list.json",
         expect="JSON {list[]}",
         terms="MISP open source / CC",
         skip="large payload; verified through GitHub raw availability separately"),

    # ---------------- FIN: phishing ----------------
    dict(id="openphish-feed", module="FIN", method="GET",
         url="https://openphish.com/feed.txt",
         expect="plain text, one URL per line",
         terms="OpenPhish free community feed; commercial use restricted"),
    dict(id="phishtank-online-valid", module="FIN", method="GET",
         url="https://data.phishtank.com/data/online-valid.json",
         expect="JSON array (registration may be required)",
         terms="PhishTank requires a free app key / registration"),
    dict(id="mitchellkrogza-phishing", module="FIN", method="GET",
         url="https://raw.githubusercontent.com/mitchellkrogza/Phishing.Database/master/phishing-links-ACTIVE.txt",
         expect="plain text",
         terms="MIT-licensed community dataset",
         skip="multi-MB list; cached copy is downloaded by the app only on explicit sync"),
    dict(id="misp-warninglists-github-path", module="FIN", method="HEAD",
         url="https://raw.githubusercontent.com/MISP/misp-warninglists/main/lists/",
         expect="GitHub raw reachability",
         terms="MISP open source"),
    dict(id="spamhaus-drop", module="NET", method="GET",
         url="https://www.spamhaus.org/drop/drop.txt",
         expect="plain text",
         terms="Spamhaus free for non-commercial use, redistribution restricted"),
    dict(id="blocklist-de", module="NET", method="GET",
         url="https://lists.blocklist.de/lists/all.txt",
         expect="plain text",
         terms="blocklist.de free, fair-use"),
    dict(id="digitalSide-domains", module="FIN", method="GET",
         url="https://raw.githubusercontent.com/digitalSide/idware/main/domains.txt",
         expect="plain text (may not exist)",
         terms="community dataset"),

    # ---------------- NET: routing / identity ----------------
    dict(id="ripestat-prefix", module="NET", method="GET",
         url="https://stat.ripe.net/data/prefix-overview/data.json?resource=8.8.8.8",
         expect="JSON {data{...}}",
         terms="RIPE NCC free public API"),
    dict(id="ripestat-asn", module="NET", method="GET",
         url="https://stat.ripe.net/data/as-overview/data.json?resource=AS15169",
         expect="JSON {data{...}}",
         terms="RIPE NCC free public API"),
    dict(id="ripestat-routing", module="NET", method="GET",
         url="https://stat.ripe.net/data/routing-status/data.json?resource=8.8.8.8",
         expect="JSON {data{...}}",
         terms="RIPE NCC free public API"),
    dict(id="rdap-org", module="NET", method="GET",
         url="https://rdap.org/domain/example.com",
         expect="JSON RDAP object",
         terms="community RDAP redirector; IANA bootstrap"),
    dict(id="rdap-arin", module="NET", method="GET",
         url="https://rdap.arin.net/registry/ip/8.8.8.8",
         expect="JSON RDAP object",
         terms="ARIN public RDAP"),
    dict(id="iana-rdap-bootstrap", module="NET", method="GET",
         url="https://data.iana.org/rdap/dns.json",
         expect="JSON bootstrap registry",
         terms="IANA public data"),
    dict(id="ipify", module="NET", method="GET",
         url="https://api64.ipify.org?format=json",
         expect='JSON {"ip":"..."}',
         terms="ipify free tier, no key"),
    dict(id="cloudflare-trace", module="NET", method="GET",
         url="https://www.cloudflare.com/cdn-cgi/trace",
         expect="key=value text including ip=",
         terms="Cloudflare edge diagnostic, public"),
    dict(id="cloudflare-doh", module="NET", method="GET",
         url="https://cloudflare-dns.com/dns-query?name=example.com&type=A",
         headers={"Accept": "application/dns-json"},
         expect="JSON DNS response",
         terms="Cloudflare public DoH; existing DNS engine owns resolution"),
    dict(id="quad9-doh", module="NET", method="GET",
         url="https://dns.quad9.net/dns-query?name=example.com&type=A",
         headers={"Accept": "application/dns-json"},
         expect="JSON DNS response",
         terms="Quad9 public DoH"),
    dict(id="iana-service-names", module="NET", method="GET",
         url="https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.csv",
         expect="CSV",
         terms="IANA public registry"),
    dict(id="team-cymru-dns", module="NET", method="GET",
         url="https://dns.google/resolve?name=example.com&type=A",
         expect="JSON DNS response",
         terms="Google public DoH"),

    # ---------------- EMRG: hazards ----------------
    dict(id="usgs-all-hour", module="EMRG", method="GET",
         url="https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.geojson",
         expect="GeoJSON FeatureCollection",
         terms="USGS public domain"),
    dict(id="usgs-m45-day", module="EMRG", method="GET",
         url="https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson",
         expect="GeoJSON FeatureCollection",
         terms="USGS public domain"),
    dict(id="usgs-ph-radius", module="EMRG", method="GET",
         url="https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson&latitude=14.6&longitude=121.0&maxradiuskm=600&minmagnitude=4&limit=10&orderby=time",
         expect="GeoJSON FeatureCollection",
         terms="USGS public domain"),
    dict(id="gdacs-rss", module="EMRG", method="GET",
         url="https://www.gdacs.org/xml/rss.xml",
         expect="RSS 2.0 XML",
         terms="GDACS public feed (EU JRC / UN)"),
    dict(id="gdacs-rss-24h", module="EMRG", method="GET",
         url="https://www.gdacs.org/xml/rss_24h.xml",
         expect="RSS 2.0 XML",
         terms="GDACS public feed"),
    dict(id="emsc-seismicportal", module="EMRG", method="GET",
         url="https://www.seismicportal.eu/fdsnws/event/1/query?format=geojson&limit=5&orderby=time",
         expect="GeoJSON FeatureCollection",
         terms="EMSC public web service"),
    dict(id="nws-alerts", module="EMRG", method="GET",
         url="https://api.weather.gov/alerts/active?area=PH",
         expect="JSON (US coverage only)",
         terms="US NWS public API; User-Agent required"),
    dict(id="reliefweb-disasters", module="EMRG", method="GET",
         url="https://api.reliefweb.int/v1/disasters?appname=omnishield-x&limit=5&preset=latest",
         expect="JSON {data[]}",
         terms="ReliefWeb API free; appname required, no key"),
    dict(id="meteoalarm-ph", module="EMRG", method="GET",
         url="https://feeds.meteoalarm.org/api/v1/warnings/feeds-philippines",
         expect="CAP/Atom JSON or XML",
         terms="MeteoAlarm public feeds (Europe coverage)"),
    dict(id="pagasa-home", module="EMRG", method="GET",
         url="https://www.pagasa.dost.gov.ph/",
         expect="HTML (no documented public API)",
         terms="PAGASA official site; no public machine API documented"),
    dict(id="phivolcs-home", module="EMRG", method="GET",
         url="https://www.phivolcs.dost.gov.ph/",
         expect="HTML (no documented public API)",
         terms="PHIVOLCS official site"),
    dict(id="ndrrmc-home", module="EMRG", method="GET",
         url="https://ndrrmc.gov.ph/",
         expect="HTML (no documented public API)",
         terms="NDRRMC official site"),
    dict(id="open-meteo", module="EMRG", method="GET",
         url="https://api.open-meteo.com/v1/forecast?latitude=14.6&longitude=121.0&current=temperature_2m,precipitation",
         expect="JSON forecast",
         terms="Open-Meteo free non-commercial API, no key"),
    dict(id="nasa-firms-area", module="EMRG", method="GET",
         url="https://firms.modaps.eosdis.nasa.gov/api/area/csv/NO_KEY/VIIRS_SNPP_NRT/121,14,123,15/1",
         expect="401/403 without MAP_KEY",
         terms="NASA FIRMS requires a free registered MAP_KEY"),
    dict(id="copernicus-ems", module="EMRG", method="GET",
         url="https://emergency.copernicus.eu/mapping/list-of-activations-rapid",
         expect="HTML",
         terms="Copernicus EMS public site"),

    # ================= replacement / expansion candidates (2026-11) =================
    # URLhaus and MalwareBazaar public downloads started returning HTTP 410 and their APIs
    # answer 401 without an Auth-Key. These probes record that, and test what still works.
    dict(id="urlhaus-api-url", module="CYBER", method="POST",
         url="https://urlhaus-api.abuse.ch/v1/url/", data="url=https%3A%2F%2Fexample.com",
         expect="JSON (401 without Auth-Key)",
         terms="abuse.ch Auth-Key required"),
    dict(id="urlhaus-payloads", module="CYBER", method="GET",
         url="https://urlhaus.abuse.ch/downloads/csv_payloads/",
         expect="CSV or HTTP 410",
         terms="abuse.ch public download"),
    dict(id="malwarebazaar-hash", module="CYBER", method="POST",
         url="https://mb-api.abuse.ch/api/v1/",
         data="query=get_info&hash=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
         expect="JSON (401 without Auth-Key)",
         terms="abuse.ch Auth-Key required"),
    dict(id="misp-warninglists-special-use", module="CYBER", method="GET",
         url="https://raw.githubusercontent.com/MISP/misp-warninglists/main/lists/special-use-domain/list.json",
         expect="JSON {list[]}",
         terms="MISP open source"),
    dict(id="misp-warninglists-url-shortener", module="FIN", method="GET",
         url="https://raw.githubusercontent.com/MISP/misp-warninglists/main/lists/url-shortener/list.json",
         expect="JSON {list[]}",
         terms="MISP open source"),

    dict(id="phishstats-api", module="FIN", method="GET",
         url="https://phishstats.info:2096/api/phishing?_sort=-date&_size=5",
         expect="JSON array of phishing records",
         terms="PhishStats free public API"),
    dict(id="scamsniffer-domains", module="FIN", method="GET",
         url="https://raw.githubusercontent.com/scamsniffer/scam-database/main/blacklist/domains.json",
         expect="JSON array of domains",
         terms="MIT-licensed community dataset"),
    dict(id="phishing-army", module="FIN", method="GET",
         url="https://phishing.army/download/phishing_army_blocklist_extended.txt",
         expect="hosts/domain list",
         terms="phishing.army free community list"),
    dict(id="ut1-phishing", module="FIN", method="GET",
         url="https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/phishing/domains",
         expect="domain list",
         terms="UT1 open data, free"),
    dict(id="hagezi-tif", module="FIN", method="GET",
         url="https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/tif.txt",
         expect="hosts list",
         terms="HaGeZi GPL-3.0 community list"),
    dict(id="blocklistproject-phishing", module="FIN", method="GET",
         url="https://blocklistproject.github.io/Lists/phishing.txt",
         expect="domain list",
         terms="Blocklist Project free list"),
    dict(id="malware-filter-phishing", module="FIN", method="GET",
         url="https://malware-filter.gitlab.io/malware-filter/phishing-filter.txt",
         expect="domain list",
         terms="malware-filter open source"),

    dict(id="firehol-level1", module="CYBER", method="GET",
         url="https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_level1.netset",
         expect="CIDR list",
         terms="FireHOL open data"),
    dict(id="et-compromised", module="CYBER", method="GET",
         url="https://rules.emergingthreats.net/blockrules/compromised-ips.txt",
         expect="IP list",
         terms="ET Open free, attribution requested"),
    dict(id="cins-badguys", module="CYBER", method="GET",
         url="https://cinsscore.com/list/ci-badguys.txt",
         expect="IP list",
         terms="CINS free list"),
    dict(id="tor-exits", module="CYBER", method="GET",
         url="https://check.torproject.org/torbulkexitlist",
         expect="IP list",
         terms="Tor Project public exit list"),
    dict(id="bambenek-c2", module="CYBER", method="GET",
         url="https://osint.bambenekconsulting.com/feeds/c2-ipmasterlist.txt",
         expect="CSV text",
         terms="Bambenek free with attribution"),
    dict(id="botvrij-iocs", module="CYBER", method="GET",
         url="https://www.botvrij.eu/data/ioclist.ip-dst.1d",
         expect="IP list",
         terms="botvrij.eu free/open"),
    dict(id="ipsum-level3", module="CYBER", method="GET",
         url="https://raw.githubusercontent.com/stamparm/ipsum/master/levels/3.txt",
         expect="IP list",
         terms="IPsum open data"),
    dict(id="sslbl-cert", module="CYBER", method="GET",
         url="https://sslbl.abuse.ch/blacklist/sslblacklist.csv",
         expect="CSV with # header",
         terms="abuse.ch public download"),

    dict(id="cveorg-api", module="CYBER", method="GET",
         url="https://cveawg.mitre.org/api/cve/CVE-2024-3400",
         expect="JSON CVE record",
         terms="CVE Program public API"),
    dict(id="exploitdb-csv", module="CYBER", method="GET",
         url="https://gitlab.com/exploit-database/exploitdb/-/raw/main/files_exploits.csv",
         expect="CSV",
         terms="Exploit-DB GPLv2 open data"),
    dict(id="circl-misp-manifest", module="CYBER", method="GET",
         url="https://www.circl.lu/doc/misp/feed-osint/manifest.json",
         expect="JSON manifest",
         terms="CIRCL MISP OSINT feed, free"),
    dict(id="depsdev-version", module="CYBER", method="GET",
         url="https://api.deps.dev/v3alpha/systems/maven/packages/com.squareup.okhttp3%3Aokhttp/versions/4.12.0",
         expect="JSON version/advisory info",
         terms="Google deps.dev free API"),
    dict(id="sonatype-ossindex", module="CYBER", method="POST",
         url="https://ossindex.sonatype.org/api/v3/component-report",
         data='{"coordinates":["pkg:maven/com.squareup.okhttp3/okhttp@4.12.0"]}',
         headers={"Content-Type": "application/json"},
         expect="JSON vulnerability report",
         terms="Sonatype OSS Index free, rate limited"),
    dict(id="abuseipdb-nokey", module="CYBER", method="GET",
         url="https://api.abuseipdb.com/api/v2/check?ipAddress=8.8.8.8",
         expect="401 without key",
         terms="AbuseIPDB requires a free account key"),
    dict(id="virustotal-nokey", module="CYBER", method="GET",
         url="https://www.virustotal.com/api/v3/ip_addresses/8.8.8.8",
         expect="401 without key",
         terms="VirusTotal API requires a key"),
    dict(id="urlscan-search", module="FIN", method="GET",
         url="https://urlscan.io/api/v1/search/?q=domain%3Aexample.com",
         expect="JSON or 401",
         terms="urlscan.io API key required for most queries"),

    dict(id="reliefweb-v2", module="EMRG", method="GET",
         url="https://api.reliefweb.int/v2/disasters?appname=omnishield-x&limit=5",
         expect="JSON {data[]} or HTTP 410",
         terms="ReliefWeb API free, appname required"),
    dict(id="reliefweb-v1-appname", module="EMRG", method="GET",
         url="https://api.reliefweb.int/v1/disasters?appname=omnishield-x&limit=5",
         expect="410 confirms v1 retirement",
         terms="ReliefWeb API"),
    dict(id="emsc-json", module="EMRG", method="GET",
         url="https://www.seismicportal.eu/fdsnws/event/1/query?format=json&limit=5&orderby=time",
         expect="JSON events",
         terms="EMSC public web service"),
    dict(id="nasa-eonet", module="EMRG", method="GET",
         url="https://eonet.gsfc.nasa.gov/api/v3/events?status=open&limit=5",
         expect="JSON events",
         terms="NASA EONET public API, no key"),
    dict(id="open-meteo-flood", module="EMRG", method="GET",
         url="https://flood-api.open-meteo.com/v1/flood?latitude=14.6&longitude=121.0&daily=river_discharge",
         expect="JSON river discharge forecast",
         terms="Open-Meteo free non-commercial"),
    dict(id="open-meteo-air", module="EMRG", method="GET",
         url="https://air-quality-api.open-meteo.com/v1/air-quality?latitude=14.6&longitude=121.0&current=pm2_5",
         expect="JSON air quality",
         terms="Open-Meteo free non-commercial"),
    dict(id="gdacs-api", module="EMRG", method="GET",
         url="https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?eventlist=EQ,TC,FL,WF&fromDate=2026-10-01&toDate=2026-11-06&limit=10",
         expect="JSON event list",
         terms="GDACS public API"),
    dict(id="wmo-swic-ph", module="EMRG", method="GET",
         url="https://severeweather.wmo.int/v2/cap-alerts/PH/",
         expect="CAP XML listing",
         terms="WMO SWIC public CAP aggregation"),
    dict(id="usgs-significant-week", module="EMRG", method="GET",
         url="https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_week.geojson",
         expect="GeoJSON",
         terms="USGS public domain"),
    dict(id="pingasc-phivolcs", module="EMRG", method="GET",
         url="https://earthquake.phivolcs.dost.gov.ph/",
         expect="HTML (no public API)",
         terms="PHIVOLCS official site"),

    dict(id="ripestat-network-info", module="NET", method="GET",
         url="https://stat.ripe.net/data/network-info/data.json?resource=8.8.8.8",
         expect="JSON",
         terms="RIPE NCC free API"),
    dict(id="ripestat-whois", module="NET", method="GET",
         url="https://stat.ripe.net/data/whois/data.json?resource=AS15169",
         expect="JSON",
         terms="RIPE NCC free API"),
    dict(id="public-suffix-list", module="NET", method="GET",
         url="https://publicsuffix.org/list/public_suffix_list.dat",
         expect="PSL text",
         terms="Mozilla Public Suffix List, MPL-2.0"),
    dict(id="iana-ipv4-special", module="NET", method="GET",
         url="https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry-1.csv",
         expect="CSV",
         terms="IANA public registry"),
    dict(id="bgpview-ip", module="NET", method="GET",
         url="https://api.bgpview.io/ip/8.8.8.8",
         expect="JSON",
         terms="BGPView free, rate limited"),
    dict(id="ipinfo-json", module="NET", method="GET",
         url="https://ipinfo.io/json",
         expect="JSON (token adds quota)",
         terms="ipinfo free tier, rate limited"),
    dict(id="arin-rest-whois", module="NET", method="GET",
         url="https://whois.arin.net/rest/ip/8.8.8.8.json",
         expect="JSON whois",
         terms="ARIN public REST"),
    dict(id="quad9-dns-json", module="NET", method="GET",
         url="https://dns.quad9.net:5053/dns-query?name=example.com&type=A",
         headers={"Accept": "application/dns-json"},
         expect="JSON or wire-only response",
         terms="Quad9 public DoH"),
    # ---------------- NEWS: World Monitor global news layer ----------------
    # These five endpoints are what `wm.news.*` actually fetches. If one starts answering with
    # an HTML challenge page instead of a feed, the News layer silently loses an outlet — which
    # is exactly the failure this list exists to catch before a release.
    dict(id="wm-news-bbc-world", module="NEWS", method="GET",
         url="https://feeds.bbci.co.uk/news/world/rss.xml",
         expect="RSS 2.0 XML with <item><title> blocks",
         terms="BBC public RSS; headline + summary + article link"),
    dict(id="wm-news-aljazeera", module="NEWS", method="GET",
         url="https://www.aljazeera.com/xml/rss/all.xml",
         expect="RSS 2.0 XML with <item> blocks",
         terms="Al Jazeera public RSS"),
    dict(id="wm-news-dw-world", module="NEWS", method="GET",
         url="https://rss.dw.com/rdf/rss-en-world",
         expect="RDF / RSS 1.0 XML with <item><dc:date> blocks",
         terms="Deutsche Welle public RSS"),
    dict(id="wm-news-un", module="NEWS", method="GET",
         url="https://news.un.org/feed/subscribe/en/news/all/rss.xml",
         expect="RSS 2.0 XML with <item> blocks",
         terms="UN News public RSS"),
    dict(id="wm-news-rappler", module="NEWS", method="GET",
         url="https://www.rappler.com/feed/",
         expect="RSS 2.0 XML with <item> blocks",
         terms="Rappler public RSS"),
    # Kept as a failing-but-recorded entry on purpose: GDELT 2.0 Geo was listed as a live source
    # and returned 404 for keyless requests, leaving the News layer empty. The probe records the
    # real answer so the catalogue cannot quietly re-add it.
    dict(id="gdelt-geo-2-0", module="NEWS", method="GET",
         url="https://api.gdeltproject.org/api/v2/geo/geo?query=earthquake&format=geojson&timespan=24h",
         expect="GeoJSON FeatureCollection (was listed; verify before re-enabling)",
         terms="GDELT open data"),
]

SKIP_REASON_DEFAULT = "skipped by catalogue policy"


def probe(item: dict, timeout: int) -> dict:
    """Perform one request; return a JSON-safe record. Never raises."""
    if item.get("skip"):
        return {
            "id": item["id"], "module": item["module"], "url": item["url"],
            "method": item["method"], "skipped": True, "skip_reason": item["skip"],
            "expect": item.get("expect", ""), "terms": item.get("terms", ""),
        }
    request = urllib.request.Request(item["url"], method=item["method"])
    request.add_header("User-Agent", UA)
    request.add_header("Accept", "*/*")
    for key, value in (item.get("headers") or {}).items():
        request.add_header(key, value)
    body = None
    if item["method"] in ("POST", "PUT", "PATCH"):
        body = (item.get("data") or "").encode("utf-8")
        if "Content-Type" not in (item.get("headers") or {}):
            request.add_header("Content-Type", "application/x-www-form-urlencoded")
        request.data = body

    record = {
        "id": item["id"], "module": item["module"], "url": item["url"],
        "method": item["method"], "expect": item.get("expect", ""),
        "terms": item.get("terms", ""), "skipped": False,
        "checked_at_utc": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read(MAX_BYTES)
            record["http_status"] = response.status
            record["content_type"] = response.headers.get("Content-Type", "")
            record["bytes_sampled"] = len(raw)
            record["elapsed_ms"] = int((time.monotonic() - started) * 1000)
            record["reachable"] = 200 <= response.status < 300
            text = raw.decode("utf-8", "replace")
            record["sample"] = " ".join(text[:220].split())
    except urllib.error.HTTPError as error:
        raw = b""
        try:
            raw = error.read(MAX_BYTES)
        except Exception:  # noqa: BLE001 - a failed error-body read must not mask the status
            pass
        record["http_status"] = error.code
        record["content_type"] = error.headers.get("Content-Type", "") if error.headers else ""
        record["bytes_sampled"] = len(raw)
        record["elapsed_ms"] = int((time.monotonic() - started) * 1000)
        record["reachable"] = False
        record["sample"] = " ".join(raw[:220].decode("utf-8", "replace").split())
    except Exception as error:  # noqa: BLE001 - every failure mode is recorded, none is fatal
        record["http_status"] = None
        record["reachable"] = False
        record["elapsed_ms"] = int((time.monotonic() - started) * 1000)
        record["error"] = f"{type(error).__name__}: {error}"
    return record


def verdict(record: dict) -> str:
    if record.get("skipped"):
        return "SKIPPED"
    status = record.get("http_status")
    if status is None:
        return "UNREACHABLE"
    if 200 <= status < 300:
        return "OK"
    if status in (401, 403):
        return "AUTH REQUIRED"
    if status == 429:
        return "RATE LIMITED"
    if status == 404:
        return "NOT FOUND"
    return f"HTTP {status}"


def render_markdown(results: list[dict], generated: str) -> str:
    lines = [
        "# OMNISHIELD X — live source probe",
        "",
        "Generated by `tools/probe_intel_endpoints.py` and committed by the",
        "`Intel live source verification` workflow. Every row is one real HTTP request from a",
        "clean GitHub Actions runner: no account, no API key, no payment.",
        "",
        f"Probe timestamp (UTC): `{generated}`",
        "",
        "**How to read this.** `OK` means the endpoint answered a keyless request at that moment.",
        "It is not a promise of future availability, and it is not proof that a device on a",
        "carrier network will get the same answer. `AUTH REQUIRED` means the provider needs a",
        "free account key — those connectors stay optional and are never mandatory.",
        "",
        "| Connector | Module | Method | Verdict | HTTP | Sample | URL |",
        "|---|---|---|---|---|---|---|",
    ]
    for record in results:
        sample = (record.get("sample") or record.get("error") or record.get("skip_reason") or "")
        sample = sample.replace("|", "\\|")[:70]
        status = record.get("http_status")
        lines.append(
            f"| `{record['id']}` | {record['module']} | {record['method']} | "
            f"**{verdict(record)}** | {status if status is not None else '—'} | {sample} | "
            f"<{record['url']}> |"
        )
    lines += ["", "## Summary", ""]
    tally: dict[str, int] = {}
    for record in results:
        tally[verdict(record)] = tally.get(verdict(record), 0) + 1
    for key in sorted(tally):
        lines.append(f"- {key}: {tally[key]}")
    lines += [
        "",
        "## What this does not verify",
        "",
        "- Payment terms, redistribution limits or licence changes made after the probe.",
        "- Android API connectors (PackageManager, BatteryManager, ConnectivityManager, …): these",
        "  are platform calls, not URLs, so they are verified on a device instead.",
        "- Response schema stability. Parsers are unit-tested against recorded fixtures; a provider",
        "  changing its schema shows up as a parser error in the connector health table, not as",
        "  silent success.",
        "",
    ]
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", default=str(DEFAULT_JSON))
    parser.add_argument("--md", default=str(DEFAULT_MD))
    parser.add_argument("--timeout", type=int, default=20)
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--only", default="")
    args = parser.parse_args(argv)

    candidates = CANDIDATES
    if args.only:
        wanted = {part.strip() for part in args.only.split(",") if part.strip()}
        candidates = [item for item in CANDIDATES if item["id"] in wanted]
        if not candidates:
            print(f"no candidate matched --only {args.only}", file=sys.stderr)
            return 2

    generated = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    results: list[dict] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = {pool.submit(probe, item, args.timeout): item for item in candidates}
        for future in concurrent.futures.as_completed(futures):
            results.append(future.result())

    order = {item["id"]: index for index, item in enumerate(CANDIDATES)}
    results.sort(key=lambda record: order.get(record["id"], 999))

    payload = {
        "generated_at_utc": generated,
        "tool": "tools/probe_intel_endpoints.py",
        "note": (
            "Measured from a GitHub Actions runner without credentials. Reachability is a "
            "point-in-time observation, not a guarantee."
        ),
        "results": results,
    }
    json_path = Path(args.json)
    md_path = Path(args.md)
    json_path.parent.mkdir(parents=True, exist_ok=True)
    md_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    md_path.write_text(render_markdown(results, generated), encoding="utf-8")

    ok = sum(1 for r in results if verdict(r) == "OK")
    auth = sum(1 for r in results if verdict(r) == "AUTH REQUIRED")
    print(f"probed={len(results)} reachable={ok} auth_required={auth}")
    print(f"wrote {json_path} and {md_path}")
    for record in results:
        print(f"  {verdict(record):14s} {record['id']:34s} {record.get('http_status')}")
    # The probe itself must not fail a build when a provider is down: its job is to record
    # reality. Only an empty result set is a bug.
    return 0 if results else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
