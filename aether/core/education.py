"""Cybersecurity education: lessons, graded quizzes and permission-gated labs.

Labs are defensive and local only. They are locked until the user types an
explicit ownership/permission confirmation. No exploit code, payloads, target
lists or offensive automation is provided anywhere in this module.
"""
from __future__ import annotations

from .util import sanitize_text

LAB_PERMISSION_PHRASE = "I OWN OR HAVE EXPLICIT PERMISSION TO TEST THESE SYSTEMS"

LESSONS: list[dict] = [
    {
        "id": "basics", "title": "Cybersecurity foundations", "module": "AI Security Education",
        "minutes": 8, "keywords": ("confidentiality", "integrity", "availability", "threat model"),
        "levels": {
            "beginner": "Security is about keeping your accounts, devices and data safe. Three ideas: "
                        "confidentiality (only you can read it), integrity (nobody changed it) and "
                        "availability (it works when you need it). Most attacks target passwords and trust.",
            "intermediate": "Start from a threat model: what do you protect, from whom, and what happens "
                            "if it fails. Layer controls: strong unique passwords + MFA, updates, backups, "
                            "least privilege and DNS filtering. Each layer reduces a different risk.",
            "professional": "Map controls to a framework (NIST CSF / CIS Controls), define asset inventory, "
                            "trust boundaries and residual risk. Measure detection precision/recall and "
                            "false-positive rate instead of assuming a control works.",
        },
        "quiz": [
            {"question": "Which control best limits damage from a leaked password?",
             "options": ["Using a longer Wi-Fi name", "Multi-factor authentication", "Dark mode"],
             "correct": 1, "explanation": "MFA requires a second factor, so a password alone is not enough."},
            {"question": "What does 'least privilege' mean?",
             "options": ["Give every app admin rights", "Give only the access needed for the task",
                         "Never install updates"],
             "correct": 1, "explanation": "Least privilege limits what a compromised account or app can reach."},
        ],
    },
    {
        "id": "network", "title": "Network security fundamentals", "module": "AI Security Education",
        "minutes": 10, "keywords": ("tcp", "tls", "dns", "firewall", "segmentation"),
        "levels": {
            "beginner": "Devices talk over networks. HTTPS adds a padlock so others cannot read or change "
                        "the page. A firewall decides which connections are allowed.",
            "intermediate": "DNS resolves names, TCP/UDP carry traffic, TLS protects it in transit. "
                            "A DNS filter sees names but not encrypted content; a firewall sees connections "
                            "but not DNS-over-HTTPS payloads.",
            "professional": "Segment networks, restrict egress, log DNS and flows, and correlate anomalies "
                            "such as regular beaconing intervals or high-entropy subdomain floods.",
        },
        "quiz": [
            {"question": "What can a DNS-only filter see?",
             "options": ["The full contents of HTTPS pages", "Domain names requested by clients",
                         "Passwords typed into forms"],
             "correct": 1, "explanation": "DNS filtering only observes name resolution, not encrypted content."},
            {"question": "Why can first-party video ads be hard to block with DNS?",
             "options": ["Ads use a special port", "They come from the same domains as the video",
                         "DNS is encrypted"],
             "correct": 1, "explanation": "Blocking the shared domain would break playback, so DNS cannot separate them."},
        ],
    },
    {
        "id": "dns_security", "title": "DNS security", "module": "AI Security Education",
        "minutes": 9, "keywords": ("doh", "dot", "dnssec", "hijack", "ttl"),
        "levels": {
            "beginner": "DNS is the phone book of the internet. If someone changes the phone book, "
                        "you can be sent to a fake site. Encrypted DNS (DoH/DoT) hides lookups from your network.",
            "intermediate": "DoH (RFC 8484) and DoT (RFC 7858) protect the transport; DNSSEC validates "
                            "authenticity of records where enabled. If an encrypted resolver fails, a "
                            "safe client returns SERVFAIL rather than silently downgrading to plaintext.",
            "professional": "Check RCODE, TTL, answer consistency across resolvers, and bogon answers "
                            "as hijack indicators. Sign and verify intelligence feeds; fail open on "
                            "engine errors to preserve connectivity.",
        },
        "quiz": [
            {"question": "What should a fail-closed encrypted DNS client do when DoH fails?",
             "options": ["Silently use plaintext DNS", "Return SERVFAIL for that request",
                         "Disable all protection"],
             "correct": 1, "explanation": "Silent downgrade leaks queries; SERVFAIL keeps the chosen protocol."},
            {"question": "Which answer suggests a DNS hijack?",
             "options": ["A public IP with normal TTL", "A private/loopback IP for a public website",
                         "A CNAME to a CDN"],
             "correct": 1, "explanation": "Private or reserved answers for public names are a classic hijack/sinkhole signal."},
        ],
    },
    {
        "id": "phishing", "title": "Recognising phishing", "module": "AI Security Education",
        "minutes": 8, "keywords": ("lookalike", "homograph", "userinfo", "redirect"),
        "levels": {
            "beginner": "A phishing page copies a real login screen. Always check the exact address "
                        "before typing a password; when in doubt, open the official app yourself.",
            "intermediate": "Look for '@' userinfo, brand names in subdomains, punycode lookalikes, "
                            "shorteners, suspicious TLDs and long redirect chains. Individually these "
                            "are weak signals; together they justify a warning.",
            "professional": "Distinguish confirmed intelligence from heuristics; require source, "
                            "timestamp, confidence and evidence for each finding, and measure "
                            "false positives from verified feedback.",
        },
        "quiz": [
            {"question": "In https://paypal.com@evil.example/login, which site receives your data?",
             "options": ["paypal.com", "evil.example", "Neither"],
             "correct": 1, "explanation": "Text before '@' is userinfo; the real host is after it."},
            {"question": "Why flag rather than auto-block a lookalike domain by default?",
             "options": ["Lookalikes are always harmless", "Heuristics can produce false positives",
                         "Blocking is impossible"],
             "correct": 1, "explanation": "Only confirmed intelligence is auto-blocked by default; suspicious hits are flagged."},
        ],
    },
    {
        "id": "wifi", "title": "Securing home Wi-Fi", "module": "AI Security Education",
        "minutes": 7, "keywords": ("wpa3", "guest network", "wps", "firmware"),
        "levels": {
            "beginner": "Change the router admin password, use WPA2/WPA3, and keep firmware updated.",
            "intermediate": "Disable WPS and remote administration, create a guest network for visitors "
                            "and IoT devices, and use a trusted resolver.",
            "professional": "Segment VLANs, review UPnP exposure, watch for rogue DHCP/DNS servers and "
                            "centralise DNS logging at the network edge.",
        },
        "quiz": [
            {"question": "Which setting should usually be disabled for security?",
             "options": ["WPA3", "WPS", "Guest network"],
             "correct": 1, "explanation": "WPS PIN is brute-forceable; WPA3 and guest networks are protective."},
        ],
    },
    {
        "id": "device", "title": "Android, Windows, macOS and Linux hardening",
        "module": "AI Security Education", "minutes": 10,
        "keywords": ("permissions", "updates", "encryption", "play protect", "vpn"),
        "levels": {
            "beginner": "Install updates, use official app stores, check permissions and use a password manager.",
            "intermediate": "Enable built-in security scanning, full-disk encryption and encrypted DNS; "
                            "use a standard user account on desktops.",
            "professional": "Understand platform limits: one always-on VPN per Android device, scoped "
                            "storage, sandboxing, and no app-level ad blocking inside other apps without root.",
        },
        "quiz": [
            {"question": "How many always-on VPNs can Android typically run at once?",
             "options": ["Unlimited", "One", "Exactly two"],
             "correct": 1, "explanation": "Android allows one active VPN; a second app's VPN cannot coexist."},
        ],
    },
    {
        "id": "privacy", "title": "Privacy and trackers", "module": "AI Security Education",
        "minutes": 8, "keywords": ("tracker", "telemetry", "third party", "fingerprinting"),
        "levels": {
            "beginner": "Trackers are requests that tell companies where you go online. Blocking their "
                        "addresses helps, but a site can still ask you for data directly.",
            "intermediate": "Choose a privacy level: minimal, balanced or strict. Blocking at DNS level "
                            "cannot see first-party or server-side tracking.",
            "professional": "Combine DNS blocklists, browser-level controls, cookie partitioning and "
                            "minimal data collection; never claim universal ad blocking.",
        },
        "quiz": [
            {"question": "What can DNS-level tracker blocking NOT stop?",
             "options": ["Requests to known analytics domains", "Server-side tracking by the site itself",
                         "Ad domains in the blocklist"],
             "correct": 1, "explanation": "Server-side tracking happens after the connection, outside DNS."},
        ],
    },
    {
        "id": "incident", "title": "Incident response basics", "module": "AI Security Education",
        "minutes": 9, "keywords": ("containment", "evidence", "mfa", "recovery"),
        "levels": {
            "beginner": "If an account is hacked: change the password from a clean device, sign out "
                        "everywhere, and turn on two-step verification.",
            "intermediate": "Check recovery methods, active sessions, forwarding rules and OAuth grants; "
                            "document times and messages.",
            "professional": "Preserve evidence, rotate tokens, scope lateral impact, remediate root cause, "
                            "then review and improve detection. Tamper-evident audit logs help here.",
        },
        "quiz": [
            {"question": "What should you do FIRST after noticing a compromised account?",
             "options": ["Delete all apps", "Use a clean device and change the password / sign out sessions",
                         "Post about it publicly"],
             "correct": 1, "explanation": "Containment first: revoke the attacker's access from a trusted device."},
        ],
    },
    {
        "id": "securedev", "title": "Secure development & vulnerability management",
        "module": "AI Security Education", "minutes": 10,
        "keywords": ("owasp", "sbom", "dependency", "signing", "cve"),
        "levels": {
            "beginner": "Never put passwords or keys in code, validate user input, and keep libraries updated.",
            "intermediate": "Use parameterised queries, output encoding, CSRF protection, dependency and "
                            "secret scanning in CI.",
            "professional": "Maintain an SBOM, prioritise known-exploited vulnerabilities, sign releases "
                            "and verify signatures before install; gate rule changes behind measured validation.",
        },
        "quiz": [
            {"question": "What is the safest way to handle a dependency with a known exploited vulnerability?",
             "options": ["Ignore it until a major release", "Patch or upgrade promptly and verify the fix",
                         "Disable all logging"],
             "correct": 1, "explanation": "Known-exploited issues should be prioritised and verified after patching."},
        ],
    },
    {
        "id": "ethical", "title": "Ethical hacking & defensive labs", "module": "AI Security Education",
        "minutes": 8, "keywords": ("permission", "scope", "lab", "ctf", "rules of engagement"),
        "levels": {
            "beginner": "Only test systems you own or have written permission to test. Use a private lab.",
            "intermediate": "Write down scope and rules of engagement, practise on deliberately "
                            "vulnerable local targets, and focus on detection and remediation.",
            "professional": "Follow a scoped methodology, handle evidence carefully, report findings with "
                            "reproduction steps and retest after fixes. AETHER never provides offensive tooling.",
        },
        "quiz": [
            {"question": "Before testing a system, what is mandatory?",
             "options": ["A fast laptop", "Ownership or explicit written permission and scope",
                         "A public IP address"],
             "correct": 1, "explanation": "Authorisation and scope define legal, safe testing."},
        ],
    },
]

