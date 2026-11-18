#!/usr/bin/env python3
"""Print the subscription limits of the coding agents on this machine as one JSON object.

Every provider is asked the same way the CLI itself asks, and a provider that
cannot answer is reported as unavailable instead of failing the whole document:
a machine without Claude still shows Codex and OpenCode Go.

Sources, in the order the CLI keeps them:
  claude    the OAuth token in CLAUDE_CREDENTIALS_JSON / ~/.claude/.credentials.json,
            or the macOS Keychain, then https://api.anthropic.com/api/oauth/usage
  codex     $CODEX_HOME/auth.json (or ~/.codex/auth.json), then the wham/usage
            endpoint. The token is never refreshed: the CLI owns that rotation.
  opencode  the local daemon: `opencode api GET /api/experimental/usage/limits`
  copilot   the Copilot SDK next to the installed `copilot` binary, when present

Usage:
  limits.py                     every provider, as one JSON object
  limits.py --only claude,codex only those providers
  limits.py --pretty            indented, for a human

The output is a document, never a claim: a provider that failed says so, with
the reason, so a reader can tell "no limits left" from "could not ask".
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

CLAUDE_URL = "https://api.anthropic.com/api/oauth/usage"
CLAUDE_KEYCHAIN_SERVICE = "Claude Code-credentials"
CODEX_URL = "https://chatgpt.com/backend-api/wham/usage"
CACHE_DIR = os.path.join(os.path.expanduser("~"), ".cache", "agent-limits")
CACHE_MAX_AGE = 7 * 24 * 3600


def cache_path(name):
    """Where the last good answer of a provider is kept."""
    return os.path.join(CACHE_DIR, f"{name}.json")


def read_cache(name):
    """The last good answer, if it is recent enough; `None` otherwise."""
    try:
        with open(cache_path(name), encoding="utf-8") as handle:
            cached = json.load(handle)
        if time.time() - float(cached.get("fetched_at", 0)) <= CACHE_MAX_AGE:
            return cached
    except Exception:  # noqa: BLE001 - a missing cache is the normal case
        pass
    return None


def write_cache(name, document):
    """Keep a good answer, failing silently: a cache is a convenience, not a promise."""
    try:
        os.makedirs(CACHE_DIR, mode=0o700, exist_ok=True)
        with open(cache_path(name), "w", encoding="utf-8") as handle:
            json.dump(document, handle)
        os.chmod(cache_path(name), 0o600)
    except Exception:  # noqa: BLE001
        pass


def http_json(url, headers, timeout=20):
    """GET a URL and decode its JSON body."""
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def claude_token():
    """Claude Code's OAuth token: the environment, the credentials file, or the macOS Keychain."""
    raw = os.environ.get("CLAUDE_CREDENTIALS_JSON")
    if raw:
        return json.loads(raw).get("claudeAiOauth", {}).get("accessToken")
    path = os.path.join(os.path.expanduser("~"), ".claude", ".credentials.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            token = json.load(handle).get("claudeAiOauth", {}).get("accessToken")
        if token:
            return token
    if sys.platform == "darwin":
        found = subprocess.run(
            ["security", "find-generic-password", "-s", CLAUDE_KEYCHAIN_SERVICE, "-w"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        if found.returncode == 0 and found.stdout.strip():
            return json.loads(found.stdout).get("claudeAiOauth", {}).get("accessToken")
    return None


def claude():
    """The 5-hour and 7-day windows Claude Code spends."""
    token = claude_token()
    if not token:
        raise RuntimeError("not signed in: no Claude credentials on this machine")
    body = http_json(
        CLAUDE_URL,
        {
            "Authorization": f"Bearer {token}",
            "anthropic-beta": "oauth-2025-04-20",
            "User-Agent": "claude-code/2.1.317",
            "Accept": "application/json",
        },
    )
    windows = {}
    for name in ("five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet"):
        window = body.get(name)
        if isinstance(window, dict):
            windows[name] = {
                "utilization": window.get("utilization"),
                "resets_at": window.get("resets_at"),
            }
    return windows


def jwt_claims(token):
    """A JWT payload, decoded without verification: the meter only reads the expiry and the account id."""
    import base64

    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload))
    except Exception:  # noqa: BLE001
        return {}


def codex():
    """The 5-hour and 7-day windows the Codex CLI spends, when auth.json holds a live session."""
    root = os.environ.get("CODEX_HOME") or os.path.join(os.path.expanduser("~"), ".codex")
    path = os.path.join(root, "auth.json")
    if not os.path.exists(path):
        raise RuntimeError("not signed in: no $CODEX_HOME/auth.json")
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    if str(data.get("auth_mode", "")).lower() not in ("", "chatgpt"):
        raise RuntimeError("not a ChatGPT session")
    tokens = data.get("tokens") or {}
    token = tokens.get("access_token")
    if not token:
        raise RuntimeError("auth.json has no access token")
    exp = jwt_claims(token).get("exp")
    if isinstance(exp, (int, float)) and exp <= time.time():
        # The CLI owns the refresh; a meter that raced it would rotate the token behind its back.
        raise RuntimeError("session expired; run codex to refresh it")
    account = tokens.get("account_id") or jwt_claims(token).get("https://api.openai.com/auth", {}).get("chatgpt_account_id")
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    if account:
        headers["chatgpt-account-id"] = account
    body = http_json(CODEX_URL, headers)
    rate = body.get("rate_limit") or body.get("rate_limits") or {}
    if not isinstance(rate, dict):
        rate = {}
    # The endpoint names its windows by slot: primary is the 5-hour one, secondary the weekly one.
    windows = {}
    for slot, name in (("primary_window", "five_hour"), ("secondary_window", "seven_day")):
        parsed = codex_window(rate.get(slot))
        if parsed:
            windows[name] = parsed
    for name in ("five_hour", "seven_day"):
        parsed = codex_window(rate.get(name))
        if parsed and name not in windows:
            windows[name] = parsed
    if body.get("plan_type"):
        windows["plan"] = body["plan_type"]
    return windows


def codex_window(value):
    """One Codex window as utilization and reset, from any of the shapes the endpoint has used."""
    if not isinstance(value, dict):
        return None
    used = value.get("used_percent")
    if used is None and value.get("percent_left") is not None:
        used = 100 - float(value["percent_left"])
    if used is None:
        return None
    reset = value.get("reset_at") or value.get("resets_at") or value.get("reset_time_ms")
    if reset is None and value.get("reset_after_seconds") is not None:
        reset = time.time() + float(value["reset_after_seconds"])
    return {"utilization": round(float(used), 1), "resets_at": reset}


def opencode():
    """The Go allowances the local OpenCode daemon reports: rolling, weekly, monthly."""
    found = subprocess.run(
        ["opencode", "api", "GET", "/api/experimental/usage/limits"],
        capture_output=True,
        text=True,
        timeout=30,
    )
    if found.returncode != 0:
        raise RuntimeError(found.stderr.strip()[:200] or "opencode refused the request")
    body = json.loads(found.stdout)
    data = body.get("data") or {}
    go = data.get("go")
    if not go:
        raise RuntimeError("OpenCode Go is not signed in")
    return {
        "rolling": go.get("rolling"),
        "weekly": go.get("weekly"),
        "monthly": go.get("monthly"),
        "workspace": (data.get("workspace") or {}).get("name"),
    }


PROVIDERS = {"claude": claude, "codex": codex, "opencode": opencode}


def collect(name):
    """Ask one provider, falling back to its last good answer, and never raising."""
    started = time.time()
    try:
        value = PROVIDERS[name]()
        document = {"ok": True, "fetched_at": time.time(), "windows": value}
        write_cache(name, document)
        return document
    except urllib.error.HTTPError as error:
        retry_after = error.headers.get("Retry-After") if error.headers else None
        return fallback(name, f"HTTP {error.code}", started, retry_after=retry_after)
    except Exception as error:  # noqa: BLE001 - a provider failure is data, not a crash
        return fallback(name, str(error)[:200], started)


def fallback(name, reason, started, retry_after=None):
    """The last good answer, marked as such, or the reason there is none."""
    cached = read_cache(name)
    result = {"ok": False, "error": reason, "checked_at": time.time(), "ms": int((time.time() - started) * 1000)}
    if retry_after:
        result["retry_after"] = retry_after
    if cached:
        result.update({"ok": True, "cached": True, "warning": reason, "windows": cached.get("windows"),
                       "fetched_at": cached.get("fetched_at")})
    return result


def main(argv):
    """Print the document for the providers asked for."""
    only = [name for name in argv if not name.startswith("-")]
    pretty = "--pretty" in argv
    names = only or list(PROVIDERS)
    for name in names:
        if name not in PROVIDERS:
            print(f"unknown provider: {name}", file=sys.stderr)
            return 2
    document = {
        "generated_at": time.time(),
        "host": os.uname().nodename if hasattr(os, "uname") else os.environ.get("COMPUTERNAME", ""),
        "platform": sys.platform,
        "providers": {name: collect(name) for name in names},
    }
    print(json.dumps(document, indent=2 if pretty else None))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
