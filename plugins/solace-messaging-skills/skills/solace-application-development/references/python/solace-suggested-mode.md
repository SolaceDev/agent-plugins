# Solace Suggested Mode

The Solace Suggested overlay for Implement mode of the Python API. This file is reached from `implement-mode.md` Step 0 when the developer chooses the Solace Suggested path. It hardens whatever the design summary chose without re-describing how the chosen leaf is generated. Work through the steps in order. Read this file's grounding links only as each step needs them; link the canonical doc and state the decision in one line, never paraphrase doc content.

This overlay is TLS-secure by definition. It assumes a broker whose TLS server certificate is already configured; Solace Cloud, the primary broker default, has TLS out of the box. There is NO non-secure variant of Solace Suggested. When the target broker has no TLS configured, do not proceed on this path: state that Solace Suggested requires a TLS-capable broker and route back to the `implement-mode.md` Step 0 door, which offers Custom with the equivalent knobs minus the secure session, or Quickstart. The session is built from the connection details the developer puts into a gitignored `config.json`. The overlay does NOT provision or configure any broker (no DMQ, no queues, no TLS certificates). It states the broker-side prerequisites; it never sets them up.

## This is a leaf-agnostic overlay

The Solace Suggested hardening is described ONCE here and applies on top of whatever `Pattern` leaf the design summary selected. It is not duplicated per leaf. The session-level deltas (TLS and HA failover) are identical code in every leaf, the message-level delta (DMQ eligibility) lands only on persistent publishes, and the separate-projects layout applies to every pattern, since each has two roles (publisher and subscriber, or requestor and replier).

This overlay defers to `implement-mode.md` for all leaf and sample mechanics: the Step 2 design-summary input contract, the Step 4 leaf dispatch (which leaf wiring file and samples each leaf reads, the near-verbatim adaptation discipline), and the fixed `VERIFY:` marker contract. It ADDS only the Solace Suggested deltas below. It changes NO `VERIFY:` marker string. The marker contract, the leaf dispatch table, and the checklist write (Step 4) and report (Step 6) all stay in `implement-mode.md` and its leaf wiring files, and run unchanged on the Solace Suggested path.

Every generated script this overlay describes carries the AI-assisted disclaimer header (the exact two lines `implement-mode.md` Step 4 defines) and keeps every import explicit; never write `from solace.messaging... import *`. This overlay names identifiers, constants, and method calls; it does not paste whole generated scripts.

## Step 0: Carry over the config.json convention

The Solace Suggested path reads broker connection details ONLY from a `config.json` file. Its keys are the Solace Python API property names the shared helper documents (`solace.messaging.transport.host`, `solace.messaging.service.vpn-name`, `solace.messaging.authentication.basic.username`, `solace.messaging.authentication.basic.password`), plus the trust store key that Step 1 adds. State plainly to the developer that `config.json` holds broker credentials, so it MUST be added to `.gitignore` and never committed, and that the credential VALUES MUST be placed directly into `config.json`, never pasted into the chat. Never inline a host, message VPN, username, or password into a generated script.

On this path there are no command-line connection arguments. Generate the shared `solace_connection_config.py` helper WITHOUT its command-line fallback: when `config.json` is absent, it prints one line that names the missing file and exits with status 1. Add the trust store key to its required keys, so a missing trust store fails at start with a clear message. Keep everything else in the helper (the pass-through of every key, the blank-value checks, the empty-password rule). The generated `config.example.json` carries the four keys plus the trust store key, with a `tcps://` placeholder host:

```json
{
  "solace.messaging.transport.host": "tcps://HOST:55443",
  "solace.messaging.service.vpn-name": "YOUR_VPN",
  "solace.messaging.authentication.basic.username": "YOUR_USERNAME",
  "solace.messaging.authentication.basic.password": "YOUR_PASSWORD",
  "solace.messaging.tls.trust-store-path": "PATH_TO_TRUST_STORE_DIRECTORY"
}
```

Generate the scripts directly, with no per-stage confirmation gate, the same way Quickstart does, then let the developer review the written files. The credential VALUES live only in the gitignored `config.json`, never in a generated script or the chat, so there is nothing credential-bearing to gate on.

## Step 1: Build the TLS secure session

The `host` in `config.json` carries a `tcps://` scheme: the Messaging Service page states that TLS needs the secure TCP protocol (`tcps`) in the host property. If the developer gives a `tcp://` (plaintext) host, tell them to use the broker's `tcps://` URL and state the TLS-already-configured assumption; never open a plaintext session on the Solace Suggested path. If the broker genuinely has no TLS configured, this overlay does not fit: route back to the `implement-mode.md` Step 0 door (Custom minus the secure-session knob, or Quickstart) rather than degrading the session here.