LABS: list[dict] = [
    {
        "id": "dns_lab", "title": "Defensive DNS observation lab", "minutes": 15,
        "objective": "Observe real DNS answers and compare resolvers on domains you own.",
        "requires_permission": True,
        "steps": [
            "Use the Investigate tab → 'Real DNS lookup' on a domain you own or a public documentation domain.",
            "Record RCODE, latency, TTL and answers in your notebook.",
            "Repeat with a different resolver preset and compare answers for consistency.",
            "Explain any NXDOMAIN/SERVFAIL result and how a fail-safe client behaves.",
            "Write a short report: what you observed, what you cannot conclude, and what you would monitor.",
        ],
    },
    {
        "id": "corpus_lab", "title": "Detection accuracy lab", "minutes": 20,
        "objective": "Measure precision, recall and false positives on a labeled corpus.",
        "requires_permission": True,
        "steps": [
            "Open the Models tab and read the active ruleset's measured metrics.",
            "Submit a deliberately bad candidate rule (for example an overly broad weight) and observe the validation gate reject it.",
            "Submit a conservative candidate and compare recall and false-positive rate.",
            "Explain why held-out real indicators matter more than indicators already present in the feed.",
            "Never activate a ruleset that fails the compatibility or false-positive gate.",
        ],
    },
    {
        "id": "audit_lab", "title": "Audit trail & rollback lab", "minutes": 12,
        "objective": "Verify tamper-evident logging and practice a safe rollback.",
        "requires_permission": True,
        "steps": [
            "Change a protection setting, then inspect the audit log entry for that change.",
            "Run 'Verify audit chain' and note the head hash.",
            "Use an approved, expiring temporary allowlist entry and confirm the audit record includes approver, reason and expiry.",
            "Roll back the policy snapshot and confirm the previous state is restored as a new version.",
            "Document why rollback is safer than deleting an audit entry.",
        ],
    },
]


