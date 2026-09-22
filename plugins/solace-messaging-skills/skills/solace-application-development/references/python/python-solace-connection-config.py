"""
Shared connection-config helper for the Python reference samples.

Any generated adaptation of this sample MUST begin with the exact line:
  AI-assisted code. Review before production use.
(This reference sample itself carries no such header by design.)

Copied into a generated project as solace_connection_config.py (a Python module
name needs underscores, not hyphens); every sample imports it with
  from solace_connection_config import SolaceConnectionConfig

Loads connection properties and turns them into the properties dict that
MessagingService.builder().from_properties(...) takes. Source precedence:
  1. a config.json in the working directory (the project root), when present.
     Its keys are Solace Python API property-name strings, the VALUES of the
     constants in solace.messaging.config.solace_properties (for example the
     transport_layer_properties.HOST constant's value is
     "solace.messaging.transport.host"):
       "solace.messaging.transport.host"
       "solace.messaging.service.vpn-name"
       "solace.messaging.authentication.basic.username"
       "solace.messaging.authentication.basic.password"
     EVERY key in the file is passed through to from_properties() unchanged, so
     further service properties (for example
     "solace.messaging.transport.keep-alive-interval") work with no parser
     change. A builder call made after from_properties() overrides the same key:
     the samples set the reconnection strategy that way, so the
     reconnection-attempts and reconnection-attempts-wait-interval keys from the
     file are replaced by each sample's constants. Pass-through limitations: the
     API passes every value to the native session as a string, and the native
     library rejects an invalid value at build() or connect(); an unknown key is
     accepted and silently ignored by the API. config.json holds broker
     credentials, so it MUST be gitignored and never committed.
  2. otherwise the command-line arguments:
     <host:port> <message-vpn> <client-username> [password]

The password is optional in both sources. The API requires the basic-auth password
key at build(), so an absent or blank password is passed as an empty string.

The config.json reader is the standard-library json module, so the samples keep
their single Solace dependency (solace-pubsubplus). config.json must hold a single
flat JSON object.

Grounding: the "load a JSON file, then from_properties()" idiom and the required
host and vpn-name keys are on the Messaging Service page of the Python API
developer guide:
  https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md
"""

# PEP 563: keep annotations unevaluated so the built-in generics below (list[str],
# dict[str, object]) also import on Python 3.7 and 3.8
from __future__ import annotations

import json
import os
import sys
from typing import Optional

from solace.messaging.config.solace_properties import (
    authentication_properties,
    service_properties,
    transport_layer_properties,
)

CONFIG_FILE = "config.json"

# the three connection values every sample needs; the password is optional
REQUIRED_KEYS = (
    transport_layer_properties.HOST,
    service_properties.VPN_NAME,
    authentication_properties.SCHEME_BASIC_USER_NAME,
)


class SolaceConnectionConfig:
    """Connection details for one sample, loaded from config.json or the command line."""

    def __init__(self, properties: dict[str, object]) -> None:
        for key in REQUIRED_KEYS:
            _require_non_blank(properties.get(key), key)
        # password is optional: absent or blank means an empty password is sent
        self._properties = properties

    @staticmethod
    def load(args: list[str], app_name: str) -> "SolaceConnectionConfig":
        """
        Load connection details from config.json when present in the working directory,
        otherwise from the command-line arguments, and log which source was used. Prints a
        usage line and exits when neither source supplies host, message-vpn, and
        client-username; rejects a blank host, message-vpn, or client-username from either
        source.

        Args:
            args: the app's command-line arguments without the script name (the fallback source)
            app_name: the application name, used only in the usage message
        """
        from_file = _try_load_config_file()
        if from_file is not None:
            host = from_file._properties[transport_layer_properties.HOST]
            trace(f"{app_name}: using connection details from {CONFIG_FILE} (host={host}).")
            return from_file
        if len(args) < 3:
            trace(f"No {CONFIG_FILE} in the working directory; provide connection details on the command line.")
            trace(f"Usage: {app_name} <host:port> <message-vpn> <client-username> [password]\n")
            sys.exit(1)
        from_args: dict[str, object] = {
            transport_layer_properties.HOST: args[0],
            service_properties.VPN_NAME: args[1],
            authentication_properties.SCHEME_BASIC_USER_NAME: args[2],
        }
        if len(args) > 3:
            from_args[authentication_properties.SCHEME_BASIC_PASSWORD] = args[3]
        config = SolaceConnectionConfig(from_args)
        trace(f"{app_name}: using connection details from command-line arguments (host={args[0]}).")
        return config

    def to_service_properties(self) -> dict[str, object]:
        """
        Build the from_properties() dict from every loaded property (generic pass-through).
        The basic-auth password key is always present: the API requires it at build(), so
        an absent or blank password is passed as an empty string.
        """
        properties: dict[str, object] = dict(self._properties)
        if not properties.get(authentication_properties.SCHEME_BASIC_PASSWORD):
            properties[authentication_properties.SCHEME_BASIC_PASSWORD] = ""
        return properties


def _try_load_config_file() -> Optional[SolaceConnectionConfig]:
    if not os.path.exists(CONFIG_FILE):
        return None  # no config file: fall back to the command-line arguments
    # present but unreadable (for example a permission-locked credentials file): open()
    # raises PermissionError, so this fails loud rather than silently falling back to
    # different command-line connection args. A malformed file raises JSONDecodeError.
    with open(CONFIG_FILE, encoding="utf-8") as config_file:
        parsed = json.load(config_file)
    if not isinstance(parsed, dict):
        raise ValueError(f"{CONFIG_FILE} must hold a single JSON object")
    for required in REQUIRED_KEYS:
        if required not in parsed:
            raise ValueError(f'{CONFIG_FILE} is missing required key "{required}"')
    return SolaceConnectionConfig(parsed)


def _require_non_blank(value: object, key: str) -> None:
    """Reject a missing, non-string, or blank required connection value, whatever its source."""
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f'connection value for "{key}" must be a non-blank string')


def trace(message: str) -> None:
    """Demo narration sink: every status line in this helper funnels through this one
    function. An application replaces this single body to route narration to its
    logger or reporting system."""
    print(message, flush=True)
