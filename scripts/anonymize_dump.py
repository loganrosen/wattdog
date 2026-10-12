#!/usr/bin/env python3
"""Scrub a dumpsys capture so it can be committed as a test fixture.

    adb shell cmd package list packages -U --user 0 > packages.txt
    scripts/anonymize_dump.py --packages packages.txt < raw.txt > fixture.txt

Third-party package names reveal which apps someone has installed, so every
dotted identifier under a non-platform vendor prefix (com.vendor.app,
com.vendor.app.SomeService) is replaced with a stable placeholder. Platform
packages (android, com.android.*, com.google.*) are kept because the parser and
anomaly rules care about them. The mapping depends only on the package list, so
running the script over several dumps from one device gives consistent names.

Also scrubs email addresses, MAC addresses, IPv4 addresses, and long digit runs
that could be IMSI/ICCID/IMEI values. Review the output before committing; this
is a best-effort filter, not a guarantee.
"""

import argparse
import hashlib
import re
import sys

PLATFORM_PREFIXES = (
    "com.android.",
    "com.google.",
    "androidx.",
    "org.chromium.",
    "kotlin.",
    "java.",
    "dalvik.",
    "vendor.",
)
SECOND_LEVEL = {"com", "co", "org", "net", "gov", "ac", "edu"}
DOMAIN_ROOTS = {
    "com", "org", "net", "io", "co", "de", "me", "tv", "uk", "us", "ca", "au", "fr", "nl", "se",
    "jp", "kr", "in", "br", "ch", "it", "es", "pl", "ru", "app", "dev", "ai", "edu", "gov",
}
PLATFORM_EXACT = {"com.google", "com.android", "org.chromium"}

TOKEN = re.compile(r"[A-Za-z_][\w$]*(?:\.[\w$]+)+")
EMAIL = re.compile(r"\b[\w.%+-]+@[\w-]+(?:\.[\w-]+)*\.(?:com|net|org|edu|io|me|us|uk|de)\b(?![.\w])")
MAC = re.compile(r"\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b")
IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
LONG_DIGITS = re.compile(r"(?<![\d.])\d{14,19}(?![\d.])")
LONG_MAX = "9223372036854775807"
PACKAGE_LINE = re.compile(r"package:(\S+)")
WORKER = re.compile(r"#([A-Za-z_][\w$]*)#")
WORKER_WITH_PKG = re.compile(r"#([A-Za-z_][\w$]*)#[^@\s]*@androidx\.work\.systemjobscheduler@([\w.]+)")


def is_platform(pkg: str) -> bool:
    return pkg == "android" or pkg.startswith("android.") or pkg.startswith(PLATFORM_PREFIXES)


def vendor_prefix(pkg: str) -> str:
    parts = pkg.split(".")
    n = 3 if len(parts) > 2 and parts[1] in SECOND_LEVEL and len(parts[0]) == 2 else 2
    return ".".join(parts[:n])


def short_hash(s: str) -> str:
    return hashlib.sha1(s.encode()).hexdigest()[:6]


def make_token_scrubber(packages: set[str]):
    third_party = {p for p in packages if not is_platform(p)}
    prefixes = sorted({vendor_prefix(p) for p in third_party})
    vendors = {p: f"com.example.v{i + 1}" for i, p in enumerate(prefixes)}
    # Longest first so au.com.vendor wins over a shorter overlapping prefix.
    ordered = sorted(vendors, key=len, reverse=True)
    # Apps often use class namespaces that differ from their package (com.dd.app ships com.app.*),
    # so anything that looks reverse-domain gets scrubbed, not just known package prefixes.
    roots = DOMAIN_ROOTS | {p.split(".")[0] for p in third_party}

    def replacement(name: str) -> str:
        for prefix in ordered:
            if name == prefix or name.startswith(prefix + "."):
                base = vendors[prefix]
                return f"{base}.app{short_hash(name)[:3]}" if name in third_party else f"{base}.c{short_hash(name)}"
        return f"com.example.x{short_hash(vendor_prefix(name))}.c{short_hash(name)}"

    def scrub(m: re.Match) -> str:
        segs = m.group(0).split(".")
        for i, seg in enumerate(segs[:-1]):
            if seg in roots:
                name = ".".join(segs[i:])
                if is_platform(name) or name in PLATFORM_EXACT:
                    return m.group(0)
                return ".".join(segs[:i] + [replacement(name)])
        return m.group(0)

    return scrub


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--packages", required=True, help="output of `cmd package list packages -U`")
    args = ap.parse_args()

    with open(args.packages) as f:
        packages = {m.group(1) for line in f if (m := PACKAGE_LINE.match(line))}
    scrub_token = make_token_scrubber(packages)

    lines = sys.stdin.readlines()
    # WorkManager tags carry bare worker class names (#SomeAppWorker#) that often name the app.
    # dumpsys truncates many tags, so keep only names seen at least once next to a platform package.
    platform_workers = set()
    for line in lines:
        for m in WORKER_WITH_PKG.finditer(line):
            if is_platform(m.group(2)):
                platform_workers.add(m.group(1))

    def scrub_worker(m: re.Match) -> str:
        name = m.group(1)
        return m.group(0) if name in platform_workers else f"#Worker{short_hash(name)}#"

    for line in lines:
        line = WORKER.sub(scrub_worker, line)
        line = EMAIL.sub("user@example.com", line)
        line = MAC.sub("02:00:00:00:00:00", line)
        line = IPV4.sub("192.0.2.1", line)
        line = LONG_DIGITS.sub(lambda m: m.group(0) if m.group(0) == LONG_MAX else "0" * len(m.group(0)), line)
        line = TOKEN.sub(scrub_token, line)
        sys.stdout.write(line)


if __name__ == "__main__":
    main()
