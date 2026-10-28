# Verification Checklist

This checklist records what the chosen door delivered and what remains the developer's responsibility. Each item is one binary (yes/no) check. Each item in the first two groups carries one canonical doc link; read the linked doc to decide, because the check is not a substitute for the doc. The round-trip and conformance items carry no link, because they check skill conformance, not Solace guidance.

This file is the master template. Every Implement run emits a per-project copy into the generated project at generation time (`implement-mode.md` Step 4), tailored to the chosen door, the chosen leaf, and the code that was generated (`implement-mode.md` Step 6 defines the tailoring). Nothing here implies the generated output is ready to carry real traffic. A leaf or a `Threading` value in parentheses marks a shape-specific item: keep it only for that leaf and that `Threading` value (an item with no `Threading` value applies on both shapes). An item the generation did not deliver moves to Your responsibility, and an item under Your responsibility that the door or a ticked Custom knob delivered moves to Delivered by this generation.

## Delivered by this generation

- [ ] Basic-auth connection through the shared `solace_connection_config.py` helper; credentials live only in a gitignored `config.json`. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- [ ] Service reconnection and interruption listeners registered before `connect()`. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- [ ] (Guaranteed Pub/Sub, Threading Async) Persistent publish to a topic with a publish receipt listener; a NACK is logged with the message id. [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md)
- [ ] (Guaranteed Pub/Sub, Threading Minimal) Persistent publish to a topic with `publish_await_acknowledgement()` and a positive acknowledgement timeout in milliseconds, with no publish receipt listener; a timeout or a NACK is logged with the message id and the message is discarded. [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md)
- [ ] (Guaranteed Pub/Sub, Threading Async) Capacity-bounded back pressure on the publisher (`on_back_pressure_wait`). [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md)
- [ ] (Guaranteed Pub/Sub) Message time-to-live set once on the publisher's message template. [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md)
- [ ] (Guaranteed Pub/Sub) Durable queue plus topic subscription provisioned at start (`CREATE_ON_START`), with the access type from the design summary (one durable queue per consuming service on fan-out); with the Custom admin-provisioned-queue knob, the subscriber binds the administrator's queue instead. [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md)
- [ ] (Guaranteed Pub/Sub) Client acknowledgement only after processing. [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md)
- [ ] (Guaranteed Pub/Sub, Threading Minimal) The subscriber's main loop pulls each message with `receive_message()` and a positive timeout in milliseconds, with no `receive_async()` handler on the receiver and no `time.sleep()` wait. [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md)
- [ ] (Direct Pub/Sub) Direct publish to a topic with a publish failure listener and capacity-bounded back pressure (`on_back_pressure_wait`). [Publishing Direct Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-DM-Publish.md)
- [ ] (Direct Pub/Sub) Direct topic subscription with capacity-bounded back pressure (`on_back_pressure_drop_latest`) and discard detection on received messages. [Consuming Direct Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-DM-Receive.md)
- [ ] (Direct Pub/Sub, Threading Minimal) The subscriber's main loop pulls each message with `receive_message()` and a positive timeout in milliseconds, with no `receive_async()` handler on the receiver and no `time.sleep()` wait. [Consuming Direct Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-DM-Receive.md)
- [ ] (Request-Reply (Direct)) Requests sent through a `RequestReplyMessagePublisher` with a positive reply timeout, and a replier that answers with `replier.reply()` and ignores a message with no reply-to. [Request-Reply Messaging in the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Request-Reply.md)
- [ ] Graceful SIGINT/SIGTERM shutdown: `terminate(grace_period)` and then `disconnect()` on every exit path. [Consuming Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Receive.md)

## Your responsibility (not delivered here)

