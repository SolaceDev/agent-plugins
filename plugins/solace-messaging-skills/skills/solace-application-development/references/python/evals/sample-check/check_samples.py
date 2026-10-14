"""Sample check for the baked Solace Python samples, run by check-samples.sh.

check-samples.sh copies every sample into the working directory under its generated name and
runs this file with the virtual environment's interpreter. Without a broker, it:

1. imports each sample module (each keeps its __main__ guard, so nothing connects),
2. calls each sample's setup_solace(), which builds the MessagingService and the
   role's publisher, receiver, or request-reply objects, then teardown_solace() on the
   never-connected service, and
3. exercises the shared solace_connection_config helper's config.json, command-line
   fallback, and fail-fast paths.

build() resolves the host, so every build uses tcp://localhost:55555, which resolves
whether or not a broker listens there. Any failure raises, and the non-zero exit fails
the gate.
"""

import importlib
import json
import os
import tempfile
import threading

MODULES = [
    "guaranteed_publisher",
    "guaranteed_subscriber",
    "direct_publisher",
    "direct_subscriber",
    "direct_requestor_blocking",
    "direct_requestor",
    "direct_replier",
]

HOST_KEY = "solace.messaging.transport.host"
VPN_KEY = "solace.messaging.service.vpn-name"
USER_KEY = "solace.messaging.authentication.basic.username"
PASSWORD_KEY = "solace.messaging.authentication.basic.password"

PROPERTIES = {
    HOST_KEY: "tcp://localhost:55555",
    VPN_KEY: "smoke-vpn",
    USER_KEY: "smoke-user",
    PASSWORD_KEY: "smoke-pass",
}


def build_samples() -> None:
    for name in MODULES:
        module = importlib.import_module(name)
        state = module.setup_solace(dict(PROPERTIES), threading.Event())
        module.teardown_solace(state)
        print(f"PASS: {name} builds its service and role objects without a broker", flush=True)


def expect_exit(call, case: str) -> None:
    try:
        call()
    except SystemExit as exit_error:
        if exit_error.code == 1:
            return
        raise AssertionError(f"{case}: exit code {exit_error.code}, expected 1") from exit_error
    raise AssertionError(f"{case}: expected the helper to exit")


def expect_value_error(call, case: str) -> None:
    try:
        call()
    except ValueError:
        return
    raise AssertionError(f"{case}: expected a ValueError")


def check_config_helper() -> None:
    from solace_connection_config import load_service_properties

    start_dir = os.getcwd()
    with tempfile.TemporaryDirectory() as work:
        os.chdir(work)
        try:
            def write_config(config: dict) -> None:
                with open("config.json", "w", encoding="utf-8") as config_file:
                    json.dump(config, config_file)

            # a valid config.json round-trips every key, including a pass-through key
            write_config({**PROPERTIES, "solace.messaging.tls.trust-store-path": "/smoke/trust"})
            loaded = load_service_properties([], "smoke")
            assert loaded[HOST_KEY] == PROPERTIES[HOST_KEY], loaded
            assert loaded["solace.messaging.tls.trust-store-path"] == "/smoke/trust", loaded

            # config.json wins over command-line arguments
            loaded = load_service_properties(["tcp://other:55555", "v", "u"], "smoke")
            assert loaded[HOST_KEY] == PROPERTIES[HOST_KEY], loaded

            # a missing required key fails fast
            write_config({VPN_KEY: "smoke-vpn", USER_KEY: "smoke-user"})
            expect_value_error(lambda: load_service_properties([], "smoke"), "missing-key")

            # a present but blank required value fails fast
            write_config({**PROPERTIES, HOST_KEY: " "})
            expect_value_error(lambda: load_service_properties([], "smoke"), "blank-value")

            # with no config.json, the command-line arguments are the source, and an
            # omitted password is sent as an empty string
            os.remove("config.json")
            loaded = load_service_properties(["tcp://localhost:55555", "smoke-vpn", "smoke-user"], "smoke")
            assert loaded[USER_KEY] == "smoke-user" and loaded[PASSWORD_KEY] == "", loaded

            # a blank command-line argument fails fast
            expect_value_error(lambda: load_service_properties(["", "smoke-vpn", "smoke-user"], "smoke"), "args-blank")

            # neither source: print the usage line and exit 1
            expect_exit(lambda: load_service_properties([], "smoke"), "no-source")
        finally:
            os.chdir(start_dir)
    print("PASS: solace_connection_config file, fallback, and fail-fast paths", flush=True)


if __name__ == "__main__":
    build_samples()
    check_config_helper()
