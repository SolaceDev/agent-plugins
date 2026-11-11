/*
 * Derived from patterns/DirectPublisher.java
 *   SolaceSamples/solace-samples-java
 *   https://github.com/SolaceSamples/solace-samples-java
 *   Licensed under the Apache License, Version 2.0.
 *
 * Elevated to documented best practices per the Java API developer guide:
 *   Messaging Service:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md
 *   Publishing Direct Messages:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-DM-Publish.md
 *   Configuring Logging:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Logging.md
 *   the Java API reference (lifecycle, listener contracts):
 *     https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/overview-summary.md
 *   and the JCSMP Best Practices guide (the Java API wraps JCSMP, so its reconnect
 *   practices apply):
 *     https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *
 * Reference sample. Wired for a basic direct (at-most-once) pub/sub journey:
 * basic-auth connect, a continuous DIRECT binary publish loop with a rotating payload
 * to a topic, a PublishFailureListener for the asynchronous failures the API reports,
 * capacity-bounded back pressure that blocks publish() while the buffer is full,
 * service event listeners registered BEFORE connect(), and a graceful SIGINT/SIGTERM
 * shutdown.
 * Direct messaging is at-most-once: there is no broker ACK, so there is NO publish
 * receipt and NO correlation here (the inverse of the guaranteed publisher, which acts
 * on each ACK/NACK). A failed publish() or a service interruption stops the publisher
 * with exit code 1. Structured as setupSolace(...), runPublishLoop(), and
 * teardownSolace(), with teardown run from main's finally on every exit path.
 *
 * Only practices documented in canonical Solace sources are encoded here.
 *
 * Any generated adaptation of this sample MUST begin with the exact line:
 *   AI-assisted code. Review before production use.
 * (This reference sample itself carries no such header by design.)
 */

package com.solace.samples.java;

import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solace.messaging.MessagingService;
import com.solace.messaging.PubSubPlusClientException;
import com.solace.messaging.config.SolaceProperties.TransportLayerProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.messaging.publisher.DirectMessagePublisher;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.OutboundMessageBuilder;
import com.solace.messaging.resources.Topic;

public class DirectPublisher {

    private static final String APP_NAME = DirectPublisher.class.getSimpleName();
    static final String TOPIC_PREFIX = "solace/samples/";  // used as the topic "root"
    private static final String API = "Java";
    private static final int PAYLOAD_SIZE = 512;
    // back pressure: publish() blocks once this many messages wait unsent, until there is room
    // The capacity is a count of messages, not a size in bytes: the memory it holds depends on
    // the size of each message. Tune it to the memory the application's deployment can give
    // the buffer. The API has no default capacity (its default, onBackPressureElastic(),
    // buffers without bound); 1000 is the value the Java developer guide uses in its back
    // pressure examples.
    private static final int PUBLISH_BUFFER_CAPACITY = 1000;
    // terminate(gracePeriod): how long to wait for the buffered messages to go out before the
    // publisher stops; terminate() throws IncompleteMessageDeliveryException when messages are
    // still buffered after that. There are no ACKs to wait for in direct messaging. The API
    // default is 600000 ms (10 minutes).
    private static final long TERMINATE_GRACE_PERIOD_MS = 10_000;
    // how long the shutdown hook waits for main's teardown: the grace period plus a margin
    // for the disconnect. The JVM halts when the hook returns.
    private static final long SHUTDOWN_WAIT_MS = TERMINATE_GRACE_PERIOD_MS + 5_000;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2 by default; any backend swap follows the logging rule in implement-mode.md Step 3

