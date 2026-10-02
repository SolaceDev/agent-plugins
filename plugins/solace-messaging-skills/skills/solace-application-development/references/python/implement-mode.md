# Implement Mode

Generate a complete, runnable Python project from a confirmed design summary. The canonical output is TWO scripts (a publisher and a subscriber) plus the shared connection-config helper in ONE project directory with its own virtual environment. Each script is a near-verbatim adaptation of its reference sample. Near-verbatim adaptation of two samples is more reliable than merging them into one program, and a real deployment lifts each script into its own service or project.

Implement mode is NOT only for the canonical shape. A web app, a dashboard, a service, or any embedded shape where the Python messaging layer lives inside a larger application follows the SAME steps: the same door question, the same leaf wiring for the messaging layer, the same generation outputs, and the same verification fallback. Building an off-catalog shape never waives a step in this file.

Work through the steps in order. Read this file's grounding links only as each step needs them. Link the canonical doc and state the decision in one line, never paraphrase doc content. Resolve every dependency version at generation time; pin nothing in skill content (SKILL.md Invariant 3).

**Coverage in this release.** Implement mode generates the three Guaranteed Pub/Sub leaves on the Quickstart door. The Direct Pub/Sub and Request-Reply (Direct) leaves (Step 4) and the Solace Suggested and Custom doors (Step 0) are not yet available for Python. The Python API does not bundle a verify script yet, so every run ends in the compile-only fallback of Step 5.

## Generation outputs: the file contract

Every Implement run writes ALL of these as generation output, on every app shape. None of them is gated on consent, and none of them waits for the end of the session:

1. The generated scripts for the chosen leaf, plus the shared `solace_connection_config.py` helper. Every source file starts with the AI-assisted disclaimer header (Step 4).
2. `requirements.txt` with the `solace-pubsubplus` version resolved per Step 3.
3. `config.example.json` (placeholders) and a `.gitignore` that ignores `config.json` and the virtual environment (Step 4).
4. A tailored `solace-verification-checklist.md` at the project root (tailoring rules in Step 6; the WRITE happens here at generation time, like the scripts).

There is no `verify.sh` and no `verify-hooks.sh` in a Python project yet. Do not copy the JCSMP `verify.sh` into a Python project: it drives Maven and cannot run Python scripts.

## Step 0: Ask Quickstart, Solace Suggested, or Custom, before any broker details

This is the FIRST question, and you ask it before you request or accept ANY broker connection details. Ask the developer plainly which of three doors they want: Quickstart, Solace Suggested, or Custom. Ask it as a direct question and wait for the answer. Frame the three doors honestly: Quickstart is a learning and try-it path to see a round-trip work, NOT a deployment target; Solace Suggested is the hardened baseline Solace suggests as a starting point (TLS, dead message queue eligibility, HA failover, separate projects), and Solace suggests it without guaranteeing it is ready for deployment; Custom lets the developer tick exactly which hardening knobs to include. State in the same question that Solace Suggested and Custom are not yet available for the Python API in this release.

Three things do NOT answer this question, and none of them waives it:

- **An existing `config.json`**, even one that already holds real credentials. Ask anyway.
- **A running local broker discovered in the environment.** Report what you found, then ask both this question and which broker to target.
- **An app shape larger than the generated two-script project** (a web app, a dashboard, a service). The doors still govern the messaging layer and the credential placement. Ask anyway.

The doors:

- **Quickstart.** Each script builds its connection through the shared `solace_connection_config.py` helper, which reads the connection properties from a `config.json` in the working directory when present and otherwise falls back to the command-line arguments (`<host:port> <message-vpn> <client-username> [password]`). Quickstart adds no TLS hardening and has no TLS requirement: it connects with whatever scheme the developer's host carries, and a plaintext `tcp://` dev broker is a normal Quickstart target. Generate the scripts without per-stage preview gates. Quickstart output fails fast (the fail-fast rule in Step 4).
- **Solace Suggested or Custom.** Not yet available for Python. Say so plainly, and do not describe the hardened doors beyond the one-line framing in the Step 0 question. Offer the developer two honest choices: Quickstart now, framed as a learning run, or stop here with the design saved to `solace-design.md` for when the hardened doors are available. Never generate a Quickstart project under the Solace Suggested or Custom name.

