# Solace Suggested Mode

The Solace Suggested overlay for Implement mode of the Java API. This file is reached from `implement-mode.md` Step 0 when the developer chooses the Solace Suggested path. It hardens whatever the design summary chose without re-describing how the chosen leaf is generated. Work through the steps in order. Read this file's grounding links only as each step needs them; link the canonical doc and state the decision in one line, never paraphrase doc content.

This overlay is TLS-secure by definition. It assumes a broker whose TLS server certificate is already configured; Solace Cloud, the primary broker default, has TLS out of the box and chains to a public CA. There is NO non-secure variant of Solace Suggested. When the target broker has no TLS configured, do not proceed on this path: state that Solace Suggested requires a TLS-capable broker and route back to the `implement-mode.md` Step 0 door, which offers Custom with the equivalent knobs minus the secure session, or Quickstart. The session is built from the connection details the developer puts into a gitignored `config.json`. The overlay does NOT provision or configure any broker (no DMQ, no queues, no TLS certificates). It states the broker-side prerequisites; it never sets them up.

## This is a leaf-agnostic overlay

The Solace Suggested hardening is described ONCE here and applies on top of whatever `Pattern` leaf the design summary selected. It is not duplicated per leaf. The session-level deltas (TLS and HA failover) are identical code in every leaf, the message-level delta (DMQ eligibility) lands only on persistent publishes, and the separate-projects layout applies to every pattern, since each has two roles (Publisher and Subscriber, or Requestor and Replier).

This overlay defers to `implement-mode.md` for all leaf and sample mechanics: the Step 2 design-summary input contract, the Step 4 leaf dispatch (which leaf wiring file and sample pair each leaf reads, the near-verbatim adaptation discipline), and the fixed `VERIFY:` marker contract. It ADDS only the Solace Suggested deltas below. It changes NO `VERIFY:` marker string. The marker contract, the leaf dispatch table, and the checklist write (Step 4) and report (Step 6) all stay in `implement-mode.md` and its leaf wiring files, and run unchanged on the Solace Suggested path.

Every generated class this overlay describes carries the AI-assisted disclaimer header (the exact two lines `implement-mode.md` Step 4 defines) and keeps every import explicit; never write `import com.solace.messaging.*;`. This overlay names identifiers, constants, and method calls; it does not paste whole generated classes.

## Step 0: Carry over the config.json convention

The Solace Suggested path reads broker connection details ONLY from a `config.json` file. Its keys are the Java API property names the shared helper documents (`solace.messaging.transport.host`, `solace.messaging.service.vpn-name`, `solace.messaging.authentication.basic.username`, `solace.messaging.authentication.basic.password`). State plainly to the developer that `config.json` holds broker credentials, so it MUST be added to `.gitignore` and never committed, and that the credential VALUES MUST be placed directly into `config.json`, never pasted into the chat. Never inline a host, message VPN, username, or password into a generated class.

On this path there are no command-line connection arguments. Generate the shared `SolaceConnectionConfig` helper WITHOUT its command-line fallback: when `config.json` is absent, it prints one line that names the missing file and exits with status 1. Keep everything else in the helper (the pass-through of every key, the blank-value checks, the empty-password rule). The generated `config.example.json` carries the four keys with a `tcps://` placeholder host:

```json
{
  "solace.messaging.transport.host": "tcps://HOST:55443",
  "solace.messaging.service.vpn-name": "YOUR_VPN",
  "solace.messaging.authentication.basic.username": "YOUR_USERNAME",
  "solace.messaging.authentication.basic.password": "YOUR_PASSWORD"
}
```

Generate the classes directly, with no per-stage confirmation gate, the same way Quickstart does, then let the developer review the written files. The credential VALUES live only in the gitignored `config.json`, never in a generated class or the chat, so there is nothing credential-bearing to gate on.

## Step 1: Build the TLS secure session