    private static volatile int msgSentCounter = 0;                   // num messages sent
    private static volatile boolean isShutdown = false;
    private static volatile boolean isShutdownSignalled = false;      // SIGINT/SIGTERM started a JVM shutdown
    private static volatile boolean isMainDone = false;               // main finished its teardown
    private static volatile int exitCode = 0;                         // set to 1 on a failure exit
    // guards isPublishLoopDone: the shutdown hook interrupts the main thread only while
    // the publish loop can still wait in publish()
    private static final Object shutdownLock = new Object();
    private static boolean isPublishLoopDone = false;
    private static MessagingService messagingService;
    private static DirectMessagePublisher publisher;
    private static ScheduledExecutorService statsPrintingThread;

    /** Main. */
    public static void main(String... args) {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            setupSolace(args);
            // graceful shutdown: a SIGINT (Ctrl-C) or SIGTERM signals the main loop to stop,
            // then the hook joins the main thread so the cleanup in teardownSolace() (stop
            // publishing, disconnect) runs to completion before the JVM halts. The JVM exits
            // as soon as all shutdown hooks return, so the hook must wait, not just set a flag.
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (isMainDone) {  // main already tore down and is exiting with exitCode
                    return;
                }
                isShutdownSignalled = true;
                trace("Shutdown signal received, stopping publisher...");
                isShutdown = true;
                // a publish() that waits on back pressure (a full buffer, for example during
                // an automatic reconnect) does not see the flag: interrupt it, so the main
                // thread always reaches teardownSolace()
                synchronized (shutdownLock) {
                    if (!isPublishLoopDone) {
                        mainThread.interrupt();
                    }
                }
                try {
                    mainThread.join(SHUTDOWN_WAIT_MS);  // wait for the main thread's buffer drain and disconnect
                } catch (InterruptedException e) {
                    // nothing more we can do; the JVM is halting
                }
            }));
            runPublishLoop();
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
            // connection lost, automatic reconnect attempt in progress
            logger.warn("### Service reconnecting; direct messages sent while down can be lost (at-most-once): "
                    + serviceEvent);
        });
        messagingService.addReconnectionListener(serviceEvent -> {
            // automatic reconnect succeeded
            logger.info("### Service reconnected; publishing continues: " + serviceEvent);
        });
        messagingService.addServiceInterruptionListener(serviceEvent -> {
            // the connection went down and cannot be restored (reconnect attempts exhausted)
            logger.error("### Service interrupted and cannot be restored, quitting: " + serviceEvent.getCause());
            // Application cleanup signal: the service will not recover. Trigger
            // application-side cleanup from here. This sample sets isShutdown, so
            // the main loop exits and teardownSolace() runs in main's finally.
            exitCode = 1;
            isShutdown = true;
        });
        messagingService.connect();  // blocking connect

        // DIRECT publisher with capacity-bounded back pressure: publish() blocks once
        // PUBLISH_BUFFER_CAPACITY messages wait unsent, and returns when there is room again.
        // The API default (onBackPressureElastic()) buffers without bound and the developer
        // guide cautions it can exhaust memory; onBackPressureReject(n) throws
        // PublisherOverflowException instead of blocking.
        publisher = messagingService.createDirectMessagePublisherBuilder()
                .onBackPressureWait(PUBLISH_BUFFER_CAPACITY)
                .build();
        // register the failure listener BEFORE start(), so no failure is missed. It runs on an
        // API thread and can be called for ACL violations and connection loss; direct
        // messaging has no broker ACK, so this is the only signal that a message did not go out.
        publisher.setPublishFailureListener(failedPublishEvent -> {
            logger.warn("### Direct publish failed for destination " + failedPublishEvent.getDestination(),
                    failedPublishEvent.getException());
        });
        publisher.start();
    }

    private static void runPublishLoop() {
        statsPrintingThread = Executors.newSingleThreadScheduledExecutor();
        statsPrintingThread.scheduleAtFixedRate(() -> {
            trace(String.format("%s %s Published msgs/s: %,d",API,APP_NAME,msgSentCounter));  // simple way of calculating message rates
            msgSentCounter = 0;
        }, 1, 1, TimeUnit.SECONDS);

        trace(API + " " + APP_NAME + " connected, and running. Press Ctrl-C to quit.");
        byte[] payload = new byte[PAYLOAD_SIZE];  // preallocate
        // one OutboundMessageBuilder, reused for every message; build() copies the payload,
        // so the payload array can be refilled on the next pass
        OutboundMessageBuilder messageBuilder = messagingService.messageBuilder();
        trace("Publishing to topic '"+ TOPIC_PREFIX + API.toLowerCase() +
                "/direct/pub/...', at-most-once (no broker ACK, no redelivery).");
        while (!isShutdown) {  // loop until the shutdown flag
            // each loop, change the payload as an example
            char chosenCharacter = (char)(Math.round(msgSentCounter % 26) + 65);  // choose a "random" letter [A-Z]
            Arrays.fill(payload,(byte)chosenCharacter);  // fill the payload completely with that char
            // a byte[] payload travels as the binary attachment; consumers read it back
            // with getPayloadAsBytes()
            OutboundMessage message = messageBuilder.withApplicationMessageId(UUID.randomUUID().toString())  // as an example
                    .build(payload);
            String topicString = new StringBuilder(TOPIC_PREFIX).append(API.toLowerCase())
                    .append("/direct/pub/").append(chosenCharacter).toString();
            try {
                // NO user context: direct is at-most-once with no broker ACK, so there is
                // nothing to correlate (the inverse of the guaranteed publisher).
                // publish() blocks while the buffer is full (back pressure). During an automatic
                // reconnect the messages wait in the buffer for the transport; once the buffer is
                // full, publish() blocks until the reconnect completes.
                publisher.publish(message, Topic.of(topicString));
                msgSentCounter++;
            } catch (PubSubPlusClientException.RequestInterruptedException e) {
                // interrupted while publish() waited on back pressure: this message was not sent
                if (isShutdownSignalled) {  // the shutdown hook did it: a normal stop
                    logger.info("publish() interrupted by the shutdown signal; the message in hand was not sent");
                } else {
                    logger.error("### publish() was interrupted, quitting", e);
                    exitCode = 1;
                }
                isShutdown = true;
            } catch (RuntimeException e) {  // threw from publish(): the service is down or the publisher is terminated
                logger.error("### Caught while trying to publisher.publish(), quitting", e);
                exitCode = 1;
                isShutdown = true;  // let's quit; or, could initiate a new connection attempt
            }
            // no delay between messages: the loop publishes as fast as publish() returns, the way an
            // application publishes each message as soon as it has one
            // Note: STANDARD Edition Solace broker is limited to 10k msg/s max ingress
        }
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (normal quit, SIGINT/SIGTERM, service interruption, or an
        // exception), so Solace teardown and application-side cleanup are never skipped.
        isShutdown = true;
        // the publish loop is over: stop the shutdown hook from interrupting this thread, and
        // clear an interrupt it already sent, so the blocking calls below run to completion
        synchronized (shutdownLock) {
            isPublishLoopDone = true;
            Thread.interrupted();
        }
        if (statsPrintingThread != null) {
            statsPrintingThread.shutdown();  // stop printing stats
        }
        if (publisher != null && publisher.isRunning()) {
            // direct is at-most-once: there are no ACKs to drain, but terminate() still lets the
            // buffered messages go out. Terminate the publisher before the disconnect:
            // disconnect() stops it at once and discards its buffer.
            try {
                publisher.terminate(TERMINATE_GRACE_PERIOD_MS);
            } catch (PubSubPlusClientException.IncompleteMessageDeliveryException e) {
                logger.error("Publisher stopped with messages still buffered after "
                        + TERMINATE_GRACE_PERIOD_MS + " ms", e);
                exitCode = 1;
            } catch (RuntimeException e) {
                logger.error("Publisher terminate() failed", e);
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
     *  publish failure) are a separate channel and stay as they are. */
    private static void trace(String message) {
        System.out.println(message);
    }
}