class EducationCenter:
    def __init__(self, store=None):
        self.store = store

    def lessons(self) -> list[dict]:
        return [{k: v for k, v in lesson.items() if k not in ("quiz", "levels")}
                | {"question_count": len(lesson["quiz"])} for lesson in LESSONS]

    def lesson(self, lesson_id: str, level: str = "beginner") -> dict | None:
        level = level if level in ("beginner", "intermediate", "professional") else "beginner"
        for lesson in LESSONS:
            if lesson["id"] == lesson_id:
                return {**{k: v for k, v in lesson.items() if k != "quiz"},
                        "content": lesson["levels"][level], "level": level,
                        "quiz": [{kk: vv for kk, vv in q.items() if kk not in ("correct", "explanation")}
                                 for q in lesson["quiz"]]}
        return None

    def grade_quiz(self, lesson_id: str, answers: list) -> dict:
        lesson = next((l for l in LESSONS if l["id"] == lesson_id), None)
        if lesson is None:
            raise ValueError("unknown lesson id")
        if not isinstance(answers, list):
            raise ValueError("answers must be a list")
        results = []
        for idx, question in enumerate(lesson["quiz"]):
            given = answers[idx] if idx < len(answers) else None
            correct = question["correct"]
            results.append({"question": idx, "given": given, "correct": correct,
                            "is_correct": given == correct,
                            "explanation": question["explanation"]})
        score = sum(1 for r in results if r["is_correct"])
        return {"lesson_id": lesson_id, "score": score, "total": len(lesson["quiz"]),
                "passed": score == len(lesson["quiz"]),
                "percent": round(100 * score / len(lesson["quiz"]), 1),
                "results": results,
                "note": "Graded from fixed answer keys; no AI scoring or guessing."}

    def labs(self) -> list[dict]:
        return [{**lab, "permission_phrase_required": LAB_PERMISSION_PHRASE} for lab in LABS]

    def lab(self, lab_id: str, permission_phrase: str = "", actor: str = "user") -> dict:
        lab = next((l for l in LABS if l["id"] == lab_id), None)
        if lab is None:
            raise ValueError("unknown lab id")
        if sanitize_text(permission_phrase).strip().upper() != LAB_PERMISSION_PHRASE:
            return {"ok": False, "lab_id": lab_id, "unlocked": False,
                    "reason": "Explicit ownership/permission confirmation is required before lab steps are shown.",
                    "permission_phrase_required": LAB_PERMISSION_PHRASE,
                    "safety": "Only test systems you own or have explicit written permission to test."}
        if self.store:
            self.store.add_audit(actor, "education.lab_unlocked", "read", {"lab_id": lab_id})
        return {"ok": True, "lab_id": lab_id, "unlocked": True, "lab": lab,
                "safety": "Defensive, local-only steps. No offensive tooling is provided."}
