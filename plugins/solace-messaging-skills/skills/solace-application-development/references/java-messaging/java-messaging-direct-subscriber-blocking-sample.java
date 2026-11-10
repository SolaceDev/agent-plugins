/*
 * Derived from patterns/DirectReceiver.java
 *   SolaceSamples/solace-samples-java
 *   https://github.com/SolaceSamples/solace-samples-java
 *   Licensed under the Apache License, Version 2.0.
 * The upstream patterns folder has no blocking receiver: the receive loop follows the
 * receiveMessage() section of Consuming Direct Messages (below).
 *
 * Elevated to documented best practices per the Java API developer guide:
 *   Messaging Service:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md
 *   Consuming Direct Messages:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-DM-Subscribe.md
 *   Configuring Logging:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Logging.md
 *   the Java API reference (lifecycle, listener contracts):
 *     https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/overview-summary.md
 *   and the JCSMP Best Practices guide (the Java API wraps JCSMP, so its reconnect
 *   practices apply):
 *     https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *
 * Reference sample. Wired for a basic direct (at-most-once) pub/sub journey, BLOCKING
 * variant: basic-auth connect, a plain topic subscription (no queue, no
 * provisioning), a DIRECT receiver with a capacity-bounded buffer that detects
 * discards and whose main loop calls receiveMessage(timeout), service event listeners
 * registered BEFORE connect(), and a graceful SIGINT/SIGTERM shutdown. Direct
 * messaging is at-most-once: there is no broker ACK and no redelivery, so there is NO
 * ack() here. A service interruption or a receive failure stops the subscriber with
 * exit code 1. The non-blocking variant is java-messaging-direct-subscriber-sample.java.
 * Structured as setupSolace(...), receiveMessages(), and teardownSolace(), with
 * teardown run from main's finally on every exit path.
 *
 * Why blocking: the application's own thread is the single thread of control for the
 * messaging data path. The main thread takes each message from the receiver and
 * processes it, so no message handler runs on an API thread and no application state
 * is shared with one. This sample makes no throughput claim. The cost: the loop
 * processes one message at a time, so a slow loop fills the receive buffer sooner and
 * discards more messages, and a shutdown signal takes effect only when
 * receiveMessage() returns. The service event listeners still run on API threads.
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solace.messaging.MessagingService;
import com.solace.messaging.PubSubPlusClientException;
import com.solace.messaging.config.SolaceProperties.TransportLayerProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.messaging.receiver.DirectMessageReceiver;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.resources.TopicSubscription;

public class DirectSubscriberBlocking {

    private static final String APP_NAME = DirectSubscriberBlocking.class.getSimpleName();
    // topic to subscribe to directly; matches the direct publisher sample's topic root
    private static final String TOPIC_NAME = "solace/samples/java/direct/pub/>";
    private static final String API = "Java";
    // receiveMessage(timeout) blocks up to this many MILLISECONDS for the next message, then
    // returns null. The loop checks the shutdown flag each time the call returns, so this
    // value bounds how long a shutdown signal waits during an empty wait. 1 s keeps Ctrl-C
    // prompt and matches the rate report interval; a longer timeout wakes the loop less often
    // but stops slower. Do not call receiveMessage() without a timeout: it then waits without
    // a limit and a shutdown signal never takes effect while no message arrives.
    private static final long RECEIVE_TIMEOUT_MS = 1000;
    // how often the receive loop reports its message rate, in milliseconds
    private static final long STATS_INTERVAL_MS = 1000;
    // back pressure: once this many received messages wait for the receive loop, the receiver
    // discards each new incoming message until there is room
    // The capacity is a count of messages, not a size in bytes: the memory it holds depends on
    // the size of each message. Tune it to the memory the application's deployment can give
    // the buffer. The API has no default capacity (its default, onBackPressureElastic(),
    // buffers without bound); 1000 is the value the Java developer guide uses in its back
    // pressure examples.
    private static final int RECEIVE_BUFFER_CAPACITY = 1000;
    // terminate(gracePeriod): how long to wait for the messages the API has already received
    // to be taken from its buffer before the receiver stops. In this blocking sample only the
    // receive loop takes them, and that loop has stopped by teardown, so a grace period only
    // delays the exit. 0 stops at once; direct messages still in the buffer are lost
    // (at-most-once).
    private static final long TERMINATE_GRACE_PERIOD_MS = 0;
    // how long the shutdown hook waits for main's teardown: one receive timeout, plus a
    // margin for the message in progress and the disconnect. The JVM halts when the hook returns.
    private static final long SHUTDOWN_WAIT_MS = RECEIVE_TIMEOUT_MS + 5_000;

    private static volatile int msgRecvCounter = 0;              // num messages received
    private static volatile boolean hasDetectedDiscard = false;  // any discards seen?
    private static volatile boolean isShutdown = false;          // are we done?
    private static volatile boolean isShutdownSignalled = false; // SIGINT/SIGTERM started a JVM shutdown
    private static volatile boolean isMainDone = false;          // main finished its teardown
    private static volatile int exitCode = 0;                    // set to 1 on a failure exit
    private static MessagingService messagingService;
    private static DirectMessageReceiver receiver;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2 by default; any backend swap follows the logging rule in implement-mode.md Step 3

    /** This is the main app. Use this type of app for receiving Direct (at-most-once) messages from a topic. */
    public static void main(String... args) {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            setupSolace(args);
            // graceful shutdown: a SIGINT (Ctrl-C) or SIGTERM signals the main loop to stop,
            // then the hook joins the main thread so the cleanup in teardownSolace() (stop the
            // receiver, disconnect) runs to completion before the JVM halts. The JVM exits as
            // soon as all shutdown hooks return, so the hook must wait, not just set a flag.
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (isMainDone) {  // main already tore down and is exiting with exitCode
                    return;
                }
                isShutdownSignalled = true;
                trace("Shutdown signal received, stopping subscriber...");
                isShutdown = true;
                try {
                    mainThread.join(SHUTDOWN_WAIT_MS);  // wait for the receive loop to return, then the receiver stop and disconnect
                } catch (InterruptedException e) {
                    // nothing more we can do; the JVM is halting
                }
            }));
            receiveMessages();
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
        // ServiceProperties.RECEIVER_DIRECT_SUBSCRIPTION_REAPPLY re-adds the direct topic
        // subscription automatically after an automatic reconnect. ConfigurationProfile.V1
        // already sets it to true (the JCSMP default is false), so this sample does not set
        // it. A direct subscription is not held on a broker queue, so without it delivery
        // would silently stop after a reconnect.
        messagingService = MessagingService.builder(ConfigurationProfile.V1)
                .fromProperties(properties)
                .build();
        // best practice: register the service event listeners BEFORE connect(), so no
        // reconnection or interruption event raised during or right after the connect is
        // lost, and handle each event appropriately rather than only logging it
        messagingService.addReconnectionAttemptListener(serviceEvent -> {
            // connection lost, automatic reconnect attempt in progress; receiveMessage() keeps
            // timing out and the loop keeps going
            logger.warn("### Service reconnecting; direct delivery is paused until re-established: " + serviceEvent);
        });
        messagingService.addReconnectionListener(serviceEvent -> {
            // automatic reconnect succeeded; the subscription is reapplied and delivery resumes
            logger.info("### Service reconnected; the subscription is restored and delivery resumes: " + serviceEvent);
        });
        messagingService.addServiceInterruptionListener(serviceEvent -> {
            // the connection went down and cannot be restored (reconnect attempts exhausted)
            logger.error("### Service interrupted and cannot be restored, quitting: " + serviceEvent.getCause());
            // Application cleanup signal: the service will not recover. Trigger
            // application-side cleanup from here. This sample sets isShutdown, so
            // the receive loop exits and teardownSolace() runs in main's finally.
            exitCode = 1;
            isShutdown = true;
        });
        messagingService.connect();  // blocking connect

        // DIRECT receiver: a topic subscription on the receiver (no queue, no provisioning).
        // start() adds the subscription; direct messaging is at-most-once, so messages flow
        // straight to the subscriber with no broker ACK and no redelivery.
        // Capacity-bounded back pressure: once RECEIVE_BUFFER_CAPACITY messages wait for the
        // receive loop, onBackPressureDropLatest(n) discards each new incoming message, and
        // the next message delivered carries an internal discard indication. The API default
        // (onBackPressureElastic()) buffers without bound and the developer guide cautions it
        // can exhaust memory; onBackPressureDropOldest(n) discards the oldest buffered message
        // instead.
        receiver = messagingService.createDirectMessageReceiverBuilder()
                .withSubscriptions(TopicSubscription.of(TOPIC_NAME))
                .onBackPressureDropLatest(RECEIVE_BUFFER_CAPACITY)
                .build();
        trace(String.format("Adding direct topic subscription '%s'.", TOPIC_NAME));
        receiver.start();
        // no receiveAsync(): the receive loop takes each message with receiveMessage(). The
        // API reference states the two receive styles are mutually exclusive on one receiver.
    }

    private static void receiveMessages() {
        trace(String.format("%s subscribed and consuming. Press Ctrl-C to quit.", APP_NAME));
        long nextReport = System.currentTimeMillis() + STATS_INTERVAL_MS;
        while (!isShutdown) {
            final InboundMessage message;
            try {
                // blocks up to RECEIVE_TIMEOUT_MS for the next message. null means a timeout, or
                // that the receiver or the service is shutting down; either way the loop goes
                // round and the shutdown check above decides whether to stop
                message = receiver.receiveMessage(RECEIVE_TIMEOUT_MS);
            } catch (RuntimeException e) {
                // thrown once the service is down or the receiver is terminated. A service
                // interruption has already set isShutdown and exitCode
                if (!isShutdown) {
                    logger.error("receiveMessage() failed, quitting", e);
                    exitCode = 1;
                }
                break;
            }
            if (message != null) {
                processMessage(message);
            }
            // simple way of calculating message rates, inline on this thread: no stats thread,
            // so the main thread stays the only application thread
            long now = System.currentTimeMillis();
            if (now >= nextReport) {
                trace(String.format("%s %s Received msgs/s: %,d",API,APP_NAME,msgRecvCounter));
                msgRecvCounter = 0;
                if (hasDetectedDiscard) {  // at least one message was dropped before this subscriber
                    trace("*** Discard detected (at-most-once: a direct message was dropped) ***");
                    hasDetectedDiscard = false;  // only show the warning once per report
                }
                nextReport = now + STATS_INTERVAL_MS;
            }
        }
    }

    private static void processMessage(InboundMessage message) {
        msgRecvCounter++;
        // the publisher sample sends a byte[] payload (the binary attachment), read back
        // with getPayloadAsBytes()
        byte[] payload = message.getPayloadAsBytes();
        if (payload != null) {
            trace(API + " " + APP_NAME + " received: " + new String(payload, StandardCharsets.UTF_8));
        }
        // at-most-once discard evidence (API reference): the broker sets the broker discard
        // indication when it discarded direct messages destined for this subscriber for any
        // reason (e.g. a slow consumer overran its egress buffer), and the API sets the
        // internal one when its own receive buffer dropped messages (back pressure, see
        // RECEIVE_BUFFER_CAPACITY). There is NO redelivery in direct messaging.
        if (message.getMessageDiscardNotification().hasBrokerDiscardIndication()
                || message.getMessageDiscardNotification().hasInternalDiscardIndication()) {
            hasDetectedDiscard = true;
        }
        // Direct messaging is at-most-once: there is no consumer-side acknowledgement here.
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (normal quit, SIGINT/SIGTERM, service interruption, or an
        // exception), so Solace teardown and application-side cleanup are never skipped.
        isShutdown = true;
        if (receiver != null && receiver.isRunning()) {
            // stop delivery at once (TERMINATE_GRACE_PERIOD_MS is 0): the receive loop has
            // stopped, so nothing takes more messages from the API's buffer. Terminate the
            // receiver before the disconnect: disconnect() stops it at once.
            try {
                receiver.terminate(TERMINATE_GRACE_PERIOD_MS);
            } catch (PubSubPlusClientException.IncompleteMessageDeliveryException e) {
                logger.warn("Receiver stopped with unprocessed messages in its buffer; they are lost (at-most-once)", e);
            } catch (RuntimeException e) {
                logger.error("Receiver terminate() failed", e);
                exitCode = 1;
            }
        }
        if (messagingService != null && messagingService.isConnected()) {
            messagingService.disconnect();  // will also release the receiver
        }
    }

    /** Demo narration sink: every status line in this sample funnels through this one
     *  method. An application replaces this single body to route narration to its
     *  logger or reporting system. The log4j2 logger calls for API events (service)
     *  are a separate channel and stay as they are. */
    private static void trace(String message) {
        System.out.println(message);
    }
}
