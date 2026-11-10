/*
 * Derived from patterns/DirectReceiver.java
 *   SolaceSamples/solace-samples-java
 *   https://github.com/SolaceSamples/solace-samples-java
 *   Licensed under the Apache License, Version 2.0.
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
 * Reference sample. Wired for a basic direct (at-most-once) pub/sub journey:
 * basic-auth connect, a plain topic subscription (no queue, no provisioning), an
 * async DIRECT receiver with receiveAsync() and a capacity-bounded buffer that
 * detects discards, service event listeners registered BEFORE connect(), and a
 * graceful SIGINT/SIGTERM shutdown. Direct messaging is at-most-once: there is no
 * broker ACK and no redelivery, so there is NO ack() here (the inverse of the
 * guaranteed subscriber). A service interruption stops the subscriber with exit code
 * 1. The blocking variant is java-messaging-direct-subscriber-blocking-sample.java.
 * Structured as setupSolace(...), awaitMessages(), and teardownSolace(), with
 * teardown run from main's finally on every exit path.
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
import com.solace.messaging.receiver.MessageReceiver.MessageHandler;
import com.solace.messaging.resources.TopicSubscription;

public class DirectSubscriber {

    private static final String APP_NAME = DirectSubscriber.class.getSimpleName();
    // topic to subscribe to directly; matches the direct publisher sample's topic root
    private static final String TOPIC_NAME = "solace/samples/java/direct/pub/>";
    private static final String API = "Java";
    // back pressure: once this many received messages wait for the handler, the receiver
    // discards each new incoming message until there is room
    // The capacity is a count of messages, not a size in bytes: the memory it holds depends on
    // the size of each message. Tune it to the memory the application's deployment can give
    // the buffer. The API has no default capacity (its default, onBackPressureElastic(),
    // buffers without bound); 1000 is the value the Java developer guide uses in its back
    // pressure examples.
    private static final int RECEIVE_BUFFER_CAPACITY = 1000;
    // terminate(gracePeriod): how long the message handler gets to process the messages the
    // API has already received before the receiver stops; terminate() throws
    // IncompleteMessageDeliveryException when messages are still in its buffer after that.
    // The API default is 600000 ms (10 minutes).
    private static final long TERMINATE_GRACE_PERIOD_MS = 10_000;
    // how long the shutdown hook waits for main's teardown: the grace period plus a margin
    // for the disconnect. The JVM halts when the hook returns.
    private static final long SHUTDOWN_WAIT_MS = TERMINATE_GRACE_PERIOD_MS + 5_000;

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
    public static void main(String... args) throws InterruptedException {
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
                    mainThread.join(SHUTDOWN_WAIT_MS);  // wait for the main thread's receiver stop and disconnect
                } catch (InterruptedException e) {
                    // nothing more we can do; the JVM is halting
                }
            }));
            awaitMessages();
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
            // connection lost, automatic reconnect attempt in progress
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
            // the main loop exits and teardownSolace() runs in main's finally.
            exitCode = 1;
            isShutdown = true;
        });
        messagingService.connect();  // blocking connect

        // DIRECT receiver: a topic subscription on the receiver (no queue, no provisioning).
        // start() adds the subscription; direct messaging is at-most-once, so messages flow
        // straight to the subscriber with no broker ACK and no redelivery.
        // Capacity-bounded back pressure: once RECEIVE_BUFFER_CAPACITY messages wait for the
        // handler, onBackPressureDropLatest(n) discards each new incoming message, and the
        // next message delivered carries an internal discard indication. The API default
        // (onBackPressureElastic()) buffers without bound and the developer guide cautions it
        // can exhaust memory; onBackPressureDropOldest(n) discards the oldest buffered message
        // instead.
        receiver = messagingService.createDirectMessageReceiverBuilder()
                .withSubscriptions(TopicSubscription.of(TOPIC_NAME))
                .onBackPressureDropLatest(RECEIVE_BUFFER_CAPACITY)
                .build();
        trace(String.format("Adding direct topic subscription '%s'.", TOPIC_NAME));
        receiver.start();
        // tell the API to start delivering messages to the handler; see the bottom of the file
        // for the DirectMessageHandler class. One receive style per receiver: the API reference
        // states that receiveAsync() and receiveMessage() are mutually exclusive on one receiver.
        receiver.receiveAsync(new DirectMessageHandler());
    }

    private static void awaitMessages() throws InterruptedException {
        // async direct receive working now, so time to wait until done...
        trace(String.format("%s subscribed and consuming. Press Ctrl-C to quit.", APP_NAME));
        while (!isShutdown) {
            Thread.sleep(1000);  // wait 1 second
            trace(String.format("%s %s Received msgs/s: %,d",API,APP_NAME,msgRecvCounter));  // simple way of calculating message rates
            msgRecvCounter = 0;
            if (hasDetectedDiscard) {  // at least one message was dropped before this subscriber
                trace("*** Discard detected (at-most-once: a direct message was dropped) ***");
                hasDetectedDiscard = false;  // only show the warning once per second
            }
        }
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (normal quit, SIGINT/SIGTERM, service interruption, or an
        // exception), so Solace teardown and application-side cleanup are never skipped.
        isShutdown = true;
        if (receiver != null && receiver.isRunning()) {
            // direct is at-most-once: there are no acknowledgements to drain, but terminate()
            // lets the handler process the messages already received. Terminate the receiver
            // before the disconnect: disconnect() stops it at once and discards its buffer.
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

    ////////////////////////////////////////////////////////////////////////////

    /** Very simple static inner class, used for receiving direct messages from the topic. Runs
     *  on an API thread: return promptly, since delivery stalls while it runs. **/
    private static class DirectMessageHandler implements MessageHandler {

        @Override
        public void onMessage(InboundMessage message) {
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
            // Direct messaging is at-most-once: there is no consumer-side acknowledgement
            // here (the inverse of the guaranteed CLIENT-ack subscriber). The message is
            // consumed as it arrives; the broker holds no copy and expects no ack.
        }
    }
}