State plainly that a real deployment lifts each script into its own service or project. Basic username/password is the only auth this journey generates; ground it in the authentication section of [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md).

## Step 1: Confirm broker access and grounding

With the door chosen, confirm the developer has access to a reachable broker, and confirm WHICH broker they want to target. Confirm that they HAVE one; do NOT ask them to paste connection details into the chat. When Design mode already confirmed broker access in this session (its Step 0), do not re-ask whether a broker exists; ask only WHICH broker to target. A broker discovered in the environment is a fact to report while asking, never an answer. The credential VALUES reach the app through a `config.json` when present, otherwise as command-line arguments at run time; the skill itself never needs the values typed into the chat.

If the developer has no reachable broker, route through `prerequisites.md` first, then return here.

Grounding catch-up: if the design summary names a page in its Grounding docs field that this session has not WebFetched, fetch it now before generating against it (SKILL.md Invariant 2).

## Step 2: Establish the design as the input contract

Implement mode works from the unified design summary of eight fields that `design-mode.md` defines: Pattern, Delivery, Access type, Topic, Consumption endpoint, Auth, Broker, Grounding docs. The `Pattern` field is one of the five Python leaf strings.

The summary is a HARD precondition (the design-contract gate in `python.md`): Implement mode never proceeds past this step without one, and never invents design values. Exactly three sources satisfy it:

- A summary Design mode confirmed in this session: use it as-is, with no re-confirm.
- An explicit summary the developer supplied in chat (the eight fields or an equivalent statement of them): use it directly. If it omits a derivable field, derive it rather than asking (the topic via the solace-topic-best-practices skill, the queue name via the `q.`-prefixed convention), the same way Design mode does.
- A saved `solace-design.md` in the developer's project: read it, restate it in one line, and proceed on their confirm. A saved summary titled `Solace JCSMP Design Summary` belongs to the JCSMP API: say so and ask whether the developer wants a Python project from it, then re-confirm it through Design mode, because a JCSMP leaf such as `Request-Reply (Guaranteed)` has no Python equivalent.

If none of the three exists, route through Design mode (`design-mode.md`) and return with its confirmed summary. The only carve-out is the topology rule in `python.md`: a mechanical edit to an existing app proceeds without a summary.

## Step 3: Resolve the version, then write requirements.txt and the virtual environment

### Resolve the latest solace-pubsubplus version at generation time

Never hardcode a `solace-pubsubplus` version in this skill or carry one in your memory. Resolve the latest release from the AUTHORITATIVE PyPI JSON API at generation time:

```bash
curl -s https://pypi.org/pypi/solace-pubsubplus/json \
  | python3 -c 'import json, sys; print(json.load(sys.stdin)["info"]["version"])'
```

If the network query fails, do not guess a version and do not write an unpinned requirement. Ask the developer for the version they want, pin that version, and tell the developer that PyPI was unreachable, so the pin is their choice and not a resolved release.

### Write requirements.txt and create the virtual environment

