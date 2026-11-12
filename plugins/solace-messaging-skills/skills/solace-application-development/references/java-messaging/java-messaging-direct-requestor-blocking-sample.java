/*
 * Derived from patterns/DirectRequestorBlocking.java
 *   SolaceSamples/solace-samples-java
 *   https://github.com/SolaceSamples/solace-samples-java
 *   Licensed under the Apache License, Version 2.0.
 *
 * Elevated to documented best practices per the Java API developer guide:
 *   Messaging Service:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md
 *   Request-Reply Messaging:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Request-Reply.md
 *   Configuring Logging:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Logging.md
 *   the Java API reference (lifecycle, listener contracts):
 *     https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/overview-summary.md
 *   and the JCSMP Best Practices guide (the Java API wraps JCSMP, so its reconnect
 *   practices apply):
 *     https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *
 * Reference sample. Wired for a basic direct request-reply journey on the requestor
 * side, BLOCKING variant: basic-auth connect, a RequestReplyMessagePublisher, one
 * blocking publishAwaitResponse() with a POSITIVE reply timeout, a
 * PubSubPlusClientException.TimeoutException catch, service event listeners
 * registered BEFORE connect(), and a graceful SIGINT/SIGTERM shutdown. The blocking
 * request IS the synchronization point: the requestor sends one request, waits for
 * the correlated reply, and exits (0 on a reply, 1 on a timeout or a failed request).
 * There is no retry. Request-reply in the Java API is direct only, so it is
 * at-most-once on both legs. The non-blocking variant is
 * java-messaging-direct-requestor-sample.java. Structured as setupSolace(...),
 * sendRequest(), and teardownSolace(), with teardown run from main's finally on every
 * exit path.
 *
 * Only practices documented in canonical Solace sources are encoded here.
 *
 * Any generated adaptation of this sample MUST begin with the exact line:
 *   AI-assisted code. Review before production use.
 * (This reference sample itself carries no such header by design.)
 */

package com.solace.samples.java;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solace.messaging.MessagingService;
import com.solace.messaging.PubSubPlusClientException;
import com.solace.messaging.config.SolaceProperties.TransportLayerProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.RequestReplyMessagePublisher;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.resources.Topic;

public class DirectRequestorBlocking {

    private static final String APP_NAME = DirectRequestorBlocking.class.getSimpleName();
    // request topic the replier subscribes to; matches the direct replier samples
    private static final String REQUEST_TOPIC = "solace/samples/java/direct/request";
    private static final String API = "Java";
    // POSITIVE reply timeout in MILLISECONDS: publishAwaitResponse() blocks up to this long
    // for the correlated reply, then throws PubSubPlusClientException.TimeoutException. The
    // API has no default and requires a value of 1 or more. This value also bounds how long
    // a shutdown signal waits during the request.
    private static final long REQUEST_TIMEOUT_MS = 3000;
    // terminate(gracePeriod): how long to wait for a request still waiting on its reply before
    // the requestor stops; terminate() throws IncompleteMessageDeliveryException when a
    // request is still outstanding after that. The API default is 600000 ms (10 minutes).
    private static final long TERMINATE_GRACE_PERIOD_MS = 10_000;
    // how long the shutdown hook waits for main's teardown: one request timeout, plus the
    // grace period and a margin for the disconnect. The JVM halts when the hook returns.
    private static final long SHUTDOWN_WAIT_MS = REQUEST_TIMEOUT_MS + TERMINATE_GRACE_PERIOD_MS + 5_000;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2 by default; any backend swap follows the logging rule in implement-mode.md Step 3

    private static volatile boolean isShutdown = false;
    private static volatile boolean isShutdownSignalled = false;  // SIGINT/SIGTERM started a JVM shutdown
    private static volatile boolean isMainDone = false;           // main finished its teardown
    private static volatile int exitCode = 0;                     // set to 1 on a failure exit
    private static MessagingService messagingService;
    private static RequestReplyMessagePublisher requestor;

