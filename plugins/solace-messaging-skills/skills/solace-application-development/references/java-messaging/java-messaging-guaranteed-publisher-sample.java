/*
 * Derived from patterns/GuaranteedNonBlockingPublisher.java
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
 * Reference sample. Wired for a basic publish-subscribe journey: basic-auth
 * connect, PERSISTENT binary publish to a topic with publish(), publish receipt
 * (ACK/NACK) handling in a MessagePublishReceiptListener correlated by user context,
 * capacity-bounded back pressure that blocks publish() while the buffer is full,
 * service event listeners registered BEFORE
 * connect(), and a graceful SIGINT/SIGTERM shutdown that finishes outstanding ACKs
 * before disconnecting. A NACK is logged and the message is discarded; a failed
 * publish() or a service interruption stops the publisher with exit code 1. The
 * blocking variant is java-messaging-guaranteed-publisher-blocking-sample.java.
 * Structured as setupSolace(...), runPublishLoop(), and teardownSolace(), with
 * teardown run from main's finally on every exit path.
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
import com.solace.messaging.publisher.PersistentMessagePublisher.MessagePublishReceiptListener;
import com.solace.messaging.publisher.PersistentMessagePublisher.PublishReceipt;
import com.solace.messaging.resources.Topic;

public class GuaranteedPublisher {

    private static final String APP_NAME = GuaranteedPublisher.class.getSimpleName();
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
    // terminate(gracePeriod): how long to wait for the buffered messages to go out and for
    // the in-flight messages' ACKs before the publisher stops; terminate() throws
    // IncompleteMessageDeliveryException when messages are still outstanding after that.
    // The API default is 600000 ms (10 minutes). Adjust the value to suit the application:
    // longer gives outstanding messages more time, shorter exits sooner.
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
    private static MessagingService messagingService;
    private static PersistentMessagePublisher publisher;
    private static ScheduledExecutorService statsPrintingThread;

    /** Main. */
    public static void main(String... args) {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            setupSolace(args);
            // graceful shutdown: a SIGINT (Ctrl-C) or SIGTERM signals the main loop to stop,
            // then the hook joins the main thread so the cleanup in teardownSolace() (finish
            // outstanding ACKs, disconnect) runs to completion before the JVM halts. The JVM
            // exits as soon as all shutdown hooks return, so the hook must wait, not just
            // set a flag.
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (isMainDone) {  // main already tore down and is exiting with exitCode
                    return;
                }
                isShutdownSignalled = true;
                trace("Shutdown signal received, stopping publisher...");
                isShutdown = true;
                try {
                    mainThread.join(SHUTDOWN_WAIT_MS);  // wait for the main thread's ACK drain and disconnect
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
        // PUB_ACK_WINDOW_SIZE: ConfigurationProfile.V1 already sets the guaranteed-publish
        // window to 255, the maximum (the JCSMP default is 1). 255 favors throughput; lower
        // it with withDeliveryAckWindowSize(n) on the publisher builder for less memory.
        messagingService = MessagingService.builder(ConfigurationProfile.V1)
                .fromProperties(properties)
                .build();
        // best practice: register the service event listeners BEFORE connect(), so no
        // reconnection or interruption event raised during or right after the connect is
        // lost, and handle each event appropriately rather than only logging it
        messagingService.addReconnectionAttemptListener(serviceEvent -> {
            // connection lost, automatic reconnect attempt in progress: publish() keeps
            // buffering, and blocks once PUBLISH_BUFFER_CAPACITY messages wait for the transport
            logger.warn("### Service reconnecting; publishing pauses until re-established: " + serviceEvent);
        });
        messagingService.addReconnectionListener(serviceEvent -> {
            // automatic reconnect succeeded: the buffered messages go out
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

        // PERSISTENT publisher with capacity-bounded back pressure: publish() blocks once
        // PUBLISH_BUFFER_CAPACITY messages wait unsent, and returns when there is room again.
        // The API default (onBackPressureElastic()) buffers without bound and the developer
        // guide cautions it can exhaust memory; onBackPressureReject(n) throws
        // PublisherOverflowException instead of blocking.
        publisher = messagingService.createPersistentMessagePublisherBuilder()
                .onBackPressureWait(PUBLISH_BUFFER_CAPACITY)
                .build();
        // register the receipt listener BEFORE start(), so no receipt is missed
        publisher.setMessagePublishReceiptListener(new PublishReceiptHandler());
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
                // user context for local ACK/NACK correlation: an immutable per-send
                // identifier that the API hands back in the publish receipt.
                // publish() blocks while the buffer is full (back pressure). During an automatic
                // reconnect the messages wait in the buffer for the transport and go out after
                // the reconnect; once the buffer is full, publish() blocks until they do.
                publisher.publish(message, Topic.of(topicString), msgId);
                msgSentCounter++;
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
        if (statsPrintingThread != null) {
            statsPrintingThread.shutdown();  // stop printing stats
        }
        if (publisher != null && publisher.isRunning()) {
            // gracefully finish outstanding ACKs before exit: terminate() waits up to the grace
            // period for the buffered messages to go out and for the broker to confirm the
            // in-flight PERSISTENT messages. Terminate the publisher before the disconnect:
            // disconnect() stops it at once and discards its buffer.
            try {
                publisher.terminate(TERMINATE_GRACE_PERIOD_MS);
            } catch (PubSubPlusClientException.IncompleteMessageDeliveryException e) {
                logger.error("Publisher stopped with messages still unacknowledged after "
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
     *  publish receipt) are a separate channel and stay as they are. */
    private static void trace(String message) {
        System.out.println(message);
    }

    ////////////////////////////////////////////////////////////////////////////

    /** Very simple static inner class, used for handling publish receipts (ACKs/NACKs) from
     *  the broker. Runs on an API thread: return promptly and do not block in it. **/
    private static class PublishReceiptHandler implements MessagePublishReceiptListener {

        @Override
        public void onPublishReceipt(PublishReceipt publishReceipt) {
            final PubSubPlusClientException e = publishReceipt.getException();
            if (e == null) {  // no exception: ACK, the broker has confirmed receipt
                logger.debug(String.format("ACK for Message ID %s", publishReceipt.getUserContext()));  // good enough, the broker has it now
            } else {  // NACK: the exception says why (ACL violation, spool over quota, no subscriber, ...)
                // This sample uses the "Discard the Message" strategy from the developer guide
                // ("Strategies for Handling Publish Receipt Errors"): log it and go on. Do not use
                // this strategy if the application cannot tolerate message loss; the guide's
                // other strategies are to wait and retry, and to retry a predefined number of times.
                logger.warn(String.format("NACK for Message ID %s - %s", publishReceipt.getUserContext(), e));
            }
        }
    }
}