- **Single Solace dependency: `solace-pubsubplus`.** Write it as `solace-pubsubplus==<the version resolved above>` (or the developer's version when PyPI was unreachable), so the generated project installs exactly the release it was generated against. That pin is generation output resolved at generation time, the same way the JCSMP pom carries a resolved `sol-jcsmp` version; it is never a version written in skill content. Add no other dependency: the samples need only the standard library besides the Solace API.
- **Python version.** Check `python3 --version` in the developer's environment. When it reports a Python that is no longer in active or security support, tell the developer, link `prerequisites.md`'s Supported environments section, and do not choose an older interpreter for them.
- **Virtual environment.** Create it at the project root and install into it: `python3 -m venv .venv`, then `.venv/bin/pip install -r requirements.txt` (on Windows, `.venv\Scripts\pip`). Every run command this mode hands back uses the virtual environment's interpreter (`.venv/bin/python`), never the system `python3`.
- **Logging.** The samples log API events through the standard `logging` module, configured once with `logging.basicConfig(level=logging.INFO, ...)` in the `__main__` block. Keep that configuration in every generated script. Never raise the level above INFO, because the API's own connect and reconnect diagnostics travel the same channel.

## Step 4: Dispatch on the Pattern leaf, then generate the scripts

Generation is keyed on the `Pattern` leaf read in Step 2. Read ONLY the leaf file the design chose, and do not read samples for a leaf the design did not choose.

| `Pattern` leaf (verbatim) | Leaf wiring file | Samples it reads |
|---------------------------|------------------|------------------|
| `Guaranteed Pub/Sub (single service, exclusive)` | `implement-guaranteed-pubsub.md` | `python-guaranteed-publisher-sample.py` + `python-guaranteed-subscriber-sample.py` |
| `Guaranteed Pub/Sub (single service, non-exclusive)` | `implement-guaranteed-pubsub.md` | `python-guaranteed-publisher-sample.py` + `python-guaranteed-subscriber-sample.py` |
| `Guaranteed Pub/Sub (fan-out)` | `implement-guaranteed-pubsub.md` | `python-guaranteed-publisher-sample.py` + `python-guaranteed-subscriber-sample.py` |
| `Direct Pub/Sub` | not yet available | none |
| `Request-Reply (Direct)` | not yet available | none |

**The two direct leaves stop here.** Say in one line that generating a Python project for this leaf is not yet available in this release. Offer to save the confirmed summary to `solace-design.md` if it is not saved yet, and stop. Do not read the direct samples and do not adapt them by hand: without a wiring file there is no marker contract and no conformance check for the result. Do not offer to write the code another way (from scratch, "outside the generator", or as unverified code), and do not write it when the developer asks again: the stop is the ENTIRE answer, the same way the Debug redirect is. Point the developer at the saved design and at the [Python tutorials](https://tutorials.solace.dev/python/) instead.

**Every leaf also generates the shared connection helper.** Read `python-solace-connection-config.py` and generate it as `solace_connection_config.py` (a module name needs underscores). Each script builds its connection dict by calling `load_service_properties(args, APP_NAME)` from that module and passes it to `MessagingService.builder().from_properties(...)`, rather than setting the host, VPN, username, or password inline.

**Fixed file names.** Generate the scripts as `guaranteed_publisher.py` and `guaranteed_subscriber.py`, the names each sample's docstring gives, next to `solace_connection_config.py` (the fan-out leaf adds one subscriber script per further consuming service, named in `implement-guaranteed-pubsub.md`). The run commands in Step 5 and the checklist address these names; the names of functions, constants, and the `APP_NAME` value inside each script stay your discretion.

**Disclaimer header on EVERY generated source file.** Stamp the top of each generated `.py` file with these two comment lines, before the module docstring and before any import:

```python
# AI-assisted code. Review before production use.
# See the verification checklist: solace-verification-checklist.md
```

Stamp the header AT WRITE TIME, as the first content of each new file. `requirements.txt`, `config.example.json`, and `.gitignore` are not source and carry no header. The reference samples themselves carry NO disclaimer. This rule is SKILL.md Invariant 4.

**Embedded and web-app shapes.** When the Python messaging layer sits inside a larger application, the chosen leaf's wiring still governs the messaging layer: the same connection helper, the same service, receiver, and publisher listeners, the same `VERIFY:` markers emitted through a stdout-reaching `trace(...)`, and the disclaimer header on every generated file, messaging or not. The `VERIFY:` markers are NOT demo harness.

**Write the verification artifacts in this same step.** Alongside the scripts, write the tailored `solace-verification-checklist.md` at the project root (Step 6), `config.example.json`, and `.gitignore`. A session that ends early still leaves the checklist in the project.

### Concurrency: threads, not asyncio, and no multiprocessing

The Python API calls application code on its own threads, and the generated code must respect that model. WebFetch [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md) before generating a receiver. That page grounds the `receive_async()` rule and the acknowledgement rule; the handler rule comes from the samples' handler docstrings and the C API Best Practices they cite, and the `multiprocessing` rule comes from the PyPI page:

- **`receive_async()` is not asyncio.** The page states that `receive_async()` is not an asynchronous coroutine or generator, is not `asyncio` compatible, returns immediately, and invokes the callback on a new Python thread for every message. Never `await` the API and never call it from inside a coroutine. In an asyncio application, keep the Solace objects on their own threads and hand each received message to the event loop with the standard library's `loop.call_soon_threadsafe(...)` (a standard library call, not a Solace API). Acknowledge the message only after the application has processed it and before the receiver terminates: the page states that any routine can acknowledge messages at any time as long as the receiver has not been terminated.
- **Handlers return promptly.** Every listener and message handler runs on an API thread. Return promptly and hand heavy work to the application's own thread or queue, as the samples' handler docstrings state.
- **No `multiprocessing`.** The Solace Python API cannot be used in an application that uses the Python `multiprocessing` module (the [solace-pubsubplus PyPI page](https://pypi.org/project/solace-pubsubplus/) states this). To scale consumption, run more independent processes of the subscriber on a non-exclusive queue, each started on its own; never fork workers from one process with `multiprocessing`.

### Adapting a sample to a variant of its leaf

The samples are grounded REFERENCE for the API idioms, not templates that must be reproduced line for line. Near-verbatim adaptation is the default WHEN the design summary matches the leaf's base shape. When the summary describes a variant, ADAPT the sample's idioms to honor the approved design; do NOT snap the variant back to the sample's default shape, and do NOT frame the deviation as a "conflict" with the skill.

Hold every invariant while adapting: keep the shared connection helper, the service event listeners registered before `connect()`, the receipt, termination, and state listeners, the disclaimer header, and the leaf's `VERIFY:` markers. Any NEW named entity the variant introduces must be traceable to a canonical Solace source per Invariant 1: WebFetch the grounding page for it.

**Guardrail: variant versus off-catalog.** Adapt freely WITHIN the chosen pattern family when the change is a topology or reliability knob that the canonical docs ground and that still emits the leaf's `VERIFY:` markers. If a requirement leaves the Solace topic and queue publish and subscribe model, or needs behavior the canonical docs cannot ground, STOP and tell the developer it is off-catalog rather than inventing it.

**Preserve the marker contract.** A variant adapts the SHAPE, never the marker strings. It MUST still emit its leaf's `VERIFY:` markers, character for character. Never rename or drop a marker to fit a variant.

**Demo harness is not application logic.** The samples carry demo-harness elements that exist only to make a standalone run observable: the once-per-second stats loop or thread, the continuous publish loop, and the rotating example payload. When the developer's request describes a real application domain, do NOT carry these into the generated scripts: replace the example payload and the publish loop with the application's real messages and triggers, never add a delay or rate limit between sends that the application does not ask for, and keep only the lifecycle pieces the leaf's steps call for (the SIGINT/SIGTERM shutdown on the long-running role, the self-exit on the foreground role). When the request IS a demo or a try-it run, keeping the harness is fine. Two things are NOT demo harness: the `trace(...)` narration function (keep it; applications replace its single body to route narration elsewhere) and the `VERIFY:` markers, which are emitted through `trace(...)`. The generated `trace(...)` body MUST stay `print(message, flush=True)`, so the markers reach stdout without buffering delays; rerouting it is the developer's step after verification.

**Comments follow their code.** The samples' comments carry the documented best practices into the code a developer reads long after this session. A generated script that carries a construct from a sample carries that construct's comment too, with only the names adapted. A dropped construct takes its comment with it. Fresh messaging code with no sample twin that applies a documented practice gets a SHORT comment naming the practice, in the samples' comment style. The one-line comments this skill mandates elsewhere (the Quickstart fail-fast posture below) always apply on their paths.

### Quickstart fail-fast reconnect defaults

On the Quickstart door, do not carry the samples' reconnect-tuning block into the generated scripts. The samples set a reconnection budget (`with_reconnection_retry_strategy(RetryStrategy.parametrized_retry(...))` with the `RECONNECT_RETRIES` and `RECONNECT_RETRY_INTERVAL_MS` constants) and carry an HA failover comment block; Quickstart generation OMITS the builder call, the two constants, the HA comment block, and the then-unused `RetryStrategy` import, leaving connect and reconnect behavior at the API defaults, so a wrong host, port, or credential in this learning setup fails immediately instead of retrying. Drop ONE short comment at the `MessagingService.builder()` site in each generated script stating that posture: connect and reconnect settings are at the API defaults so failures surface immediately; see the [Solace C API Best Practices](https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md) for reconnect tuning beyond Quickstart.

### Scaffold the config files

Generate two small project files next to the scripts:

- A committed `config.example.json` that carries placeholder values for the four keys the shared helper requires, spelled as the Solace Python API property names the helper documents. The placeholder values must be obviously fake:

  ```json
  {
    "solace.messaging.transport.host": "tcp://HOST:55555",
    "solace.messaging.service.vpn-name": "YOUR_VPN",
    "solace.messaging.authentication.basic.username": "YOUR_USERNAME",
    "solace.messaging.authentication.basic.password": "YOUR_PASSWORD"
  }
  ```

- A `.gitignore` at the project root that ignores `config.json`, `.venv/`, and `__pycache__/`, so the real credential-bearing file and the local environment are never committed.

Tell the developer they opt in to the config file by copying `config.example.json` to `config.json` and filling in their real broker values. Both scripts then read it from the working directory, so they run from the project root.

## Step 5: Check the project, then hand back the run commands

The Python API does not bundle a verify script yet, so Implement mode ends in the compile-only fallback on every run, whatever the contents of `config.json` (SKILL.md Invariant 5). Never fabricate a run result and never call a check below a verified round trip.

1. **Compile check.** Run `.venv/bin/python -m py_compile` on every generated `.py` file. A failure is a code failure: fix it and re-run, up to 3 attempts, then report the evidence and hand the decision to the developer.
2. **Import smoke.** From the project root, run `.venv/bin/python -c "import solace_connection_config, guaranteed_publisher, guaranteed_subscriber"`, adding every further generated module (for example each fan-out subscriber). Each script keeps its `if __name__ == "__main__":` guard, so the import resolves every `solace.messaging` name the scripts use without connecting to a broker. A failure (for example an `ImportError` on a misspelled module path) is a code failure under the same fix loop.
3. **Conformance checks.** From the project root, run these three checks and record each result in the checklist. A missing header or a missing checklist is a code failure under the same fix loop; a version mismatch is reported to the developer, not fixed silently.

   ```bash
   # every generated .py file starts with the disclaimer header
   for f in *.py; do head -n 1 "$f" | grep -qxF '# AI-assisted code. Review before production use.' || echo "MISSING HEADER: $f"; done
   # the tailored checklist exists
   test -f solace-verification-checklist.md || echo "MISSING CHECKLIST"
   # the pin matches the release PyPI reports now (skip and record it when PyPI is unreachable)
   grep -qxF "solace-pubsubplus==$(curl -s https://pypi.org/pypi/solace-pubsubplus/json | python3 -c 'import json, sys; print(json.load(sys.stdin)["info"]["version"])')" requirements.txt || echo "VERSION DRIFT"
   ```

4. **Report and hand back.** Report the compile, import, and conformance results, then hand the developer the exact commands to run against their own broker from the project root, subscriber first so the durable queue and its subscription exist before the first publish:

   ```
   # terminal 1: provisions the queue, binds, and waits (Ctrl-C stops it)
   .venv/bin/python guaranteed_subscriber.py [<host:port> <vpn> <user> [password]]
   # terminal 2: publishes, waits for the broker's acknowledgement, and exits
   .venv/bin/python guaranteed_publisher.py [<host:port> <vpn> <user> [password]]
   ```

   On the fan-out leaf, hand back one subscriber command per subscriber script (`guaranteed_subscriber.py` and each `guaranteed_subscriber_<service>.py`), each in its own terminal and all started before the publisher; every subscriber prints its own markers.

   The arguments are needed only when there is no `config.json`. Tell the developer what a passing run prints: the subscriber prints `VERIFY: CONNECTED` and then `VERIFY: QUEUE_BOUND`; the publisher prints `VERIFY: CONNECTED` and `VERIFY: PUBLISH_ACKED` and exits with status 0; the subscriber then prints `VERIFY: MESSAGE_RECEIVED`; Ctrl-C on the subscriber prints `Shutdown signal received` and it exits with status 0.

**When the developer asks you to run it.** If the developer explicitly asks you to run the two scripts against their broker, print the live-broker heads-up first (SKILL.md Invariant 6: name the host, every durable queue the subscribers provision, one connection per script, and the messages the publisher sends, scaled to the generated scripts). Then start each subscriber in the background with its output captured, wait for `VERIFY: QUEUE_BOUND` from each, run the publisher in the foreground, wait for `VERIFY: MESSAGE_RECEIVED` in each subscriber's output, and stop the subscribers with SIGINT. Report the markers you observed as evidence of a manual run. It is not a verify verdict: record it in the checklist as a manual run, and never describe it as a passed verify stage.

**Environment failure on a manual run: do NOT modify the code.** When the captured output shows that the broker is unreachable, the credentials or VPN are wrong, or the client may not create the queue (the subscriber's `Could not bind to queue` error), the code is not the problem. Report the matched output line as evidence and STOP: do not enter the fix loop, and do not change the code. The fix is the developer's broker, credentials, or client permissions. Fix the code only when the output shows a code failure (an exception from the scripts' own logic, or a marker that never appears after a clean connect and bind), up to 3 attempts, then hand the decision to the developer.

**Close every run with the next steps, stated plainly.** Tell the developer, in order: (1) copy `config.example.json` to `config.json` and fill in their real broker values; (2) run the subscriber, then the publisher, with the commands above, and check the markers. Step 4 already wrote the tailored `solace-verification-checklist.md`; invite the developer to add their own items in its Your additional items section and to record any skill-undelivered concerns under Developer-owned items.

### Traps to avoid

- **Improvised verdicts.** A connect that does not raise, or a publish call that returns, proves nothing about the round trip. Judge only by the markers in each script's output.
- **Publishing direct-to-queue instead of pub/sub.** Publish to a topic; let the durable queue carry a topic subscription. See [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md).
- **DIRECT delivery where Guaranteed is required.** Use the persistent publisher for the guaranteed pub/sub journey. See [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md).
- **Seconds instead of milliseconds.** `RetryStrategy.parametrized_retry(retries, retry_interval)` takes its interval in MILLISECONDS per the API reference; a value of `3` means 3 milliseconds. The sample code on the Messaging Service page comments `parametrized_retry(20, 3)` as "over an interval of 3 seconds"; that comment is wrong, and the API reference wins. Whenever a generated script sets a retry interval, write it in milliseconds, as the samples' named constants do.
- **Hardcoded credentials.** Never inline the host, VPN, username, or password. Every script builds its properties through the shared helper.
- **Hardcoded versions.** Resolve the `solace-pubsubplus` version at generation time (Step 3); never carry a version from memory, from an old `requirements.txt`, or from the upstream samples repository.
- **Wildcard imports.** Keep every import explicit, as the samples do; never write `from solace.messaging... import *`.
- **The system interpreter.** Run and check everything with `.venv/bin/python`, so the project uses the version it installed.
- **Running the publisher before the subscriber has provisioned the queue.** A persistent message published before the queue subscribes to its topic is dropped and never received. By default the broker still ACKs it, so the publisher can print `VERIFY: PUBLISH_ACKED` for a message no subscriber gets; the publisher sees a NACK only when the client profile rejects messages that match no subscription. Judge the round trip by `VERIFY: MESSAGE_RECEIVED` in the subscriber output, never by `PUBLISH_ACKED` alone. Run the subscriber first.
- **Treating a sample as a spec that overrides the approved design.** See "Adapting a sample to a variant of its leaf" above.

## Step 6: Tailor the checklist (written at Step 4) and report

Step 4 writes the tailored `solace-verification-checklist.md` into the generated project as generation output; this step defines its content and adds the report in chat. The Python API has no master checklist template yet; until it lands, write the file from the list below. Resolve each item for the generated code: an item the generation did not deliver moves to Your responsibility. Write it as a Markdown task list (`- [ ]`), ticking only what this run actually checked. Each item in the first two groups carries one canonical doc link, as the master JCSMP template does; the round-trip and conformance items carry none because they check skill conformance, not Solace guidance.

**Delivered by this generation**

- Basic-auth connection through the shared `solace_connection_config.py` helper; credentials live only in a gitignored `config.json`. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- Service reconnection and interruption listeners registered before `connect()`. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- Persistent publish to a topic with a publish receipt listener; a NACK is logged with the message id. [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md)
- Capacity-bounded back pressure on the publisher (`on_back_pressure_wait`). [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md)
- Message time-to-live set once on the publisher's message template. [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md)
- Durable queue plus topic subscription provisioned at start (`CREATE_ON_START`), with the access type from the design summary (one durable queue per consuming service on fan-out). [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md)
- Client acknowledgement only after processing. [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md)
- Graceful SIGINT/SIGTERM shutdown: `terminate(grace_period)` and then `disconnect()` on every exit path. [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md)

**Your responsibility (not delivered here)**

- TLS secure session (`tcps://` with certificate validation); Quickstart does not generate it. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- Credentials handled safely: `config.json` is never committed, and credentials are not passed as command-line arguments, where they land in shell history and process listings (the handed-back run commands accept them only for convenience). [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- Authentication stronger than basic username/password (for example a client certificate or Kerberos) where the environment requires it; this journey generates basic username/password only. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- Dead message queue eligibility on published messages and a dead message queue on the broker. [Configuring Dead Message Queues](https://docs.solace.com/Messaging/Guaranteed-Msg/Setting-Dead-Msg-Queues.md)
- Reconnect tuning for HA failover; Quickstart leaves connect and reconnect behavior at the API defaults. [Solace C API Best Practices](https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md)
- Separate projects or deployments per role (skill guidance: a real deployment lifts each script into its own service or project; no Solace doc page states it).
- `respect-ttl` enabled on the queue, without which the broker ignores the message time-to-live. [Configuring Queues](https://docs.solace.com/Messaging/Guaranteed-Msg/Configuring-Queues.md)
- An administrator-provisioned queue, or the endpoint-create permission for this client, in production. [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md)
- A Python version in active or security support and OpenSSL installed on Linux for TLS ([Supported Environments](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-supported-Environments.md)), and no `multiprocessing` in the application ([solace-pubsubplus on PyPI](https://pypi.org/project/solace-pubsubplus/)).

**Verified by the round-trip** (developer-run until the Python verify script lands)

- Every subscriber prints `VERIFY: CONNECTED` and `VERIFY: QUEUE_BOUND`.
- The publisher prints `VERIFY: CONNECTED` and `VERIFY: PUBLISH_ACKED`, then exits with status 0.
- Every subscriber prints `VERIFY: MESSAGE_RECEIVED` (`PUBLISH_ACKED` alone does not prove delivery).
- Ctrl-C on each subscriber prints `Shutdown signal received` and it exits with status 0.

**Generation conformance**

- The disclaimer header is the first two lines of every generated `.py` file (Step 5 header check).
- `requirements.txt` pins the `solace-pubsubplus` version that PyPI reported as `info.version` at generation time, or the developer's version when PyPI was unreachable (Step 5 drift check).
- This tailored checklist exists at the project root (Step 5 checklist check).
- `py_compile` and the import smoke passed (Step 5), with the result recorded here.
- Comments follow their code (Step 4).
- Every page named in the design summary's Grounding docs field was WebFetched in this session (`none fetched` is an honest value; an unfetched citation is not).
- Verification: record `verify script not yet bundled for Python; round trip handed back`, or `manual run` with the observed markers when the developer asked you to run it.

**Developer-owned items (not delivered by this skill)**: any item the skill does not deliver, recorded as developer-owned rather than skill-satisfied. A consented deviation is recorded here.

**Your additional items**: open space for the developer to keep growing the list.

The emitted checklist is an artifact, not source, so it carries NO disclaimer header. Report the same resolution in chat: what landed under each group, the compile, import, and conformance results, and that the round trip is handed back.

## Grounding references

Live `docs.solace.com` `.md` pages (WebFetch on demand):

- [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md)
- [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md)
- [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md)
- [Supported Environments](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-supported-Environments.md)
- [Solace C API Best Practices](https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md)

Public URLs (not docs.solace.com doc pages; use the live URL directly):

- [Python API reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/python/index.html)
- [Python API Release Notes](https://products.solace.com/download/PYTHON_API_RN)
- [solace-pubsubplus on PyPI](https://pypi.org/project/solace-pubsubplus/)