    /** Main. */
    public static void main(String... args) throws InterruptedException {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            setupSolace(args);
            // graceful shutdown: a SIGINT (Ctrl-C) or SIGTERM signals the main thread to stop,
            // then the hook joins the main thread so the cleanup in teardownSolace() (stop the
            // requestor, disconnect) runs to completion before the JVM halts. The JVM exits as
            // soon as all shutdown hooks return, so the hook must wait, not just set a flag.
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (isMainDone) {  // main already tore down and is exiting with exitCode
                    return;
                }
                isShutdownSignalled = true;
                trace("Shutdown signal received, stopping requestor...");
                isShutdown = true;
                try {
                    mainThread.join(SHUTDOWN_WAIT_MS);  // wait for the blocking request to return, then the requestor stop and disconnect
                } catch (InterruptedException e) {
                    // nothing more we can do; the JVM is halting
                }
            }));
            sendRequest();
        } finally {
            teardownSolace();
            trace("Main thread quitting.");
            // set on every exit path, an uncaught exception included, so the shutdown hook
            // does not report a signal when the JVM exits for another reason
            isMainDone = true;
        }
        // non-zero after a failure, so scripts and supervisors see it. A signal-initiated
        // shutdown is already in progress and ends with the JVM's own signal exit status
        // (130 for SIGINT, 143 for SIGTERM): System.exit() would block during it.
        if (exitCode != 0 && !isShutdownSignalled) {
            System.exit(exitCode);
        }
    }

    private static void setupSolace(String[] args) {
        // basic username/password connection details, built by the shared SolaceConnectionConfig
        // helper: read from a config.json in the working directory if present, else from the
        // command line (<host:port> <message-vpn> <client-username> [password])
        final Properties properties = SolaceConnectionConfig.load(args, APP_NAME).toServiceProperties();
        // reconnect budget: a few minutes of reconnect attempts, a reasonable default
        // when the design says nothing about high availability. ConfigurationProfile.V1
        // defaults to only 3 reconnection attempts. putIfAbsent() keeps a value that
        // config.json already set.
        properties.putIfAbsent(TransportLayerProperties.RECONNECTION_ATTEMPTS, "20");
        properties.putIfAbsent(TransportLayerProperties.CONNECTION_RETRIES_PER_HOST, "3");
        // For HA failover resilience, use the JCSMP Best Practices values
        // (https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md):
        //   HA failover: CONNECTION_RETRIES "1", RECONNECTION_ATTEMPTS "20",
        //     RECONNECTION_ATTEMPTS_WAIT_INTERVAL "3000", CONNECTION_RETRIES_PER_HOST "5"
        messagingService = MessagingService.builder(ConfigurationProfile.V1)
                .fromProperties(properties)
                .build();
        // best practice: register the service event listeners BEFORE connect(), so no
        // reconnection or interruption event raised during or right after the connect is
        // lost, and handle each event appropriately rather than only logging it
        messagingService.addReconnectionAttemptListener(serviceEvent -> {
            // connection lost, automatic reconnect attempt in progress. Request-reply is direct
            // (at-most-once): a request or reply in flight during the outage can be lost, and
            // the request then times out
            logger.warn("### Service reconnecting; a request in flight can time out: " + serviceEvent);
        });
        messagingService.addReconnectionListener(serviceEvent -> {
            // automatic reconnect succeeded: requests can go out again
            logger.info("### Service reconnected; requests continue: " + serviceEvent);
        });
        messagingService.addServiceInterruptionListener(serviceEvent -> {
            // the connection went down and cannot be restored (reconnect attempts exhausted)
            logger.error("### Service interrupted and cannot be restored, quitting: " + serviceEvent.getCause());
            // Application cleanup signal: the service will not recover. Trigger
            // application-side cleanup from here. This sample sets isShutdown, so no
            // request goes out, a request in flight fails or times out, and
            // teardownSolace() runs in main's finally.
            exitCode = 1;
            isShutdown = true;
        });
        messagingService.connect();  // blocking connect

        // request-reply publisher: the API sets a unique reply-to topic on each request and
        // correlates the reply, so the requestor needs no reply subscription and no
        // correlation id of its own. Its builder has no back-pressure method (unlike the
        // direct publisher), so there is nothing more to configure.
        requestor = messagingService.requestReply()
                .createRequestReplyMessagePublisherBuilder()
                .build();
        requestor.start();
    }

    private static void sendRequest() throws InterruptedException {
        if (isShutdown) {  // a shutdown signal or a service interruption: send no request
            return;
        }
        // an application message id lets the replier echo it onto the reply for traceability;
        // the API does the request-reply correlation itself and does not need it. A byte[]
        // payload travels as the binary attachment; the replier reads it back with
        // getPayloadAsBytes()
        OutboundMessage request = messagingService.messageBuilder()
                .withApplicationMessageId(UUID.randomUUID().toString())
                .build(("Request from " + APP_NAME).getBytes(StandardCharsets.UTF_8));
        trace(String.format("%s sending a direct request to topic '%s' (timeout %d ms).",
                APP_NAME, REQUEST_TOPIC, REQUEST_TIMEOUT_MS));
        try {
            // blocks until the correlated reply arrives or REQUEST_TIMEOUT_MS passes. A
            // shutdown signal that arrives during the wait takes effect once the call
            // returns, within REQUEST_TIMEOUT_MS. The call also declares the checked
            // InterruptedException; nothing in this sample interrupts the main thread, so it
            // propagates to main, which still runs teardownSolace() in its finally.
            InboundMessage reply = requestor.publishAwaitResponse(request, Topic.of(REQUEST_TOPIC), REQUEST_TIMEOUT_MS);
            // the replier samples send a byte[] payload (the binary attachment), read back
            // with getPayloadAsBytes()
            byte[] payload = reply.getPayloadAsBytes();
            String text = payload != null ? new String(payload, StandardCharsets.UTF_8) : reply.getPayloadAsString();
            trace(API + " " + APP_NAME + " received a correlated reply (message id "
                    + reply.getApplicationMessageId() + "): " + text);
        } catch (PubSubPlusClientException.TimeoutException e) {
            // no reply within REQUEST_TIMEOUT_MS: no replier answered, or the request or the
            // reply was lost (at-most-once). A failure, not a silent retry: this is where an
            // application adds its own retry policy.
            logger.warn("Request got no reply within " + REQUEST_TIMEOUT_MS + " ms.");
            exitCode = 1;
        } catch (RuntimeException e) {  // the request could not be sent: the service is down or the requestor is terminated
            logger.error("### Caught while trying to requestor.publishAwaitResponse(), quitting", e);
            exitCode = 1;
        }
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (a reply, no reply, SIGINT/SIGTERM, service interruption, or an
        // exception), so Solace teardown and application-side cleanup are never skipped.
        isShutdown = true;
        if (requestor != null && requestor.isRunning()) {
            // terminate() gives a request still waiting on its reply up to the grace period.
            // Terminate the requestor before the disconnect: disconnect() stops it at once.
            try {
                requestor.terminate(TERMINATE_GRACE_PERIOD_MS);
            } catch (PubSubPlusClientException.IncompleteMessageDeliveryException e) {
                logger.error("Requestor stopped with a request still outstanding after "
                        + TERMINATE_GRACE_PERIOD_MS + " ms", e);
                exitCode = 1;
            } catch (RuntimeException e) {
                logger.error("Requestor terminate() failed", e);
                exitCode = 1;
            }
        }
        if (messagingService != null && messagingService.isConnected()) {
            messagingService.disconnect();
        }
    }

    /** Demo narration sink: every status line in this sample funnels through this one
     *  method. An application replaces this single body to route narration to its
     *  logger or reporting system. The log4j2 logger calls for API events (service,
     *  request) are a separate channel and stay as they are. */
    private static void trace(String message) {
        System.out.println(message);
    }
}