Set server-certificate validation EXPLICITLY in code. After `load_service_properties(...)` returns and BEFORE the service is built, set these three `transport_layer_security_properties` keys to `True` on the loaded properties dict: `CERT_VALIDATED`, `CERT_REJECT_EXPIRED`, and `CERT_VALIDATE_SERVERNAME`. The values are the API defaults, but setting them explicitly makes the secure posture auditable in code review, the same reason the JCSMP overlay sets its validation properties explicitly. Because the code writes them after the file is loaded, a `config.json` value cannot turn validation off. NEVER set any of the three to `False`, and NEVER call `without_certificate_validation()`; the Messaging Service page restricts that call to development environments.

**The trust store is the developer's.** The Python API documents no default trust store, so this overlay always sets one: the `solace.messaging.tls.trust-store-path` key in `config.json` (the `TRUST_STORE_PATH` property) names a directory that holds the certificate of the CA that signed the broker's server certificate. The developer supplies the directory and its contents; for Solace Cloud that is the public CA certificate the service uses. The generated code never downloads a certificate, never creates the directory, and never hardcodes the path.

**Tell the developer about the trust store, twice.** This step is new to a developer who comes from JCSMP (Java ships a default trust store, so JCSMP needs no such step) and easy to miss for a developer new to TLS, so do not leave it to the checklist alone:

1. BEFORE generating, in plain words: with TLS, the app checks that the broker's certificate was signed by a certificate authority (CA) it trusts. The Python API has no built-in list of trusted CAs, so the developer must give the app one: a directory that holds the CA certificate as a PEM file. For Solace Cloud, the service's **Connect** tab has a **Download PEM** link for that certificate; link [Using Client Certificates with Solace Cloud](https://docs.solace.com/Cloud/ght_client_certs.md), which states that a client needs the certificate for the public CA used by the event broker service and that not all clients have a default trust store. For another broker, the broker administrator supplies the CA certificate. Without the directory, the connection fails at start.
2. In the handed-back next steps (`implement-mode.md` Step 5): create a directory (for example `trust-store/` in the project), save the downloaded PEM file into it, and set `solace.messaging.tls.trust-store-path` in `config.json` to that directory. Recommend that the directory sits outside version control or holds only the public CA certificate; never put a private key in it.

Record the same item in the checklist under Your responsibility (`implement-mode.md` Step 6).

Ground TLS in the TLS section of [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md). On Linux, TLS also needs OpenSSL installed ([Supported Environments](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-supported-Environments.md)).

## Step 2: Ask the HA-failover question; upgrade the reconnect values on yes, keep the sample baseline on no

After broker confirmation and before generating the service, ask the developer plainly: is HA failover set up? Ask it as a direct question and wait for the answer.

The reference samples already ship a reasonable reconnect baseline: `with_reconnection_retry_strategy(RetryStrategy.parametrized_retry(RECONNECT_RETRIES, RECONNECT_RETRY_INTERVAL_MS))` with 20 retries 3000 ms apart, the recommended default when the design says nothing about high availability. When the answer is no, KEEP this baseline as-is; do not strip it back to the API defaults (the fail-fast posture belongs to the Quickstart door alone), and do not add the HA-only values.

ONLY when the answer is yes, apply the HA failover values the samples' HA comment block names: `with_connection_retry_strategy(RetryStrategy.parametrized_retry(1, 3000))`, `with_reconnection_retry_strategy(RetryStrategy.parametrized_retry(20, 3000))`, and `transport_layer_properties.CONNECTION_RETRIES_PER_HOST` set to 5 on the properties dict. These map to the JCSMP overlay's `connectRetries` 1, `reconnectRetries` 20, `reconnectRetryWaitInMillis` 3000, and `connectRetriesPerHost` 5. Keep both strategy calls BEFORE `from_properties(...)`, as the samples order them. Replace the samples' HA comment block with a one-line comment that names the applied values. Every interval is in MILLISECONDS (the seconds-versus-milliseconds trap in `implement-mode.md` Step 5). Ground the values in [Solace C API Best Practices](https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md), which the Python API inherits because it wraps the C API.

## Step 3: Set DMQ eligibility on every persistent publish (Solace Suggested path only)

On the Solace Suggested path, add `.with_property(message_properties.PERSISTENT_DMQ_ELIGIBLE, True)` to the persistent publisher's `message_builder()` template, next to the `PERSISTENT_TIME_TO_LIVE` property it already sets, so every message built from the template carries it. In the Python API this applies to the Guaranteed Pub/Sub publisher only: there is no guaranteed request-reply leaf, so no other script builds a persistent message. The API reference gives this property a default of `True`; set it explicitly anyway, for the same auditable reason as the TLS properties in Step 1.

The direct leaves are EXCLUDED. The API reference states that the property is valid only for persistent messages, so it is a misleading no-op on a direct message; do NOT set it on Direct Pub/Sub or Request-Reply (Direct). Quickstart and the reference samples are unchanged; the explicit property is a Solace Suggested addition only.

At the call site, state the broker-side prerequisite as the developer's responsibility in one comment: a message moves to a dead message queue only when a DMQ is configured on the broker AND a max-redelivery count or a time-to-live applies. The flag alone does not make DMQ work. Ground the persistent publish in [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md) and the broker side in [Configuring Dead Message Queues](https://docs.solace.com/Messaging/Guaranteed-Msg/Setting-Dead-Msg-Queues.md).

## Step 4: Generate separate, independent Python projects

The Solace Suggested path decouples the roles. The DEFAULT shape is one standalone Python project per role (publisher and subscriber, or requestor and replier), each in its own directory. On the fan-out leaf, every subscriber script gets its own project beside the publisher project. Each project is self-contained: its own `requirements.txt` with the `solace-pubsubplus` version resolved per `implement-mode.md` Step 3, its own `.venv`, its own copy of `solace_connection_config.py`, its own `config.example.json`, `.gitignore`, and gitignored `config.json`, and its own tailored `solace-verification-checklist.md`. There is no shared package and no shared virtual environment between the projects.

**When the developer's ask requires a single deliverable** (one command, one app, one dashboard), the separate-projects default conflicts with the ask. Surface that conflict plainly and let the developer resolve it; NEVER merge silently. Offer two resolutions: (1) keep the independent projects plus a thin launcher script that starts each one with its own `.venv/bin/python`; (2) drop the separate-projects shape with the developer's explicit consent. Whichever they choose is recorded in the emitted `solace-verification-checklist.md`; a consented drop lands under Developer-owned items, so the deviation is written down, not lost.

The Solace Suggested path adds no new dependency. TLS, DMQ, and the retry strategies all live in the same `solace-pubsubplus` package Quickstart already uses.

**Verification.** The Python API does not bundle a verify script yet, so the Solace Suggested path ends in the same check-and-hand-back fallback as Quickstart (`implement-mode.md` Step 5), run once per project: `py_compile`, the import smoke, and the conformance checks from each project root. Hand back the run commands per project, long-running role first, each from its own directory with its own interpreter, and with NO connection arguments (each script reads its own `config.json`). When the developer asks you to run them, follow the manual-run rule in `implement-mode.md` Step 5, including the live-broker heads-up (SKILL.md Invariant 6). A live Solace Suggested run needs a TLS-capable broker and the trust store from Step 1; a TLS or trust store error is an environment failure, never a code failure, so do not modify the code for it.

## Step 5: Topic architecture

For how to structure, order, name, and place wildcards in the topic hierarchy, point the developer to the co-installed `solace-topic-best-practices` skill, which reads the canonical Topic Architecture Best Practices page live and applies it. Do not design the topic hierarchy here and do not restate that skill's guidance; the topic string the developer settles on is recorded in the design summary, but the hierarchy decision belongs to that skill.

## Step 6: Return to the implement-mode contracts

After the Solace Suggested deltas above, return to `implement-mode.md` for the unchanged leaf mechanics: the Step 4 dispatch generates the chosen leaf's scripts and writes the tailored `solace-verification-checklist.md` into each project as generation output, and Step 6 resolves each item to its state for this door, invites the developer's additions, and reports the same in chat. This overlay adds the Solace Suggested hardening; it does not replace those contracts.

## Grounding references

Live `docs.solace.com` `.md` pages (WebFetch on demand):

- [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- [Solace C API Best Practices](https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md)
- [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md)
- [Configuring Dead Message Queues](https://docs.solace.com/Messaging/Guaranteed-Msg/Setting-Dead-Msg-Queues.md)
- [Supported Environments](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-supported-Environments.md)
- [Using Client Certificates with Solace Cloud](https://docs.solace.com/Cloud/ght_client_certs.md)

Live exception (no `docs.solace.com` `.md` form; use the live URL directly):

- [Python API reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/python/index.html) (the `transport_layer_security_properties`, `transport_layer_properties`, and `message_properties` constants)