The `host` in `config.json` carries a `tcps://` scheme: the Messaging Service page states that TLS needs the secure TCP protocol (`tcps`) in the host property. If the developer gives a `tcp://` (plaintext) host, tell them to use the broker's `tcps://` URL and state the TLS-already-configured assumption; never open a plaintext session on the Solace Suggested path. If the broker genuinely has no TLS configured, this overlay does not fit: route back to the `implement-mode.md` Step 0 door (Custom minus the secure-session knob, or Quickstart) rather than degrading the session here.

WebFetch the Using Transport Layer Security section of [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md) and set the TLS strategy EXPLICITLY in code, with the three calls that page's recommended setup uses. Add it to the service builder chain AFTER `fromProperties(properties)` and before `build()`:

```java
messagingService = MessagingService.builder(ConfigurationProfile.V1)
        .fromProperties(properties)
        .withTransportSecurityStrategy(TLS.create()
                .withCertificateHostValidation()
                .withCertificateValidation(trustStorePassword, false)
                .withExcludedProtocols(SecureProtocols.TLSv1_1, SecureProtocols.TLSv1, SecureProtocols.SSLv3))
        .build();
```

`TLS` is `com.solace.messaging.config.TransportSecurityStrategy.TLS`, and `SecureProtocols` is its nested enum; import each one explicitly. The validation settings are the API defaults, but the explicit strategy makes the secure posture auditable in code review, the same reason the JCSMP overlay sets its validation properties explicitly. Because the strategy is applied after the properties, a `config.json` value cannot turn validation off. Five rules:

1. **Never weaken it.** NEVER call `withoutCertificateValidation()` (the page restricts it to development environments), never pass `true` for `ignoreExpiration`, and never set a `solace.messaging.tls.cert-*` property to `false`.
2. **No `withCipherSuites()`.** The method is deprecated, and the page states that it prevents the application from negotiating a TLS 1.3 connection. Leave cipher suite selection to the JVM.
3. **The trust store is the JVM default.** The two-argument `withCertificateValidation(password, ignoreExpiration)` names no trust store file: the [TLS strategy reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/com/solace/messaging/config/TransportSecurityStrategy.TLS.md) states that the trust store is then the file the `javax.net.ssl.trustStore` system property names, otherwise the JDK default trust store. The JDK default trust store carries the public root CAs, which covers Solace Cloud. Do NOT generate a custom trust store, do NOT download a certificate, and do NOT set a trust store path in code.
4. **The trust store password is not the page's example value.** The password argument unlocks the trust store that is selected. Read it from the properties with the JDK default as the fallback, and say so in a comment at that line:

   ```java
   // unlocks the trust store that TLS uses: "changeit" is the password of the JDK default
   // trust store (cacerts). For your own trust store (-Djavax.net.ssl.trustStore=...), set
   // "solace.messaging.tls.trust-store-password" in config.json.
   final String trustStorePassword = properties.getProperty(
           TransportLayerSecurityProperties.TRUST_STORE_PASSWORD, "changeit");
   ```

   Do NOT copy the password literal from the page's snippet: with the JDK default trust store, the load fails on JDK 11 and JDK 17 (`Unable to load trust store`) for any password other than the JDK default. `changeit` is the public default password of the JDK `cacerts` file, not a credential.
5. **A broker with a private CA is the developer's step.** When the broker's certificate is not signed by a public CA, the developer adds the CA certificate to a trust store of their own, starts the JVM with `-Djavax.net.ssl.trustStore=<path>`, and sets `solace.messaging.tls.trust-store-password` in `config.json`. Say so in the handed-back next steps and record it in the checklist under Your responsibility. The generated code never creates or edits a trust store.

