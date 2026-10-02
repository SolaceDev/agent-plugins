# Python API

The Python entry file of the Solace API development skill. The Solace Messaging API for Python is published on PyPI as `solace-pubsubplus`. This file detects the developer's intent and selects the right Python mode; the detailed guidance lives in the lazy-loaded mode files under `python/`.

The cross-cutting invariants (content sourcing, WebFetch-on-demand doc grounding, and no hardcoded versions) are owned by the umbrella `SKILL.md`. Apply them here; this file does not restate them. The Python-specific points below are the package to depend on and the canonical pages to ground in.

## Mode Detection

Determine the user's intent and enter the appropriate mode. Routing is keyed on the design contract, not on the phrasing of the request: Implement mode never starts without a confirmed design (the design-contract gate below), so a "build me..." prompt is NOT an Implement signal by itself.

| User intent | Mode | What to do |
|---|---|---|
| "Help me choose the Solace messaging pattern for a new Python app" / "Topic or queue?" / "What delivery semantics should I use?" | **Design** | Read `python/design-mode.md` |
| Build or generate a NEW app or messaging component with NO valid design contract yet ("Build me a Solace Python publisher/consumer", "Generate a Python project with solace-pubsubplus", a web app / dashboard / service, or any embedded shape where the Python messaging layer lives inside a larger application) | **Design first** | Read `python/design-mode.md`. A prompt that already states the design resolves on its fully-specified path in ONE confirm; a bare prompt walks the tree. The confirmed summary then feeds Implement. |
| A build request WITH a valid design contract (see the design-contract gate below) | **Implement** | Read `python/implement-mode.md` |
| An edit to an EXISTING app that changes the messaging topology (the topology rule below) | **Design first** | Re-enter `python/design-mode.md` to re-confirm the affected summary fields, then Implement applies the change |
| A mechanical edit to an EXISTING app with no topology change (reconnect handling, payload format, logging, renames, ack tuning) | **Implement** | Read `python/implement-mode.md`; no design pass |
| "My Python app is throwing on connect" / "Why is my receiver not binding the queue?" | **Debug** | Debug mode is not yet available in this release. Redirect the user to the canonical Solace Python API documentation: [Python API Home](https://docs.solace.com/API/Messaging-APIs/Python-API/python-home.md). The redirect is the ENTIRE debug answer: acknowledge the problem in one line, link the page, and stop. Do not generate debugging guidance from memory. That prohibition covers root-cause hypotheses, likely causes, interim checks or "things worth checking in the meantime", workarounds, and configuration or code suggestions. Restating this rule does not license adding such guidance after the redirect. |

If unclear, default to **Design**. Understand the messaging problem before generating code.

**The topology rule** decides between the two existing-app rows. An edit changes the messaging topology when it changes the structure the design summary records: a new app or messaging component (a new publisher, receiver, requestor, or replier, including a leaf change such as single-service growing into fan-out), a pattern change, a delivery-mode change, a consumption-endpoint change, or a queue access-type change. That list is the core; also treat an unlisted change as topology when your judgment says it alters the message flow between the apps and the broker. Everything else is a mechanical edit.

Three gates hold on every path:

- **The design-contract gate.** Implement mode never starts without a design contract, and never invents design values. Exactly three sources satisfy it: (1) a summary Design mode confirmed in this session: use it as-is, with no re-confirm; (2) an explicit summary the developer supplied in chat (the eight fields or an equivalent statement of them): use it directly; (3) a saved `solace-design.md` in the project: restate it in one line and proceed on confirm. A prose build request that merely mentions pattern details is NOT a contract: route it through Design mode, whose fully-specified path derives the summary, echoes it, and takes one confirm. The one carve-out is the mechanical-edit row above. An experienced developer therefore never has to walk the design tree, but never skips the contract either.
- **Implement mode opens with a mandatory door question.** Ask Quickstart, Solace Suggested, or Custom, before you request or accept ANY broker details (`python/implement-mode.md` Step 0). Ask it even when a `config.json` with credentials already exists, even when a local broker is already running, and even when the requested app is bigger than the canonical generated shape; none of those answers the question. A confirmed design summary does not answer it either: the door question always follows the design contract, never merges into it.
- **Environment discovery never answers a question.** A running broker container, an existing config file, or found credentials are facts to report, not answers to consume. Report what you found, then still ask which broker the developer wants to target.

## Python package

The single Solace dependency for this API is the PyPI package `solace-pubsubplus`, imported as `solace.messaging`. Resolve the latest release at generation time (the no-hardcoded-versions invariant); never pin a `solace-pubsubplus` version in skill content. Resolve it from the authoritative PyPI JSON API with exactly this command, and read `info.version`:

```bash
curl -s https://pypi.org/pypi/solace-pubsubplus/json \
  | python3 -c 'import json, sys; print(json.load(sys.stdin)["info"]["version"])'
```

The package's `requires_python` value on PyPI is an install floor, not a support statement. Solace supports only the Python versions that are in active support and also have security update support (see `python/prerequisites.md`).

## Verify script

The Python API does not bundle a verify script yet. Every Implement run ends in the compile-only fallback that `python/implement-mode.md` Step 5 defines, and never claims a verified run (SKILL.md Invariant 5).

## Canonical doc links

Python-specific grounding. Each page below is a live `docs.solace.com` `.md` URL, WebFetched on demand; the live exceptions are listed separately because they have no `docs.solace.com` `.md` form.

- [Python API Home](https://docs.solace.com/API/Messaging-APIs/Python-API/python-home.md): entry point for the Python API.
- [Python API Developer Guide](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Dev-Guide.md): the index of the developer guide pages.
- [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md): building and connecting the `MessagingService`.
- [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md) and [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md): the guaranteed pub/sub pages.
- [Request-Reply Messaging in the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Request-Reply.md): request-reply in the Python API, which uses direct messages only.
- [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md): queue provisioning from the client.
- [Supported Environments](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-supported-Environments.md): Python versions, platforms, and OpenSSL.
- [Solace C API Best Practices](https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md): the Python API has no best practices page of its own. It wraps the C API, so the reference samples ground their callback, reconnect, acknowledgement, and time-to-live practices here.
- For the broader Solace grounding, see the shared [Solace Core Concepts](https://docs.solace.com/Get-Started/event-mesh-basics.md) page.

Live exceptions (these have no `docs.solace.com` `.md` form; use the live URL directly):

- [Python API reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/python/index.html) (Sphinx HTML, one large page per sub-package)
- [Python API Release Notes](https://products.solace.com/download/PYTHON_API_RN)
- [solace-pubsubplus on PyPI](https://pypi.org/project/solace-pubsubplus/) (its project description carries the `multiprocessing` restriction, which no `docs.solace.com` page states)

## Mode and reference files (read on-demand only)

- `python/prerequisites.md`: broker acquisition and the supported environments (route here first if the developer has no reachable broker).
- `python/design-mode.md`: choose and confirm the messaging pattern and topology before any code is generated; every new build without a design contract routes here first.
- `python/implement-mode.md`: generate a runnable Python project from a confirmed design; its Step 4 dispatches onto the per-leaf wiring files below.
- `python/implement-guaranteed-pubsub.md`: the Guaranteed Pub/Sub leaf wiring (read from implement-mode Step 4).
- `python/python-guaranteed-publisher-sample.py`: best-practices publisher sample (basic-auth connect, PERSISTENT publish to a topic, publish receipt handling, graceful shutdown).
- `python/python-guaranteed-subscriber-sample.py`: best-practices receiver sample (provisions a durable queue plus a topic subscription, CLIENT-ack receiver).
- `python/python-solace-connection-config.py`: the shared connection-config helper every generated app uses.
- `python/python-direct-*-sample.py`: the direct pub/sub and direct request-reply samples. Implement mode does not generate those leaves yet (see `python/implement-mode.md` Step 4).
