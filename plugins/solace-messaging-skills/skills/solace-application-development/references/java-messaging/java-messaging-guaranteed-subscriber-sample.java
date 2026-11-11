/*
 * Derived from patterns/GuaranteedReceiver.java
 *   SolaceSamples/solace-samples-java
 *   https://github.com/SolaceSamples/solace-samples-java
 *   Licensed under the Apache License, Version 2.0.
 *
 * Elevated to documented best practices per the Java API developer guide:
 *   Messaging Service:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-Messaging-Service.md
 *   Consuming Persistent Messages:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-PM-Subscribe.md
 *   Creating Queues with the Solace Java API:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Create-Queues.md
 *   Configuring Logging:
 *     https://docs.solace.com/API/API-Developer-Guide-Java/Java-API-Logging.md
 *   the Java API reference (lifecycle, listener contracts):
 *     https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/overview-summary.md
 *   and the JCSMP Best Practices guide (the Java API wraps JCSMP, so its reconnect and
 *   acknowledgement practices apply):
 *     https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *
 * Reference sample. Wired for a basic publish-subscribe journey: basic-auth
 * connect, in-process provisioning of a durable exclusive queue plus a topic
 * subscription so a fresh broker works out of the box, a CLIENT-ack receiver with
 * receiveAsync() that ACKs only after processing, receiver active/passive state and
 * termination handling, service event listeners registered BEFORE connect(), and a
 * graceful SIGINT/SIGTERM shutdown. A service interruption or a receiver termination
 * stops the subscriber with exit code 1. The blocking variant is
 * java-messaging-guaranteed-subscriber-blocking-sample.java. Structured as
 * setupSolace(...), awaitMessages(), and teardownSolace(), with teardown run from
 * main's finally on every exit path.
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
import com.solace.messaging.config.MissingResourcesCreationConfiguration.MissingResourcesCreationStrategy;
import com.solace.messaging.config.ReceiverActivationPassivationConfiguration.ReceiverStateChangeListener;
import com.solace.messaging.config.ReceiverActivationPassivationConfiguration.ReceiverStateChangeListener.ReceiverState;
import com.solace.messaging.config.SolaceProperties.TransportLayerProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.receiver.MessageReceiver.MessageHandler;
import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
import com.solace.messaging.resources.TopicSubscription;

public class GuaranteedSubscriber {

    private static final String APP_NAME = GuaranteedSubscriber.class.getSimpleName();
    private static final String QUEUE_NAME = "q_java_sub";
    // topic to map onto the queue; matches the publisher sample's topic root
    private static final String TOPIC_NAME = "solace/samples/java/pers/pub/>";
    private static final String API = "Java";
    // terminate(gracePeriod): how long the message handler gets to take the messages the API
    // has already received out of its buffer before the receiver stops; terminate() throws
    // IncompleteMessageDeliveryException when messages are still in the buffer after that.
    // terminate() unbinds from the queue BEFORE the grace period starts, so an ack() made
    // during the grace period fails. For that reason the handler skips these messages (see
    // QueueMessageHandler): they were never ACKed, so they stay on the queue and are
    // redelivered. The API default is 600000 ms (10 minutes).
    private static final long TERMINATE_GRACE_PERIOD_MS = 10_000;
    // how long the shutdown hook waits for main's teardown: the grace period plus a margin
    // for the disconnect. The JVM halts when the hook returns.
    private static final long SHUTDOWN_WAIT_MS = TERMINATE_GRACE_PERIOD_MS + 5_000;

    private static volatile int msgRecvCounter = 0;                 // num messages received
    private static volatile boolean hasDetectedRedelivery = false;  // detected any messages being redelivered?
    private static volatile boolean isShutdown = false;             // are we done?
    private static volatile boolean isShutdownSignalled = false;    // SIGINT/SIGTERM started a JVM shutdown
    private static volatile boolean isMainDone = false;             // main finished its teardown
    private static volatile int exitCode = 0;                       // set to 1 on a failure exit
    // held by the handler while it processes and ACKs one message, and taken by
    // teardownSolace() before terminate(), so no message is between processing and ACK
    // when the receiver unbinds
    private static final Object processingLock = new Object();
    private static MessagingService messagingService;
    private static PersistentMessageReceiver receiver;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2 by default; any backend swap follows the logging rule in implement-mode.md Step 3

    /** This is the main app.  Use this type of app for receiving Guaranteed messages (e.g. via a queue endpoint). */
    public static void main(String... args) throws InterruptedException {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            if (setupSolace(args)) {
                // graceful shutdown: a SIGINT (Ctrl-C) or SIGTERM signals the main loop to
                // stop, then the hook joins the main thread so the cleanup in
                // teardownSolace() (finish the message in hand, stop the receiver, disconnect)
                // runs to completion before the JVM halts. The JVM exits as soon as all
                // shutdown hooks return, so the hook must wait, not just set a flag.
                final Thread mainThread = Thread.currentThread();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (isMainDone) {  // main already tore down and is exiting with exitCode
                        return;
                    }
                    isShutdownSignalled = true;
                    trace("Shutdown signal received, stopping consumer...");
                    isShutdown = true;
                    try {
                        mainThread.join(SHUTDOWN_WAIT_MS);  // wait for the main thread's receiver stop and disconnect
                    } catch (InterruptedException e) {
                        // nothing more we can do; the JVM is halting
                    }
                }));
                awaitMessages();
            }
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

    private static boolean setupSolace(String[] args) {
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
        // SUB_ACK_WINDOW_SIZE: the persistent receiver's window is 255 (range 1-255) under
        // ConfigurationProfile.V1, the same as the JCSMP default: the max Guaranteed messages
        // the broker sends before the API must ACK. Lower it to cut per-client broker buffer
        // use when binding many receivers or consuming large messages; see the JCSMP Best
        // Practices buffer-sizing guidance.
        messagingService = MessagingService.builder(ConfigurationProfile.V1)
                .fromProperties(properties)
                .build();
        // best practice: register the service event listeners BEFORE connect(), so no
        // reconnection or interruption event raised during or right after the connect is
        // lost, and handle each event appropriately rather than only logging it
        messagingService.addReconnectionAttemptListener(serviceEvent -> {
            // connection lost, automatic reconnect attempt in progress
            logger.warn("### Service reconnecting; message delivery is paused until re-established: " + serviceEvent);
        });
        messagingService.addReconnectionListener(serviceEvent -> {
            // automatic reconnect succeeded; the receiver rebinds to the queue and delivery resumes
            logger.info("### Service reconnected; the receiver rebinds and delivery resumes: " + serviceEvent);
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

        // ELEVATION: provision the durable queue in-process and map the topic onto it at
        // start, so a fresh broker works out of the box. CREATE_ON_START creates the queue
        // passed to build() when start() runs, as long as the client has permission; the
        // default, DO_NOT_CREATE, disables provisioning so an administrator provisions the
        // queue instead. withSubscriptions() adds the topic subscription to the queue at
        // start (pub/sub onto a queue). A re-run reuses the existing queue and subscription.
        receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMissingResourcesCreationStrategy(MissingResourcesCreationStrategy.CREATE_ON_START)
                .withSubscriptions(TopicSubscription.of(TOPIC_NAME))
                // best practice: client acknowledgement (also the API default), so a message leaves
                // the queue only after the handler has processed it and called ack()
                .withMessageClientAcknowledgement()
                // request active/passive state changes: on an exclusive queue only one bound receiver
                // is ACTIVE; any other is PASSIVE (bound, not receiving) until the active one goes away
                .withActivationPassivationSupport(new ReceiverStateHandler())
                .build(Queue.durableExclusiveQueue(QUEUE_NAME));  // durable, exclusive (single-consumer sample)
        // a broker-initiated termination (for example the queue was deleted or shut down)
        // surfaces here: decide whether to recreate the receiver or shut down. This sample
        // shuts down: isShutdown ends the main loop and teardownSolace() runs in main's finally.
        receiver.setTerminationNotificationListener(terminationEvent -> {
            logger.error("Receiver terminated, delivery from queue " + QUEUE_NAME + " has stopped: "
                    + terminationEvent.getMessage(), terminationEvent.getCause());
            exitCode = 1;
            isShutdown = true;
        });

        trace(String.format("Attempting to bind to queue '%s' on the broker.", QUEUE_NAME));
        try {
            // start() provisions the queue plus its subscription (CREATE_ON_START) and binds
            // to the queue
            receiver.start();
        } catch (RuntimeException e) {
            logger.error(String.format("Could not bind to queue '%s'. If this client may not create endpoints, "
                    + "provision the queue and its topic subscription out-of-band or grant the "
                    + "capability. Exiting.", QUEUE_NAME), e);
            exitCode = 1;
            return false;  // teardownSolace() in main's finally disconnects
        }
        // tell the API to start delivering messages to the handler; see the bottom of the file
        // for the QueueMessageHandler class. One receive style per receiver: the API reference
        // states that receiveAsync() and receiveMessage() are mutually exclusive on one receiver.
        receiver.receiveAsync(new QueueMessageHandler());
        return true;
    }

    private static void awaitMessages() throws InterruptedException {
        // async queue receive working now, so time to wait until done...
        trace(APP_NAME + " connected, and running. Press Ctrl-C to quit.");
        while (!isShutdown) {
            Thread.sleep(1000);  // wait 1 second
            trace(String.format("%s %s Received msgs/s: %,d",API,APP_NAME,msgRecvCounter));  // simple way of calculating message rates
            msgRecvCounter = 0;
            if (hasDetectedRedelivery) {  // try shutting -> enabling the queue on the broker to see this
                trace("*** Redelivery detected ***");
                hasDetectedRedelivery = false;  // only show the error once per second
            }
        }
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (normal quit, SIGINT/SIGTERM, service interruption, receiver
        // termination, or an exception), so Solace teardown and application-side cleanup
        // are never skipped.
        // wait for the handler to finish and ACK the message it has in hand. From here on the
        // handler skips every message, so no message is processed without its ACK.
        synchronized (processingLock) {
            isShutdown = true;
        }
        if (receiver != null && receiver.isRunning()) {
            // gracefully stop the receiver: terminate() unbinds from the queue, then gives the
            // handler up to the grace period to take the messages already received out of the
            // buffer. The handler skips them without an ACK, so the broker redelivers them.
            // Terminate the receiver before the disconnect: disconnect() stops it at once
            // and discards its buffer.
            try {
                receiver.terminate(TERMINATE_GRACE_PERIOD_MS);
            } catch (PubSubPlusClientException.IncompleteMessageDeliveryException e) {
                // the handler did not empty the buffer inside the grace period. Those messages
                // were never ACKed, so they stay on the queue and are redelivered: a normal
                // stop, not a failure
                logger.warn("Receiver stopped with unprocessed messages in its buffer; the broker redelivers them", e);
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
     *  logger or reporting system. The log4j2 logger calls for API events (service,
     *  receiver state, termination) are a separate channel and stay as they are. */
    private static void trace(String message) {
        System.out.println(message);
    }

    ////////////////////////////////////////////////////////////////////////////

    /** Very simple static inner class, used for receiving messages from the queue. Runs on
     *  an API thread: return promptly, since delivery stalls while it runs. **/
    private static class QueueMessageHandler implements MessageHandler {

        @Override
        public void onMessage(InboundMessage message) {
            // processing and ACK of one message are one unit under processingLock:
            // teardownSolace() takes the same lock before terminate()
            synchronized (processingLock) {
                if (isShutdown) {
                    // shutting down: the receiver is about to unbind (or already did), and an
                    // ack() after the unbind fails. Do not process the message; it was not
                    // ACKed, so it stays on the queue and the broker redelivers it (with the
                    // redelivered flag set, although this application did not process it).
                    return;
                }
                try {
                    processMessage(message);
                } catch (RuntimeException e) {
                    // WARNING, MESSAGE LOSS BY DESIGN: this sample discards a message that fails
                    // processing. The failed message is ACKed below and leaves the queue for good.
                    // Without this catch the API only logs the exception, and the message stays
                    // unacknowledged on the flow.
                    //
                    // Do NOT use this strategy if the application cannot tolerate message loss.
                    // Alternatives:
                    //   1. Do not ACK: return without the ack() call. The message stays on the queue
                    //      and the broker redelivers it after the next bind (risk: a poison-message
                    //      loop when the same message fails every time).
                    //   2. NACK explicitly: build the receiver with
                    //      withRequiredMessageClientOutcomeOperationSupport(Outcome.FAILED, Outcome.REJECTED)
                    //      and call receiver.settle(message, outcome). Outcome.FAILED: the broker
                    //      redelivers the message, and can move it to the DMQ (dead message queue)
                    //      once the queue's max redelivery count is reached. Outcome.REJECTED: no
                    //      redelivery; the broker moves the message to the DMQ, or deletes it when
                    //      no DMQ is configured.
                    //   3. Retry the processing a bounded number of times before the discard.
                    logger.error("Processing failed for message id " + message.getApplicationMessageId()
                            + "; acknowledging anyway (the message is discarded)", e);
                }
                // Messages are removed from the broker queue when the ACK is received.
                // ACK only after processing is complete: DO NOT ACK until all processing/storing
                // of this message is done. NOTE that messages can be acknowledged from a different thread.
                try {
                    receiver.ack(message);  // ACKs are asynchronous
                } catch (RuntimeException e) {
                    // the ACK did not reach the API (for example the broker terminated the receiver,
                    // or the service went down): the message stays on the queue and is redelivered
                    logger.warn("ACK failed for message id " + message.getApplicationMessageId()
                            + "; the broker redelivers it", e);
                }
            }
        }

        private void processMessage(InboundMessage message) {
            msgRecvCounter++;
            // the publisher sample sends a byte[] payload (the binary attachment), read back
            // with getPayloadAsBytes()
            byte[] payload = message.getPayloadAsBytes();
            if (payload != null) {
                trace(API + " " + APP_NAME + " received: " + new String(payload, StandardCharsets.UTF_8));
            }
            if (message.isRedelivered()) {  // useful check
                // this is the broker telling the consumer that this message has been sent and not ACKed before.
                // this can happen if an exception is thrown, or the broker restarts, or the network disconnects
                // perhaps an error in processing? Should do extra checks to avoid duplicate processing
                hasDetectedRedelivery = true;
            }
        }
    }

    /** Reports whether this receiver is the one the broker delivers to. Runs on an API thread:
     *  return promptly and do not block in it. **/
    private static class ReceiverStateHandler implements ReceiverStateChangeListener {

        @Override
        public void onStateChange(ReceiverState oldState, ReceiverState newState, long changeTimestamp) {
            if (newState == ReceiverState.ACTIVE) {  // bound and actively receiving from the queue
                logger.info("Receiver active: consuming from queue " + QUEUE_NAME);
            } else {  // PASSIVE: bound but not receiving (another consumer is active on this exclusive queue)
                logger.warn("Receiver passive: bound but not receiving (another consumer is active on exclusive queue "
                        + QUEUE_NAME + ")");
            }
        }
    }
}