**Security floor on the resolved version.** The `javax.net.ssl.trustStore` fallback of rule 3 is correct only from `solace-messaging-client` 1.10.0. The version is resolved at generation time (`implement-mode.md` Step 3), so the latest release meets the floor; this is a CHECK on the resolved result, never a pin. When the pom carries an older version (the developer's choice while Maven Central was unreachable), tell the developer that the secure session needs 1.10.0 or later and do not hand back a run command for the older version.

The two deprecated `SecureProtocols` constants (`TLSv1` and `SSLv3`) make the compiler print a deprecation note. The note is expected: the page's recommended setup excludes those protocols by name. Do not remove them to silence it.

## Step 2: Ask the HA-failover question; upgrade the reconnect values on yes, keep the sample baseline on no

After broker confirmation and before generating the service, ask the developer plainly: is HA failover set up? Ask it as a direct question and wait for the answer.

The reference samples already ship a reasonable reconnect baseline: two `properties.putIfAbsent(...)` lines that set `TransportLayerProperties.RECONNECTION_ATTEMPTS` to 20 and `CONNECTION_RETRIES_PER_HOST` to 3, the recommended default when the design says nothing about high availability. When the answer is no, KEEP this baseline as-is; do not strip it back to the `ConfigurationProfile.V1` defaults (the fail-fast posture belongs to the Quickstart door alone), and do not add the HA-only values.

ONLY when the answer is yes, apply the HA failover values the samples' HA comment block names, as four `properties.putIfAbsent(...)` lines in place of the two baseline lines: `CONNECTION_RETRIES` "1", `RECONNECTION_ATTEMPTS` "20", `RECONNECTION_ATTEMPTS_WAIT_INTERVAL` "3000", and `CONNECTION_RETRIES_PER_HOST` "5". These map to the JCSMP overlay's `connectRetries` 1, `reconnectRetries` 20, `reconnectRetryWaitInMillis` 3000, and `connectRetriesPerHost` 5. Replace the samples' HA comment block with a one-line comment that names the applied values. Keep `putIfAbsent`, so a value the developer sets in `config.json` still wins.

The developer guide has no reconnection page for the Java API. Ground each property in the [TransportLayerProperties reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/com/solace/messaging/config/SolaceProperties.TransportLayerProperties.md) (WebFetch it; it names the JCSMP channel property each key maps to), and ground the four values in [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md), which the Java API inherits because it is built on JCSMP.

## Step 3: Set DMQ eligibility on every persistent publish (Solace Suggested path only)

On the Solace Suggested path, add `messageProperties.setProperty(MessageProperties.PERSISTENT_DMQ_ELIGIBLE, "true");` to the per-message `Properties` object of the persistent publisher, next to the `APPLICATION_MESSAGE_ID` line it already sets, so every message carries it. This applies to the Guaranteed Pub/Sub Publisher only (on either publish shape): the Java API has no guaranteed request-reply leaf in this skill, so no other class builds a persistent message. The [MessageProperties reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/com/solace/messaging/config/SolaceProperties.MessageProperties.md) gives this property a default of `true`; set it explicitly anyway, for the same auditable reason as the TLS strategy in Step 1.

The direct leaves are EXCLUDED. The same reference states that the property is valid only for persistent messages, so it is a misleading no-op on a direct message; do NOT set it on Direct Pub/Sub or Request-Reply (Direct). Quickstart and the reference samples are unchanged; the explicit property is a Solace Suggested addition only.

At the call site, state the broker-side prerequisite as the developer's responsibility in one comment: a message moves to a dead message queue only when a DMQ is configured on the broker AND a max-redelivery count or a time-to-live applies. The property alone does not make DMQ work. Ground the persistent publish in [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Publish.md) and the broker side in [Configuring Dead Message Queues](https://docs.solace.com/Messaging/Guaranteed-Msg/Setting-Dead-Msg-Queues.md).

## Step 4: Keep the bounded publisher back pressure

Every publisher on this path keeps a bounded buffer. The samples already carry it: `onBackPressureWait(PUBLISH_BUFFER_CAPACITY)` on the non-blocking persistent publisher and on the direct publisher, and `onReplierBackPressureReject(REPLY_BUFFER_CAPACITY)` on the replier. NEVER remove one of these calls and NEVER generate `onBackPressureElastic()`: the samples' comments state that the API default buffers without a limit and that the developer guide cautions it can exhaust memory. Two builders have no such call by design, and both stay bounded: the blocking persistent publisher (`publishAwaitAcknowledgement()` holds one message in flight) and the request-reply publisher (its builder has no back pressure method). Keep each sample's comment on the point. This step adds no code: it is the same code on every door, named here because it is part of the suggested baseline.

## Step 5: Generate two separate, independent Maven projects

The Solace Suggested path decouples the two roles. The DEFAULT shape is TWO standalone single-module Maven projects, one per role (Publisher and Subscriber, or Requestor and Replier), each in its own project root with its OWN `pom.xml`. On the fan-out leaf, every Subscriber class gets its own project beside the Publisher project. Each pom resolves `solace-messaging-client` and its logging backend at generation time per `implement-mode.md` Step 3 (no pinned versions; the `2.17.1` Log4Shell floor is a check, not a pin), does not declare `sol-jcsmp`, and declares exactly ONE `exec-maven-plugin` execution for that project's single main, with the same execution id the leaf file names for that role.

On this default shape there is NO parent POM and NO `<modules>` aggregator: each project builds on its own. Each project is self-contained, with its own copy of `SolaceConnectionConfig`, its own `log4j2.xml`, its own `config.example.json`, `.gitignore`, and gitignored `config.json`, and its own tailored `solace-verification-checklist.md`.

**When the developer's ask requires a single deliverable** (one command, one jar, one web app with one dashboard), the two-project default conflicts with the ask. Surface that conflict plainly and let the developer resolve it; NEVER merge silently. Offer three resolutions: (1) keep two independent projects plus a thin launcher script that starts both; (2) one Maven build with separate modules under a parent pom, which keeps the decoupling and still yields one command; (3) drop the decoupled-projects shape with the developer's explicit consent. Whichever they choose is recorded in the emitted `solace-verification-checklist.md`; a consented drop lands under Developer-owned items, so the deviation is written down, not lost.

The Solace Suggested path adds no new Maven dependency. TLS, DMQ, and the reconnect properties all live in the same `solace-messaging-client` artifact Quickstart already uses.

**Verification.** The Java API does not bundle a verify script yet, so this path ends in the same compile-only fallback as Quickstart (`implement-mode.md` Step 5), run once per project: `mvn -q compile` and the conformance checks in each project root, plus the Step 1 version floor. Then hand back one run command per project, the long-running role first, each run from inside its own project root with NO `-Dexec.args` (each class reads its own project's `config.json`, so the broker credentials never appear in a process listing). A live run needs a TLS-capable broker; a certificate or trust store error is an environment failure, never a code failure, so do not modify the code for it.

## Step 6: Topic architecture

For how to structure, order, name, and place wildcards in the topic hierarchy, point the developer to the co-installed `solace-topic-best-practices` skill, which reads the canonical Topic Architecture Best Practices page live and applies it. Do not design the topic hierarchy here and do not restate that skill's guidance; the topic string the developer settles on is recorded in the design summary, but the hierarchy decision belongs to that skill.

## Step 7: Return to the implement-mode contracts

After the Solace Suggested deltas above, return to `implement-mode.md` for the unchanged leaf mechanics: the Step 4 dispatch generates the chosen leaf's classes and writes the tailored `solace-verification-checklist.md` into each project as generation output, and Step 6 resolves each item to its state for this door, invites the developer's additions, and reports the same in chat. This overlay adds the Solace Suggested hardening; it does not replace those contracts.

## Grounding references

Live `docs.solace.com` `.md` pages (WebFetch on demand):

- [Messaging Service](https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md)
- [TLS strategy reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/com/solace/messaging/config/TransportSecurityStrategy.TLS.md)
- [TransportLayerProperties reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/com/solace/messaging/config/SolaceProperties.TransportLayerProperties.md)
- [MessageProperties reference](https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/com/solace/messaging/config/SolaceProperties.MessageProperties.md)
- [JCSMP Best Practices](https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md)
- [Publishing Persistent Messages](https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Publish.md)
- [Configuring Dead Message Queues](https://docs.solace.com/Messaging/Guaranteed-Msg/Setting-Dead-Msg-Queues.md)
