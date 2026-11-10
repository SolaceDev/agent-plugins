/*
 * Derived from patterns/GuaranteedBlockingPublisher.java
 *   SolaceSamples/solace-samples-java
 *   https://github.com/SolaceSamples/solace-samples-java
 *   Licensed under the Apache License, Version 2.0.
 *
 * Elevated to documented best practices per the Java API developer guide:
 *   Messaging Service:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md
 *   Publishing Persistent Messages:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Publish.md
 *   Configuring Logging:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Logging.md
 *   the Java API reference (lifecycle, listener contracts):
 *     https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/overview-summary.md
 *   and the JCSMP Best Practices guide (the Java API wraps JCSMP, so its reconnect and
 *   acknowledgement practices apply):
 *     https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *
 * Reference sample. Wired for a basic publish-subscribe journey, BLOCKING variant:
 * basic-auth connect, PERSISTENT binary publish to a topic with one blocking
 * publishAwaitAcknowledgement() per message and a POSITIVE acknowledgement timeout,
 * service event listeners registered BEFORE connect(), and a graceful SIGINT/SIGTERM
 * shutdown. There is no publish receipt listener: the call returns once the broker
 * ACKs the message and throws on a NACK or a timeout. A timeout, a NACK, or a buffer
 * overflow is logged and the message is discarded; any other publish failure or a
 * service interruption
 * stops the publisher with exit code 1. The non-blocking variant is
 * java-messaging-guaranteed-publisher-sample.java. Structured as setupSolace(...),
 * runPublishLoop(), and teardownSolace(), with teardown run from main's finally on
 * every exit path.
 *
 * Why blocking: the application's own thread is the single thread of control for the
 * messaging data path. The main thread publishes each message and handles its ACK or
 * NACK itself, so no receipt callback runs on an API thread and no application state
 * is shared with one. This sample makes no throughput claim. The cost: only one
 * persistent message is in flight at a time, so each message waits a full broker round
 * trip before the next one goes out. The service event listeners still run on API
 * threads.
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
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.OutboundMessageBuilder;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.resources.Topic;

public class GuaranteedPublisherBlocking {

    private static final String APP_NAME = GuaranteedPublisherBlocking.class.getSimpleName();
    static final String TOPIC_PREFIX = "solace/samples/";  // used as the topic "root"
    private static final String API = "Java";
    private static final int PAYLOAD_SIZE = 512;
    // POSITIVE acknowledgement timeout in MILLISECONDS: publishAwaitAcknowledgement() blocks
    // up to this long for the broker's ACK or NACK, then throws TimeoutException. The API
    // resends an unACKed message each time its publisher ACK timer expires (2000 ms by
    // default, ServiceProperties.PUBLISHER_PERSISTENT_ACK_TIMEOUT), so 5000 ms allows more
    // than one attempt. Keep the value above that timer.
    private static final long PUBLISH_ACK_TIMEOUT_MS = 5000;
    // terminate(gracePeriod): how long to wait for a message that the API has not yet handed
    // to the transport before the publisher stops; terminate() throws
    // IncompleteMessageDeliveryException when messages are still outstanding after that.
    // After the loop stops, a blocking publish has normally no message outstanding, so
    // terminate() returns at once. The API default is 600000 ms (10 minutes).
    private static final long TERMINATE_GRACE_PERIOD_MS = 10_000;
    // how long the shutdown hook waits for main's teardown: one blocking publish that is
    // still waiting for its ACK, then the grace period, plus a margin for the disconnect.
    // The JVM halts when the hook returns.
    private static final long SHUTDOWN_WAIT_MS = PUBLISH_ACK_TIMEOUT_MS + TERMINATE_GRACE_PERIOD_MS + 5_000;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2 by default; any backend swap follows the logging rule in implement-mode.md Step 3

    private static volatile int msgSentCounter = 0;                   // num messages ACKed
    private static volatile boolean isShutdown = false;
    private static volatile boolean isShutdownSignalled = false;      // SIGINT/SIGTERM started a JVM shutdown
    private static volatile boolean isMainDone = false;               // main finished its teardown
    private static volatile int exitCode = 0;                         // set to 1 on a failure exit
    private static MessagingService messagingService;
    private static PersistentMessagePublisher publisher;
    private static ScheduledExecutorService statsPrintingThread;

    /** Main. */
    public static void main(String... args) {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            setupSolace(args);
            // graceful shutdown: a SIGINT (Ctrl-C) or SIGTERM signals the main loop to stop,
            // then the hook joins the main thread so the cleanup in teardownSolace()
            // (terminate the publisher, disconnect) runs to completion before the JVM halts.
            // The JVM exits as soon as all shutdown hooks return, so the hook must wait, not
            // just set a flag.
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (isMainDone) {  // main already tore down and is exiting with exitCode
                    return;
                }
                isShutdownSignalled = true;
                trace("Shutdown signal received, stopping publisher...");
                isShutdown = true;
                try {
                    mainThread.join(SHUTDOWN_WAIT_MS);  // wait for the in-flight ACK and the disconnect
                } catch (InterruptedException e) {
                    // nothing more we can do; the JVM is halting
                }
            }));
            runPublishLoop();
        } finally {
            teardownSolace();
            trace("Main thread quitting.");
        }
        // non-zero after a failure, so scripts and supervisors see it. A signal-initiated
        // shutdown is already in progress and ends with the JVM's own signal exit status
        // (130 for SIGINT, 143 for SIGTERM): System.exit() would block during it.
        isMainDone = true;
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
            // connection lost, automatic reconnect attempt in progress: the message in flight
            // waits for the transport, and publishAwaitAcknowledgement() keeps waiting for its
            // ACK up to PUBLISH_ACK_TIMEOUT_MS
            logger.warn("### Service reconnecting; the message in flight waits for its ACK: " + serviceEvent);
        });
        messagingService.addReconnectionListener(serviceEvent -> {
            // automatic reconnect succeeded: the waiting message goes out
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

        // PERSISTENT publisher with no back-pressure setting: publishAwaitAcknowledgement()
        // returns only after the broker's receipt, so the publish buffer never holds more than
        // the one message in flight, and the API default (onBackPressureElastic) cannot grow.
        // The non-blocking sample bounds the buffer with onBackPressureWait() because its
        // publish() returns before the receipt.
        publisher = messagingService.createPersistentMessagePublisherBuilder().build();
        // no publish receipt listener: each publishAwaitAcknowledgement() call returns the
        // broker's ACK as its normal return and the NACK as an exception, on the main thread
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
                "/pers/pub/...', please ensure queue has matching subscription.");
        while (!isShutdown) {  // loop until the shutdown flag
            // each loop, change the payload as an example
            char chosenCharacter = (char)(Math.round(msgSentCounter % 26) + 65);  // choose a "random" letter [A-Z]
            Arrays.fill(payload,(byte)chosenCharacter);  // fill the payload completely with that char
            String msgId = UUID.randomUUID().toString();
            // a byte[] payload travels as the binary attachment; consumers read it back
            // with getPayloadAsBytes()
            OutboundMessage message = messageBuilder.withApplicationMessageId(msgId)  // as an example
                    .build(payload);
            String topicString = new StringBuilder(TOPIC_PREFIX).append(API.toLowerCase())
                    .append("/pers/pub/").append(chosenCharacter).toString();
            // NOTE: publishing to topic, so make sure the consumer's queue is subscribed to the same topic,
            //       or enable "Reject Message to Sender on No Subscription Match" the client-profile
            try {
                // blocks until the broker ACKs this message or PUBLISH_ACK_TIMEOUT_MS passes: a
                // normal return IS the ACK, so the message needs no receipt correlation. During
                // an automatic reconnect the message waits for the transport and its ACK comes
                // after the reconnect. A shutdown signal that arrives during the wait takes
                // effect once the call returns, within PUBLISH_ACK_TIMEOUT_MS.
                publisher.publishAwaitAcknowledgement(message, Topic.of(topicString), PUBLISH_ACK_TIMEOUT_MS);
                msgSentCounter++;
            // A timeout, a NACK, or an overflow is handled with the "Discard the Message" strategy
            // from the developer guide ("Strategies for Handling Publish Receipt Errors"): log it and go
            // on to the next message. Do not use this strategy if the application cannot
            // tolerate message loss; the guide's other strategies are to wait and retry, and
            // to retry a predefined number of times.
            } catch (PubSubPlusClientException.TimeoutException e) {
                // no ACK or NACK inside the timeout. The broker can still have the message (the
                // ACK can be late, for example after a long reconnect), so a discarded message
                // can still be delivered
                logger.warn(String.format("No ACK for Message ID %s within %d ms; discarding it",
                        msgId, PUBLISH_ACK_TIMEOUT_MS));
            } catch (PubSubPlusClientException.MessageRejectedByBrokerException
                    | PubSubPlusClientException.MessageNotAcknowledgedByBrokerException
                    | PubSubPlusClientException.MessageDestinationDoesNotExistException e) {
                // NACK: the broker did not persist the message, and the exception says why (for
                // example MessageDestinationDoesNotExistException when no queue subscribes to the
                // topic and the client-profile rejects on no subscription match)
                logger.warn(String.format("NACK for Message ID %s; discarding it - %s", msgId, e));
            } catch (PubSubPlusClientException.PublisherOverflowException e) {
                // the API reference lists it for this call; with one message in flight and the
                // default back-pressure setting, the publish buffer does not overflow
                logger.warn(String.format("Publish buffer overflow for Message ID %s; discarding it - %s", msgId, e));
            } catch (InterruptedException e) {
                // interrupted while waiting for the ACK: probably shutting down
                Thread.currentThread().interrupt();  // preserve interrupt
                isShutdown = true;
            } catch (RuntimeException e) {
                // the message cannot be sent (for example the service is down or the publisher
                // is terminated) and retrying would not help
                logger.error("### Caught while trying to publisher.publishAwaitAcknowledgement(), quitting", e);
                exitCode = 1;
                isShutdown = true;  // let's quit; or, could initiate a new connection attempt
            }
            // no delay between messages: the next message goes out as soon as the previous one is
            // ACKed. Note: STANDARD Edition Solace broker is limited to 10k msg/s max ingress
        }
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (normal quit, SIGINT/SIGTERM, service interruption, a publish
        // failure, or an exception), so Solace teardown and application-side cleanup are
        // never skipped.
        isShutdown = true;
        if (statsPrintingThread != null) {
            statsPrintingThread.shutdown();  // stop printing stats
        }
        if (publisher != null && publisher.isRunning()) {
            // terminate the publisher before the disconnect: disconnect() stops it at once
            // and discards its buffer
            try {
                publisher.terminate(TERMINATE_GRACE_PERIOD_MS);
            } catch (PubSubPlusClientException.IncompleteMessageDeliveryException e) {
                logger.error("Publisher stopped with a message still outstanding after "
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
