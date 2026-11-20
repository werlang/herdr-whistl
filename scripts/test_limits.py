#!/usr/bin/env python3
"""The limits command the app ships: the four ways Claude can fail, and the codes the app reads (0.7.9).

Run with:
  python3 -m unittest scripts/test_limits.py
"""

import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from unittest import mock

# The script lives in the app's assets, so a .pyc there would be packaged into the APK: import it without writing one.
sys.dont_write_bytecode = True

HERE = os.path.dirname(os.path.abspath(__file__))
LIMITS_PATH = os.path.join(HERE, "..", "app", "src", "main", "assets", "limits.py")
SETUP_TOKEN = "claude setup-token"
TOKEN_FILE = "~/.config/agent-limits/claude-token"


def load_limits():
    """The script as a module, loaded by path: it is an app asset, not an installed package."""
    spec = importlib.util.spec_from_file_location("whistl_limits", LIMITS_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


limits = load_limits()


def http_error(code):
    """The error urllib raises for one HTTP status."""
    return urllib.error.HTTPError(limits.CLAUDE_URL, code, f"HTTP {code}", None, io.BytesIO(b""))


class ClaudeFailuresTest(unittest.TestCase):
    """The four cases of claude(), each with the code the app turns into its own words."""

    def fail_claude(self, tokens, statuses=()):
        """Run claude() with a fixed token list and one HTTP status per token; return the raised error."""
        calls = []
        raised = []

        def answer(url, headers, timeout=20):
            calls.append(headers["Authorization"])
            error = http_error(statuses[len(calls) - 1])
            raised.append(error)
            raise error

        try:
            with mock.patch.object(limits, "claude_tokens", return_value=tokens), \
                    mock.patch.object(limits, "http_json", side_effect=answer):
                with self.assertRaises(Exception) as caught:
                    limits.claude()
        finally:
            for error in raised:
                error.close()
        return caught.exception

    def assertPointsToSetupToken(self, error):
        """Every remediable failure names the command and the file the user has to write."""
        message = str(error)
        self.assertIn(SETUP_TOKEN, message)
        self.assertIn(TOKEN_FILE, message)
        self.assertNotIn("not signed in", message.lower())

    def test_an_account_without_a_token_asks_for_setup(self):
        """Case 1: the machine has a login, but no token is readable here."""
        with mock.patch.object(limits, "claude_tokens", return_value=[]), \
                mock.patch.object(limits, "claude_has_account", return_value=True):
            with self.assertRaises(limits.ProviderError) as caught:
                limits.claude()

        self.assertEqual("claude_token_setup_required", caught.exception.code)
        self.assertPointsToSetupToken(caught.exception)

    def test_no_account_and_no_token_says_claude_is_not_configured(self):
        """Case 2: neither a login nor a token, so the machine was never set up for Claude limits."""
        with mock.patch.object(limits, "claude_tokens", return_value=[]), \
                mock.patch.object(limits, "claude_has_account", return_value=False):
            with self.assertRaises(limits.ProviderError) as caught:
                limits.claude()

        self.assertEqual("claude_not_configured", caught.exception.code)
        self.assertPointsToSetupToken(caught.exception)

    def test_a_refused_token_is_expired(self):
        """Case 3: a token was found, but the endpoint refused it as unauthorised."""
        error = self.fail_claude(["token"], [401])

        self.assertIsInstance(error, limits.ProviderError)
        self.assertEqual("claude_token_expired", error.code)
        self.assertPointsToSetupToken(error)

    def test_every_token_refused_as_unauthorised_is_expired(self):
        """Case 3 with more than one login: 401 and 403 are both an expired token."""
        error = self.fail_claude(["first", "second"], [401, 403])

        self.assertIsInstance(error, limits.ProviderError)
        self.assertEqual("claude_token_expired", error.code)
        self.assertPointsToSetupToken(error)

    def test_a_rate_limit_keeps_the_http_error(self):
        """Case 4: 429 says nothing about the token, so it gets no auth code."""
        error = self.fail_claude(["token"], [429])

        self.assertNotIsInstance(error, limits.ProviderError)
        self.assertIn("429", str(error))
        self.assertNotIn("not signed in", str(error).lower())

    def test_a_non_auth_failure_after_an_auth_one_keeps_the_http_error(self):
        """Case 4: one attempt refused as unauthorised and one 500 is still not an expired token."""
        error = self.fail_claude(["first", "second"], [401, 500])

        self.assertNotIsInstance(error, limits.ProviderError)
        self.assertIn("500", str(error))
        self.assertNotIn("not signed in", str(error).lower())


class ClaudeAccountTest(unittest.TestCase):
    """A login in any home counts, even when this process cannot read its token."""

    def test_the_cli_session_is_enough(self):
        with mock.patch.object(limits, "claude_signed_in", return_value=(True, "claude.ai")), \
                mock.patch.object(limits, "claude_account_files", return_value=[]):
            self.assertTrue(limits.claude_has_account())

    def test_an_oauth_account_in_claude_json_is_enough(self):
        with tempfile.TemporaryDirectory() as home:
            path = os.path.join(home, ".claude.json")
            with open(path, "w", encoding="utf-8") as handle:
                json.dump({"oauthAccount": {"emailAddress": "dev@example.com"}}, handle)
            with mock.patch.object(limits, "claude_signed_in", return_value=(False, None)), \
                    mock.patch.object(limits, "claude_account_files", return_value=[path]):
                self.assertTrue(limits.claude_has_account())

    def test_an_empty_oauth_account_is_no_account(self):
        with tempfile.TemporaryDirectory() as home:
            path = os.path.join(home, ".claude.json")
            with open(path, "w", encoding="utf-8") as handle:
                json.dump({"oauthAccount": None}, handle)
            with mock.patch.object(limits, "claude_signed_in", return_value=(False, None)), \
                    mock.patch.object(limits, "claude_account_files", return_value=[path]):
                self.assertFalse(limits.claude_has_account())


class CollectDocumentTest(unittest.TestCase):
    """The document: a coded failure keeps its code, and a cached answer replaces the failure."""

    def collect(self, provider, cached=None):
        with mock.patch.dict(limits.PROVIDERS, {"probe": provider}), \
                mock.patch.object(limits, "read_cache", return_value=cached):
            return limits.collect("probe")

    def test_a_coded_failure_carries_the_code(self):
        def broken():
            raise limits.ProviderError("run `claude setup-token`", "claude_token_setup_required")

        document = self.collect(broken)

        self.assertFalse(document["ok"])
        self.assertEqual("claude_token_setup_required", document["error_code"])
        self.assertEqual("run `claude setup-token`", document["error"])
        self.assertNotIn("not signed in", document["error"].lower())

    def test_an_uncoded_failure_has_no_code(self):
        def broken():
            raise RuntimeError("HTTP 429")

        document = self.collect(broken)

        self.assertFalse(document["ok"])
        self.assertNotIn("error_code", document)

    def test_a_cached_answer_hides_the_failure_and_its_code(self):
        def broken():
            raise limits.ProviderError("the saved Claude token expired", "claude_token_expired")

        document = self.collect(broken, cached={"windows": {}, "fetched_at": 1.0})

        self.assertTrue(document["ok"])
        self.assertTrue(document["cached"])
        self.assertNotIn("error_code", document)


if __name__ == "__main__":
    unittest.main()