- [ ] TLS secure session (`tcps://` with certificate validation set explicitly in code). Quickstart, and Custom with the secure-session knob unticked, open a TLS session only when the host is `tcps://`, and then validate only through the API defaults, which a `config.json` key can turn off; neither sets the certificate checks in code. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- [ ] (Any `tcps://` host: Solace Suggested, the Custom secure-session knob, or Quickstart or Custom without that knob with a `tcps://` host) A trust store directory that holds the broker's CA certificate as a PEM file, named by `solace.messaging.tls.trust-store-path` in `config.json`; the Python API has no default trust store, so the connection fails without it. [Using Client Certificates with Solace Cloud](https://docs.solace.com/Cloud/ght_client_certs.md)
- [ ] (Custom admin-provisioned-queue knob) The queue and its topic subscription provisioned on the broker by an administrator, with the access type the design summary records. [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md)
- [ ] Credentials handled safely: `config.json` is never committed, and credentials are not passed as command-line arguments, where they land in shell history and process listings (the `verify.sh` connection arguments and the handed-back run commands accept them only for convenience). [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- [ ] Authentication stronger than basic username/password (for example a client certificate or Kerberos) where the environment requires it; this journey generates basic username/password only. [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Messaging-Service.md)
- [ ] (Guaranteed Pub/Sub) Dead message queue eligibility (`PERSISTENT_DMQ_ELIGIBLE`) set explicitly on published messages. [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Python/Python-PM-Publish.md)
- [ ] (Guaranteed Pub/Sub) A dead message queue provisioned on the broker with a max-redelivery count or a time-to-live; the eligibility flag alone does nothing without it. [Configuring Dead Message Queues](https://docs.solace.com/Messaging/Guaranteed-Msg/Setting-Dead-Msg-Queues.md)
- [ ] Reconnect tuning for HA failover; Quickstart leaves connect and reconnect behavior at the API defaults, and Custom without the HA knob keeps the samples' baseline of 20 retries 3000 ms apart. [Solace C API Best Practices](https://docs.solace.com/API/API-Developer-Guide/C-API-Best-Practices.md)
- [ ] Separate projects or deployments per role (skill guidance: a real deployment lifts each script into its own service or project; no Solace doc page states it).
- [ ] (Guaranteed Pub/Sub) `respect-ttl` enabled on the queue, without which the broker ignores the message time-to-live. [Configuring Queues](https://docs.solace.com/Messaging/Guaranteed-Msg/Configuring-Queues.md)
- [ ] (Guaranteed Pub/Sub) An administrator-provisioned queue, or the endpoint-create permission for this client, in production. [Creating Queues with the Solace Python API](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-Create-Queues.md)
- [ ] A Python version in active or security support and OpenSSL installed on Linux for TLS ([Supported Environments](https://docs.solace.com/API/API-Developer-Guide-Python/Python-API-supported-Environments.md)), and no `multiprocessing` in the application ([solace-pubsubplus on PyPI](https://pypi.org/project/solace-pubsubplus/)).

## Verified by the round-trip

The `verify.sh` stage for the leaf checks these items in each role's own output (`roundtrip` for Guaranteed Pub/Sub, `direct` for Direct Pub/Sub, `direct-request-reply` for Request-Reply (Direct), `app` for an embedded shape, which checks its two hook markers only).

- [ ] (Guaranteed Pub/Sub) The subscriber prints `VERIFY: CONNECTED` and `VERIFY: QUEUE_BOUND`.
- [ ] (Guaranteed Pub/Sub) The publisher prints `VERIFY: CONNECTED` and `VERIFY: PUBLISH_ACKED`, then exits with status 0.
- [ ] (Guaranteed Pub/Sub) The subscriber prints `VERIFY: MESSAGE_RECEIVED` (`PUBLISH_ACKED` alone does not prove delivery). On fan-out, `verify.sh` drives `guaranteed_subscriber.py` (or `guaranteed_subscriber_blocking.py`) only; every further subscriber is checked by its handed-back command.
- [ ] (Direct Pub/Sub) The subscriber prints `VERIFY: CONNECTED` and `VERIFY: SUBSCRIBED`; the publisher prints `VERIFY: CONNECTED` and exits with status 0; the subscriber then prints `VERIFY: MESSAGE_RECEIVED`.
- [ ] (Request-Reply (Direct)) The replier prints `VERIFY: CONNECTED`, `VERIFY: SUBSCRIBED`, and `VERIFY: REQUEST_RECEIVED`; the requestor prints `VERIFY: CONNECTED` and `VERIFY: REPLY_RECEIVED`, then exits with status 0.
- [ ] SIGINT on the long-running role (subscriber or replier) prints `Shutdown signal received` and it exits with status 0.

## Generation conformance

These are mechanical conformance checks on the generated output itself. The copied `verify.sh` reports the first three in its preflight, so a miss is visible on every run.

- [ ] The disclaimer header is the first two lines of every generated `.py` file.
- [ ] Every generated `.py` file sits under `src/`; the project root holds only `requirements.txt`, the config files, `.gitignore`, the verify scripts, and the Markdown artifacts.
- [ ] `requirements.txt` pins the `solace-pubsubplus` version that PyPI reported as `info.version` at generation time, or the developer's version when PyPI was unreachable.
- [ ] This tailored checklist exists at every generated project root.
- [ ] `py_compile` and the import smoke passed (`implement-mode.md` Step 5).
- [ ] Every payload passed to `build()` or a publish call is a `bytearray` or a `str`, never the `bytes` that `.encode()` returns.
- [ ] Every construct carried from a reference sample keeps that construct's comment (names adapted); dropped demo-harness code took its comments with it; fresh messaging code that applies a documented practice carries a short comment naming it (`implement-mode.md` Step 4).
- [ ] A `verify.sh` stage ran and its stage name and exit code are recorded here: `<stage> → exit <code>` (or: the compile-only fallback was taken and the exact commands were handed back).
- [ ] Every page named in the design summary's Grounding docs field was WebFetched in this session (`none fetched` is an honest value; an unfetched citation is not).

## Developer-owned items (not delivered by this skill)

Record here any item the skill does not deliver: non-Solace concerns, out-of-scope concerns, or anything you are tracking on your own. Each is marked developer-owned rather than skill-satisfied, so at a glance a reader sees which items the skill stands behind and which you own. A consented deviation is recorded here. In this master template the section is a skeleton; the per-project copy is where concrete developer-owned items are recorded during a run.

## Your additional items

This is open space for you to add your own checklist items after the run, so the emitted checklist keeps growing with the project alongside the seeded items above.
