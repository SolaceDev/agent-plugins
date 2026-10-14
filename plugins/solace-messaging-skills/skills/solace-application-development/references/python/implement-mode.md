# Implement Mode

Generate a complete, runnable Python project from a confirmed design summary. The canonical output is TWO scripts (a publisher and a subscriber, or a requestor and a replier) plus the shared connection-config helper in ONE project directory with its own virtual environment. Each script is a near-verbatim adaptation of its reference sample. Near-verbatim adaptation of two samples is more reliable than merging them into one program, and a real deployment lifts each script into its own service or project.

Implement mode is NOT only for the canonical shape. A web app, a dashboard, a service, or any embedded shape where the Python messaging layer lives inside a larger application follows the SAME steps: the same door question, the same leaf wiring for the messaging layer, the same generation outputs, and the same verification fallback. Building an off-catalog shape never waives a step in this file.

Work through the steps in order. Read this file's grounding links only as each step needs them. Link the canonical doc and state the decision in one line, never paraphrase doc content. Resolve every dependency version at generation time; pin nothing in skill content (SKILL.md Invariant 3).

**Coverage in this release.** Implement mode generates all five Python leaves on all three doors and verifies them with the bundled `scripts/verify.sh` (Step 5).

## Generation outputs: the file contract

Every Implement run writes ALL of these as generation output, on every app shape. None of them is gated on consent, and none of them waits for the end of the session:

1. The generated scripts for the chosen leaf, plus the shared `solace_connection_config.py` helper. Every source file starts with the AI-assisted disclaimer header (Step 4).
2. `requirements.txt` with the `solace-pubsubplus` version resolved per Step 3.
3. `config.example.json` (placeholders) and a `.gitignore` that ignores `config.json` and the virtual environment (Step 4).
4. A tailored `solace-verification-checklist.md` at each project root (tailoring rules in Step 6; the WRITE happens here at generation time, like the scripts).
5. A copy of the bundled Python `scripts/verify.sh` at each project root, plus a generated `verify-hooks.sh` beside it (Step 5), so the verification commands resolve from inside the project.

Copy the Python `verify.sh` from `references/python/scripts/`, never the JCSMP one: the JCSMP script drives Maven and cannot run Python scripts.

## Step 0: Ask Quickstart, Solace Suggested, or Custom, before any broker details

This is the FIRST question, and you ask it before you request or accept ANY broker connection details. Ask the developer plainly which of three doors they want: Quickstart, Solace Suggested, or Custom. Ask it as a direct question and wait for the answer. Frame the three doors honestly: Quickstart is a learning and try-it path to see a round-trip work, NOT a deployment target; Solace Suggested is the hardened baseline Solace suggests as a starting point (TLS, dead message queue eligibility, HA failover, separate projects), and Solace suggests it without guaranteeing it is ready for deployment; Custom lets the developer tick exactly which hardening knobs to include and generates that ticked subset and nothing more. The answer decides WHERE broker connection details belong, and it drives behavior for the rest of Implement mode. Every door generates the same scripts for the chosen leaf plus the shared `solace_connection_config.py` helper; they differ in where the credential VALUES live: Solace Suggested puts them ONLY in a gitignored `config.json`, while Quickstart reads `config.json` when present and otherwise falls back to command-line arguments; Custom follows the Quickstart convention by default, and `custom-mode.md` says when it drops the command-line fallback.

Three things do NOT answer this question, and none of them waives it:

- **An existing `config.json`**, even one that already holds real credentials. Ask anyway.
- **A running local broker discovered in the environment.** Report what you found, then ask both this question and which broker to target.
- **An app shape larger than the generated two-script project** (a web app, a dashboard, a service). The doors still govern the messaging layer and the credential placement. Ask anyway.

The doors:

- **Quickstart.** Each script builds its connection through the shared `solace_connection_config.py` helper, which reads the connection properties from a `config.json` in the working directory when present and otherwise falls back to the command-line arguments (`<host:port> <message-vpn> <client-username> [password]`). Quickstart adds no TLS hardening and has no TLS requirement: it connects with whatever scheme the developer's host carries, and a plaintext `tcp://` dev broker is a normal Quickstart target. A `tcps://` host (the Solace Cloud default) needs a trust store even on Quickstart, because the Python API has no default one and fails at `build()` with "Failed to load trust store" without it. Step 1 asks the developer whether the broker URL is plaintext or secure; when the answer is secure (`tcps://`), give the developer the trust store explanation from `solace-suggested-mode.md` Step 1 and have them set `solace.messaging.tls.trust-store-path` in `config.json`; the helper passes that key through unchanged, and the command-line fallback cannot carry it, so a `tcps://` host runs from `config.json`. Quickstart adds no validation code: the API defaults already validate the certificate. Never downgrade a `tcps://` host to `tcp://` to avoid the trust store: the plaintext port may be closed, the port differs, and the password would cross the network in clear text. Generate the scripts without per-stage preview gates. Quickstart output fails fast (the fail-fast rule in Step 4).
- **Solace Suggested.** On the Solace Suggested path, read `solace-suggested-mode.md` and follow the Solace Suggested overlay (TLS with the developer's trust store, DMQ eligibility on persistent publishes, HA failover, separate projects, and the `config.json` convention); the leaf and sample mechanics, the Step 4 dispatch, and the fixed `VERIFY:` contract stay here in implement-mode.md and its leaf files. Solace Suggested is TLS-secure by definition: the session is `tcps://` with server-certificate validation, and it REQUIRES a broker that already has TLS configured (Solace Cloud has it out of the box) plus a trust store directory that holds the broker's CA certificate, which the developer supplies (`solace-suggested-mode.md` Step 1 explains it to the developer). There is no non-secure variant of this door and no sub-question to ask; the door question stays a clean three-way choice. When the developer's target broker has no TLS configured (for example, a local dev container), say plainly that Solace Suggested requires a TLS-capable broker and offer the two honest alternatives: Custom with the equivalent knobs ticked minus the secure session, or Quickstart for a learning run. Never generate a plaintext session under the Solace Suggested name. The overlay is leaf-agnostic: it rides on whatever `Pattern` leaf the design summary chose and only adds the Solace Suggested deltas. It is what Solace suggests as a hardened baseline, not a guarantee that the output is ready for deployment.
- **Custom.** On the Custom path, read `custom-mode.md` and follow it. Custom opens a SINGLE up-front multi-select checklist of the applicable hardening knobs, captured once, not a sequence of per-knob questions; generation then applies exactly the ticked subset and nothing more. Nothing ticked lands a Quickstart-shaped base app, everything ticked lands about the Solace Suggested shape, and each ticked knob layers one Suggested delta on top of that floor. `custom-mode.md` owns the leaf-aware checklist content and the knob application; the leaf and sample mechanics, the Step 4 dispatch, and the fixed `VERIFY:` contract stay here in implement-mode.md and its leaf files, exactly as they do on the Solace Suggested path.

State plainly that a real deployment lifts each script into its own service or project. Every door generates the scripts in one project, unless the developer opts into separate projects on Solace Suggested or Custom. Basic username/password is the only auth this journey generates; ground it in the authentication section of [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md).

## Step 1: Confirm broker access and grounding

With the door chosen, confirm the developer has access to a reachable broker, and confirm WHICH broker they want to target. Confirm that they HAVE one; do NOT ask them to paste connection details into the chat. When Design mode already confirmed broker access in this session (its Step 0), do not re-ask whether a broker exists; ask only WHICH broker to target. A broker discovered in the environment is a fact to report while asking, never an answer. Where the credential VALUES live depends on the Step 0 door: for Solace Suggested they go ONLY into the gitignored `config.json`; for Quickstart they reach the app through a `config.json` when present, otherwise as command-line arguments at run time. The skill itself never needs the values typed into the chat.

**On Quickstart, and on Custom with the secure-session knob unticked, also ask the scheme.** Ask in one direct question: "Is your broker URL plaintext (`tcp://`) or secure (`tcps://`)?" The scheme is not a credential, so asking it never conflicts with the rule against connection details in the chat; Solace Cloud URLs are `tcps://` by default. An existing `config.json` or a discovered broker is a fact to report, never the answer: still ask. The answer decides the Quickstart trust store guidance (Step 0) and the `config.example.json` placeholder (Step 4). Do not ask it on Solace Suggested, which is always `tcps://`, or on Custom with the secure-session knob ticked, which is always `tcps://`.

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
| `Direct Pub/Sub` | `implement-direct-pubsub.md` | `python-direct-publisher-sample.py` + `python-direct-subscriber-sample.py` |
| `Request-Reply (Direct)` | `implement-request-reply.md` | `python-direct-requestor-blocking-sample.py` (or `python-direct-requestor-sample.py` on the non-blocking variant) + `python-direct-replier-sample.py` |

**Every leaf also generates the shared connection helper.** Read `python-solace-connection-config.py` and generate it as `solace_connection_config.py` (a module name needs underscores). Each script builds its connection dict by calling `load_service_properties(args, APP_NAME)` from that module and passes it to `MessagingService.builder().from_properties(...)`, rather than setting the host, VPN, username, or password inline.

**Fixed file names.** Generate each script under the name its sample's docstring gives, next to `solace_connection_config.py`: `guaranteed_publisher.py` and `guaranteed_subscriber.py` (the fan-out leaf adds one subscriber script per further consuming service, named in `implement-guaranteed-pubsub.md`), `direct_publisher.py` and `direct_subscriber.py`, and `direct_replier.py` with `direct_requestor_blocking.py` (or `direct_requestor.py` on the non-blocking variant). `verify.sh` (Step 5) finds the scripts by these names; the names of functions, constants, and the `APP_NAME` value inside each script stay your discretion.

**Disclaimer header on EVERY generated source file.** Stamp the top of each generated `.py` file with these two comment lines, before the module docstring and before any import:

```python
# AI-assisted code. Review before production use.
# See the verification checklist: solace-verification-checklist.md
```

Stamp the header AT WRITE TIME, as the first content of each new file. `requirements.txt`, `config.example.json`, and `.gitignore` are not source and carry no header. The reference samples themselves carry NO disclaimer. This rule is SKILL.md Invariant 4.

**Embedded and web-app shapes.** When the Python messaging layer sits inside a larger application, the chosen leaf's wiring still governs the messaging layer: the same connection helper, the same service, receiver, and publisher listeners, the same `VERIFY:` markers emitted through a stdout-reaching `trace(...)`, and the disclaimer header on every generated file, messaging or not. The `VERIFY:` markers are NOT demo harness; they stay in the messaging layer whatever the shape, because the `app` verify stage (Step 5) greps for them in the app's captured output.

**Write the verification artifacts in this same step.** Alongside the scripts, write the tailored `solace-verification-checklist.md` at each project root (Step 6), `config.example.json`, `.gitignore`, a copy of the bundled `scripts/verify.sh` at each project root, and the generated `verify-hooks.sh` (Step 5 defines its contract). A session that ends early still leaves the checklist and the verify entry points in the project. Make the `verify.sh` copy with a read-and-redirect, `cat "<skill directory>/references/python/scripts/verify.sh" > verify.sh && chmod +x verify.sh`, where the skill directory is the one announced when this skill loaded. Do not use `cp` on the bundled file: Claude Code protects the plugin directory and blocks a `cp` that names a file inside it, and a blocked copy pushes the script through a retype that must stay byte-identical. Never edit the bundled script itself.

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

On the Quickstart door ONLY, do not carry the samples' reconnect-tuning block into the generated scripts. The samples set a reconnection budget (`with_reconnection_retry_strategy(RetryStrategy.parametrized_retry(...))` with the `RECONNECT_RETRIES` and `RECONNECT_RETRY_INTERVAL_MS` constants) and carry an HA failover comment block; Quickstart generation OMITS the builder call, the two constants, the HA comment block, and the then-unused `RetryStrategy` import, leaving connect and reconnect behavior at the API defaults, so a wrong host, port, or credential in this learning setup fails immediately instead of retrying. Drop ONE short comment at the `MessagingService.builder()` site in each generated script stating that posture: connect and reconnect settings are at the API defaults so failures surface immediately; see the [Solace C API Best Practices](https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md) for reconnect tuning beyond Quickstart. The Solace Suggested door is unchanged: it keeps the samples' baseline or upgrades it per `solace-suggested-mode.md` Step 2. Custom follows its floor exception in `custom-mode.md`.

### Scaffold the config files

Generate two small project files next to the scripts (on Solace Suggested, on Custom when the secure-session knob is ticked, and on Quickstart or on Custom with the secure-session knob unticked when the Step 1 answer is secure, use the `config.example.json` from `solace-suggested-mode.md` Step 0 instead, with its `tcps://` placeholder host and its trust store key):

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

## Step 5: Check the project, then verify with verify.sh

Verification is a `verify.sh` run, on every door and every app shape. Step 4 copied `scripts/verify.sh` into each project root and generated `verify-hooks.sh` beside it, so every command below resolves from inside the project. An improvised check (curl against the app's own HTTP API, a manual browser click) may TRIGGER traffic, but it NEVER renders the verdict: the verdict is the `VERIFY:` markers plus the script's exit code, which is what separates "your code is broken" (exit 1) from "your broker, credentials, or trust store are wrong" (exit 2). Never fabricate a run result.

**Live-broker heads-up (every run, every launcher).** Immediately before starting ANY process that will connect to a live broker (a `verify.sh` stage, a `.venv/bin/python` run, a launcher script), print ONE line that names the broker host and states the actual broker-side effects of this run: the durable queues the subscribers provision, the connections it opens, and the messages or requests the foreground role sends, scaled to the generated scripts (SKILL.md Invariant 6).

### Check first: compile, import, and conformance

Run these checks on every run, before any `verify.sh` stage. On the compile-only fallback below they are the whole check.

1. **Compile check.** Run `.venv/bin/python -m py_compile` on every generated `.py` file. A failure is a code failure: fix it and re-run, up to 3 attempts, then report the evidence and hand the decision to the developer.
2. **Import smoke.** From the project root, run `.venv/bin/python -c "import solace_connection_config, guaranteed_publisher, guaranteed_subscriber"` with the module names of the chosen leaf (for example `direct_publisher, direct_subscriber` or `direct_replier, direct_requestor_blocking`), adding every further generated module (for example each fan-out subscriber). On separate projects, run the checks in each project root against that project's modules. Each script keeps its `if __name__ == "__main__":` guard, so the import resolves every `solace.messaging` name the scripts use without connecting to a broker. A failure (for example an `ImportError` on a misspelled module path) is a code failure under the same fix loop.
3. **Conformance checks.** From the project root, run these three checks and record each result in the checklist. A missing header or a missing checklist is a code failure under the same fix loop; a version mismatch is reported to the developer, not fixed silently. The `verify.sh` preflight repeats them as warnings on every run.

   ```bash
   # every generated .py file starts with the disclaimer header
   for f in *.py; do head -n 1 "$f" | grep -qxF '# AI-assisted code. Review before production use.' || echo "MISSING HEADER: $f"; done
   # the tailored checklist exists
   test -f solace-verification-checklist.md || echo "MISSING CHECKLIST"
   # the pin matches the release PyPI reports now (skip and record it when PyPI is unreachable)
   grep -qxF "solace-pubsubplus==$(curl -s https://pypi.org/pypi/solace-pubsubplus/json | python3 -c 'import json, sys; print(json.load(sys.stdin)["info"]["version"])')" requirements.txt || echo "VERSION DRIFT"
   ```

### The canonical two-script shape: the fixed stages

`verify.sh` checks every script with `py_compile`, starts the long-running role FIRST with the project's own `.venv` interpreter (the subscriber, so the durable queue or the topic subscription exists before the first publish; the replier, so the request topic subscription exists before the first request), waits for its readiness marker, runs the foreground role and lets it exit on its own, watches each process's own output for the stage's `VERIFY:` marker, sends SIGINT to the long-running role to exercise its graceful shutdown, and returns an exit code that classifies the outcome. It finds the scripts by the fixed file names of Step 4, so never rename a generated script.

| `Pattern` leaf | Stages, in order | Each stage waits for |
|----------------|------------------|----------------------|
| The three Guaranteed Pub/Sub leaves | `consumer`, then `publisher`, then `roundtrip` | `VERIFY: QUEUE_BOUND` in the subscriber output; `VERIFY: PUBLISH_ACKED` in the publisher output (this stage starts the subscriber first, so it needs both scripts); `VERIFY: MESSAGE_RECEIVED` in the subscriber output |
| `Direct Pub/Sub` | `direct` | `VERIFY: SUBSCRIBED` in the subscriber output as the readiness gate, then at least one `VERIFY: MESSAGE_RECEIVED` from the publisher's short burst |
| `Request-Reply (Direct)` | `direct-request-reply` | `VERIFY: SUBSCRIBED` in the replier output as the readiness gate, then `VERIFY: REPLY_RECEIVED` in the requestor output and `VERIFY: REQUEST_RECEIVED` in the replier output |

The `direct-request-reply` stage runs `direct_requestor_blocking.py`, or `direct_requestor.py` when the project has the non-blocking variant instead. The Python API has no guaranteed request-reply, so there is no `guaranteed-request-reply` stage. On the fan-out leaf, the stages drive `guaranteed_subscriber.py` only; hand back one command per further subscriber script (`guaranteed_subscriber_<service>.py`), each started in its own terminal before the publisher, and tell the developer that each prints its own `VERIFY: MESSAGE_RECEIVED`.

Invoke each stage from the project root in one of these forms:

- **With `config.json`** (always the form for a `tcps://` host, because the trust store path has no argument form): `./verify.sh <stage>`. Each script reads `config.json` from the working directory.
- **With connection arguments** (a plaintext host and no `config.json`): `./verify.sh <stage> <host:port> <vpn> <user> [password]`, the same contract the scripts' command-line fallback uses.
- **On separate projects** (Solace Suggested, or the Custom decoupled-projects knob): `./verify.sh <stage> --subscriber-dir <subscriber-project> --publisher-dir <publisher-project>` on the pub/sub leaves, or `./verify.sh direct-request-reply --replier-dir <replier-project> --requestor-dir <requestor-project>`. Run it from either project; the directories are relative to the working directory. It passes NO connection details on the command line: each script runs from inside its own project and reads that project's `config.json`, with that project's `.venv`.

### Every other shape: the `app` stage and `verify-hooks.sh`

A web app, an embedded service, or any single-process shape that the fixed stages cannot drive is verified with the `app` stage: `./verify.sh app`. The stage sources the generated `verify-hooks.sh`, which carries the ONLY app-specific facts (how to start the app and how to cause one publish), while the script keeps the universal logic: marker watching, timeouts, environment-signature classification, and the 0/1/2 exit contract. Generation writes `verify-hooks.sh` because the generator just wrote the app and knows both commands:

```bash
# verify-hooks.sh: generated with the project; verify.sh's app stage sources it.
START_CMD='.venv/bin/python app.py'                                        # starts the app (long-running)
TRIGGER_CMD='curl -s --max-time 10 -X POST localhost:8081/api/orders -d "{\"demo\":1}"'  # causes exactly one publish
READY_MARKER='VERIFY: QUEUE_BOUND'          # marker proving the consuming side is live
PASS_MARKER='VERIFY: MESSAGE_RECEIVED'      # marker proving the round trip
```

Generate `verify-hooks.sh` with ONLY these four assignments. The app stage imports exactly these four names from a child shell and ignores everything else in the file; keep the file to the four lines all the same. Give a network trigger its own timeout (the `--max-time` above): the stage also bounds the trigger and force-kills one that never returns, but a self-timing trigger leaves cleaner evidence. Set `READY_MARKER` and `PASS_MARKER` to the chosen leaf's own markers. On the canonical two-script shape, generate `verify-hooks.sh` too, carrying the classic commands (for example `START_CMD='.venv/bin/python guaranteed_subscriber.py'` and `TRIGGER_CMD='.venv/bin/python guaranteed_publisher.py'`), so the file documents the same contract everywhere; the fixed stages do not read it.

The `app` stage starts `START_CMD` in the background, waits for `READY_MARKER` in the app's captured output, runs `TRIGGER_CMD` under a bounded deadline, waits for `PASS_MARKER`, then stops the app with SIGINT and classifies. A curl inside `TRIGGER_CMD` is exactly the right use of curl: it triggers, and the markers judge.

### Interpret the exit code

- **Exit 0 (stage passes).** The stage milestone appeared, the long-running role printed `Shutdown signal received`, and it exited with status 0. Advance to the next stage.
- **Exit 1 (code failure).** `py_compile` failed, a marker never appeared, a process died before its milestone, the shutdown line did not print, or a run hung and had to be force-killed. Enter the bounded fix loop below.
- **Exit 2 (environment failure).** A Solace Python API connection, authentication, or TLS signature appeared (the broker is unreachable, the credentials or VPN are wrong, or the trust store is missing or holds the wrong CA), or the subscriber could not bind its queue because the client may not create it (`Could not bind to queue`). STOP. Do NOT enter the fix loop. The code is not the problem.

**Bounded fix loop (exit 1 only).** Diagnose the captured output, fix the code, and re-run the same stage, up to 3 automatic attempts per stage. After the third failure, stop fixing: summarize exactly what you tried and what the captured output shows, then hand the decision to the developer. Fixes apply without a confirmation gate, on every door.

**Environment-failure path (exit 2).** Report the matched line from the captured output as evidence, and do NOT modify the code. The fix is the developer's broker, credentials, trust store, or client permissions.

### When to run verify.sh, and when to hand it back

**Run it from `config.json` with real values.** Run the stages for real, with NO confirmation prompt, ONLY when a `config.json` is present at every project root involved AND its values are real, meaning they are NOT the `config.example.json` placeholders (`tcp://HOST:55555` or `tcps://HOST:55443`, `YOUR_VPN`, `YOUR_USERNAME`, `YOUR_PASSWORD`, `PATH_TO_TRUST_STORE_DIRECTORY`). Filling in real values is the opt-in; a `config.json` that still carries a placeholder is not. Use the `config.json` form of the stage command (single project) or the separate-projects form, and run the leaf's stages in the table's order. Print the live-broker heads-up immediately before the run, naming the host from `config.json`; the real-values `config.json` is the consent, the heads-up is not a prompt. The developer can also ask you to run `verify.sh` with connection arguments they give for a plaintext host.

**Honesty rule (no fabricated runs).** When `config.json` is absent or still holds placeholders, do NOT run the scripts and do NOT fabricate a result. Report the compile, import, and conformance results, and hand back the exact `verify.sh` commands for the developer to run from the project root, for example on the Guaranteed Pub/Sub leaves:

```
./verify.sh consumer
./verify.sh publisher
./verify.sh roundtrip
```

Add the connection arguments only for a plaintext host with no `config.json`, and never on a path that drops the command-line fallback. On separate projects, hand back the separate-projects form. For an `app`-stage shape, the handed-back command is `./verify.sh app` after the developer fills in the app's config.

**Close every run with the next steps, stated plainly.** Tell the developer, in order: (1) copy `config.example.json` to `config.json` (in each project on separate projects) and fill in their real broker values; on any `tcps://` host (always on Solace Suggested and with the Custom secure-session knob, and on Quickstart or on Custom with the secure-session knob unticked when the Step 1 answer was secure), also put the broker's CA certificate into a trust store directory and set `solace.messaging.tls.trust-store-path` to it, as `solace-suggested-mode.md` Step 1 explains. On a Quickstart run, or a Custom run with the secure-session knob unticked, whose Step 1 answer was plaintext, still add one line: if the host later changes to `tcps://`, the project needs a trust store directory set in `solace.messaging.tls.trust-store-path` in `config.json`, or the connection fails with "Failed to load trust store"; (2) then just ask you to verify, and you will run `verify.sh` for them against their broker (naming the host and side effects first). Make the fill-in-`config.json`-then-ask-me path the recommended one; if they would rather run it themselves, point them at the handed-back commands. Step 4 already wrote the tailored `solace-verification-checklist.md`; invite the developer to add their own items in its Your additional items section and to record any skill-undelivered concerns under Developer-owned items.

### Traps to avoid

- **Improvised verdicts.** A connect that does not raise, a publish call that returns, or a curl against the app's own API proves nothing about the round trip. Trigger with whatever fits; judge ONLY by the markers and the `verify.sh` exit code.
- **Publishing direct-to-queue instead of pub/sub.** Publish to a topic; let the durable queue carry a topic subscription. See [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md).
- **DIRECT delivery where Guaranteed is required.** Use the persistent publisher for the guaranteed pub/sub journey. See [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md).
- **Seconds instead of milliseconds.** `RetryStrategy.parametrized_retry(retries, retry_interval)` takes its interval in MILLISECONDS per the API reference; a value of `3` means 3 milliseconds. The sample code on the Messaging Service page comments `parametrized_retry(20, 3)` as "over an interval of 3 seconds"; that comment is wrong, and the API reference wins. Whenever a generated script sets a retry interval, write it in milliseconds, as the samples' named constants do.
- **Hardcoded credentials.** Never inline the host, VPN, username, or password. Every script builds its properties through the shared helper.
- **Hardcoded versions.** Resolve the `solace-pubsubplus` version at generation time (Step 3); never carry a version from memory, from an old `requirements.txt`, or from the upstream samples repository.
- **Wildcard imports.** Keep every import explicit, as the samples do; never write `from solace.messaging... import *`.
- **The system interpreter.** Run and check everything with `.venv/bin/python`, so the project uses the version it installed.
- **Running the publisher before the subscriber has provisioned the queue.** A persistent message published before the queue subscribes to its topic is dropped and never received. By default the broker still ACKs it, so the publisher can print `VERIFY: PUBLISH_ACKED` for a message no subscriber gets; the publisher sees a NACK only when the client profile rejects messages that match no subscription. Judge the round trip by `VERIFY: MESSAGE_RECEIVED` in the subscriber output, never by `PUBLISH_ACKED` alone. Run the subscriber first; `verify.sh` enforces that order.
- **Treating a sample as a spec that overrides the approved design.** See "Adapting a sample to a variant of its leaf" above.

## Step 6: Tailor the checklist (written at Step 4) and report

Step 4 writes the tailored `solace-verification-checklist.md` into the generated project as generation output; this step defines its tailoring and adds the report in chat. Read `verification-checklist.md` (the master template) and resolve each item for the generated code: keep only the items for the chosen leaf (a leaf in parentheses marks a leaf-specific item), and move an item the generation did not deliver to Your responsibility. On Solace Suggested, and for each ticked Custom knob, an item under Your responsibility that the door or the knob delivered (the TLS secure session, DMQ eligibility on published messages, separate projects, and the HA reconnect values only when the HA-failover question was answered yes) moves to Delivered by this generation. The broker-side dead message queue always stays under Your responsibility, because the skill never provisions it. Write it as a Markdown task list (`- [ ]`), ticking only what this run actually checked. Keep the master template's groups and doc links, drop its template introduction and the leaf names in parentheses from the emitted copy, and keep its two closing sections (Developer-owned items, where a consented deviation is recorded, and Your additional items). On separate projects, write ONE copy into EACH project root.

The emitted checklist is an artifact, not source, so it carries NO disclaimer header. Report the same resolution in chat: what landed under each group and the compile, import, and conformance results. Record each `verify.sh` stage that ran and its exit code in the checklist's Generation conformance group; on the compile-only fallback, record that the run was handed back instead.

## Grounding references

Live `docs.solace.com` `.md` pages (WebFetch on demand):

- [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md)
- [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md)
- [Publishing Direct Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-DM-Publish.md)
- [Consuming Direct Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-DM-Receive.md)
- [Request-Reply Messaging in the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Request-Reply.md)
- [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md)
- [Supported Environments](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-supported-Environments.md)
- [Solace C API Best Practices](https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md)

Public URLs (not docs.solace.com doc pages; use the live URL directly):

- [Python API reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/python/index.html)
- [Python API Release Notes](https://products.solace.com/download/PYTHON_API_RN)
- [solace-pubsubplus on PyPI](https://pypi.org/project/solace-pubsubplus/)
