#!/usr/bin/env python3
"""Create deployment secrets once, without echoing them or reading the local app .env."""
import argparse
import os
from pathlib import Path
import secrets
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True, help="Public HTTPS origin, e.g. https://lumen.example.com")
    parser.add_argument("--allow-local-http", action="store_true", help="Allow HTTP only on localhost for a local smoke test")
    parser.add_argument("--output", type=Path, help="Default: deploy/.env. An existing file is never overwritten.")
    args = parser.parse_args()
    origin = args.origin.rstrip("/")
    parsed = urlsplit(origin)
    try:
        parsed.port
    except ValueError:
        parser.error("Origin has an invalid port")
    local_http = (args.allow_local_http and parsed.scheme == "http"
                  and parsed.hostname in {"localhost", "127.0.0.1", "::1"})
    if (parsed.scheme != "https" and not local_http) or not parsed.hostname:
        parser.error("Use an HTTPS origin; local HTTP needs --allow-local-http")
    if parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
        parser.error("Origin must contain only scheme, hostname and optional port")
    if any(c.isspace() for c in origin) or any(c in origin for c in "'\"$`\\#"):
        parser.error("Origin contains an invalid character")

    directory = Path(__file__).resolve().parent
    target = (args.output or directory / ".env").resolve()
    content = (directory / ".env.example").read_text(encoding="utf-8")
    content = content.replace("https://lumen.example.com", origin)
    content = content.replace("GENERATE_DATABASE_PASSWORD", secrets.token_hex(32))
    content = content.replace("GENERATE_JWT_SECRET", secrets.token_hex(48))
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        parser.error(f"Existing configuration was preserved: {target}")
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
        output.write(content)
    print(f"Created {target}; secrets were generated and were not printed.")
    print(f"Public origin: {origin}. HTTPS remains disabled until a real certificate is installed.")


if __name__ == "__main__":
    main()
