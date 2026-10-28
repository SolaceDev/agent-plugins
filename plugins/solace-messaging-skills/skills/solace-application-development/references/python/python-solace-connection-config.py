"""
Shared connection-config helper for the Python reference samples.

Loads connection properties into the dict that
MessagingService.builder().from_properties(...) takes. Source precedence:
  1. config.json in the working directory, when present. Its keys are Solace
     Python API property names (the values of the solace_properties constants):
       "solace.messaging.transport.host"
       "solace.messaging.service.vpn-name"
       "solace.messaging.authentication.basic.username"
       "solace.messaging.authentication.basic.password"
     Every key passes through to from_properties() unchanged, so other service
     properties need no parser change. A later builder call overrides the same
     key, and each sample sets its reconnection strategy before from_properties(),
     so reconnection keys in the file override the sample's default. The native library rejects an invalid value at build() or
     connect(); the API silently ignores an unknown key. config.json holds broker
     credentials, so it MUST be gitignored.
  2. otherwise the command-line arguments:
     <host:port> <message-vpn> <client-username> [password]

The password is optional. The API requires the basic-auth password key at build(),
so an absent or blank password is sent as an empty string. config.json must be a
single flat JSON object; the standard-library json module reads it.

Grounding: the "load a JSON file, then from_properties()" idiom and the required
host and vpn-name keys:
  https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md

Copied into a generated project as solace_connection_config.py (a module name
needs underscores); every sample imports it with
  from solace_connection_config import load_service_properties

Any generated adaptation of this sample MUST begin with the exact line:
  AI-assisted code. Review before production use.
(This reference sample itself carries no such header by design.)
"""

import json
import os
import sys

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


def load_service_properties(args: list[str], app_name: str) -> dict[str, object]:
    """
    Load connection details from config.json when present in the working directory,
    otherwise from the command-line arguments, log which source was used, and return the
    from_properties() dict with every loaded property (generic pass-through). Prints a
    usage line and exits when neither source supplies host, message-vpn, and
    client-username; rejects a blank host, message-vpn, or client-username from either
    source. The basic-auth password key is always present: the API requires it at build(),
    so an absent or blank password is passed as an empty string.

    Args:
        args: the app's command-line arguments without the script name (the fallback source)
        app_name: the application name, used only in the usage message
    """
    properties = _load_config_file()
    if properties is not None:
        source = CONFIG_FILE
    elif len(args) >= 3:
        source = "command-line arguments"
        properties = {
            transport_layer_properties.HOST: args[0],
            service_properties.VPN_NAME: args[1],
            authentication_properties.SCHEME_BASIC_USER_NAME: args[2],
        }
        if len(args) > 3:
            properties[authentication_properties.SCHEME_BASIC_PASSWORD] = args[3]
    else:
        trace(f"No {CONFIG_FILE} in the working directory; provide connection details on the command line.")
        trace(f"Usage: {app_name} <host:port> <message-vpn> <client-username> [password]\n")
        sys.exit(1)
    for key in REQUIRED_KEYS:
        _require_non_blank(properties.get(key), key)
    # password is optional: absent or blank means an empty password is sent
    if not properties.get(authentication_properties.SCHEME_BASIC_PASSWORD):
        properties[authentication_properties.SCHEME_BASIC_PASSWORD] = ""
    host = properties[transport_layer_properties.HOST]
    trace(f"{app_name}: using connection details from {source} (host={host}).")
    return properties


def _load_config_file() -> dict[str, object] | None:
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
    return parsed


def _require_non_blank(value: object, key: str) -> None:
    """Reject a missing, non-string, or blank required connection value, whatever its source."""
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f'connection value for "{key}" must be a non-blank string')


def trace(message: str) -> None:
    """Demo narration sink: every status line in this helper funnels through this one
    function. An application replaces this single body to route narration to its
    logger or reporting system."""
    print(message, flush=True)
