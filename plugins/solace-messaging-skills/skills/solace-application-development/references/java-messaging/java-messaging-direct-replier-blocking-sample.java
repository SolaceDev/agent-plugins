/*
 * Derived from patterns/DirectReplierBlocking.java
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
 * Reference sample. Wired for a basic direct request-reply journey on the replier
 * side, BLOCKING variant: basic-auth connect, a RequestReplyMessageReceiver built
 * with the request topic subscription (no queue, no provisioning) and
 * capacity-bounded buffers for the requests and the replies, a main loop that calls
 * receiveMessage(handler, timeout), whose RequestMessageHandler guards on a replier
 * (a message without a reply-to or a correlation id cannot be answered), detects
 * discards, and answers each request with replier.reply(), which routes to the
 * request's reply-to and copies the correlation, a ReplyFailureListener for the reply
 * failures the API reports asynchronously, service event listeners registered BEFORE
 * connect(), and a graceful SIGINT/SIGTERM shutdown. Request-reply in the Java API is
 * direct only, so it is at-most-once on both legs: a reply that cannot go out is
 * logged and discarded, and the requestor times out. A service interruption or a
 * receive failure stops the replier with exit code 1. Answers both requestor samples.
 * The non-blocking variant is java-messaging-direct-replier-sample.java. Structured
 * as setupSolace(...), receiveRequests(), and teardownSolace(), with teardown run
 * from main's finally on every exit path.
 *
 * Why blocking: the application's own thread is the single thread of control for the
 * messaging data path. The main thread takes each request from the receiver and
 * answers it, so no request handler runs on an API thread and no application state
 * is shared with one. This sample makes no throughput claim. The cost: the loop
 * answers one request at a time, so a slow loop fills the request buffer sooner and
 * discards more requests, and a shutdown signal takes effect only when
 * receiveMessage() returns. The service event listeners and the reply failure
 * listener still run on API threads.
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
import com.solace.messaging.config.SolaceConstants.ReceiverConstants;
import com.solace.messaging.config.SolaceProperties.MessageProperties;
import com.solace.messaging.config.SolaceProperties.ReceiverProperties;
import com.solace.messaging.config.SolaceProperties.TransportLayerProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.OutboundMessageBuilder;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.receiver.RequestReplyMessageReceiver;
import com.solace.messaging.receiver.RequestReplyMessageReceiver.Replier;
import com.solace.messaging.resources.TopicSubscription;

public class DirectReplierBlocking {

    private static final String APP_NAME = DirectReplierBlocking.class.getSimpleName();
    // request topic to subscribe to; the requestor samples send to this topic
    private static final String REQUEST_TOPIC = "solace/samples/java/direct/request";
    private static final String API = "Java";
    // receiveMessage(handler, timeout) blocks up to this many MILLISECONDS for the next
    // request, then returns without a call to the handler. The loop checks the shutdown flag
    // each time the call returns, so this value bounds how long a shutdown signal waits
    // during an empty wait. 1 s keeps Ctrl-C prompt and matches the rate report interval; a
    // longer timeout wakes the loop less often but stops slower. Do not call
    // receiveMessage(handler) without a timeout: it then waits without a limit and a shutdown
    // signal never takes effect while no request arrives.
    private static final long RECEIVE_TIMEOUT_MS = 1000;
    // how often the receive loop reports its request rate, in milliseconds
    private static final long STATS_INTERVAL_MS = 1000;
    // back pressure, requests: once this many received requests wait for the receive loop,
    // the receiver discards each new incoming request until there is room (that requestor
    // times out)
    // The capacity is a count of messages, not a size in bytes: the memory it holds depends on
    // the size of each message. Tune it to the memory the application's deployment can give
    // the buffer. The API has no default capacity (its default strategy, elastic, buffers
    // without bound); 1000 is the value the Java developer guide uses in its back pressure
    // examples.
    private static final int RECEIVE_BUFFER_CAPACITY = 1000;
    // back pressure, replies: once this many replies wait unsent, replier.reply() throws
    // PublisherOverflowException until there is room. Same unit and tuning as above.
    private static final int REPLY_BUFFER_CAPACITY = 1000;
    // terminate(gracePeriod): how long the buffered replies get to go out before the replier
    // stops. The API applies the grace period to the request buffer first and to the reply
    // buffer second, so terminate() can take up to two grace periods. Only the receive loop
    // takes requests here, and that loop has stopped by teardown: when requests still wait in
    // the request buffer, terminate() waits the full grace period for them, then throws
    // IncompleteMessageDeliveryException, and those requests are lost (at-most-once). A short
    // grace period keeps that exit prompt.
    private static final long TERMINATE_GRACE_PERIOD_MS = 1_000;
    // how long the shutdown hook waits for main's teardown: one receive timeout, plus two
    // grace periods (one for each buffer) and a margin for the request in progress and the
    // disconnect. The JVM halts when the hook returns.
    private static final long SHUTDOWN_WAIT_MS = RECEIVE_TIMEOUT_MS + 2 * TERMINATE_GRACE_PERIOD_MS + 5_000;

    private static volatile int msgRecvCounter = 0;              // num requests received
    private static volatile boolean hasDetectedDiscard = false;  // any discards seen?
    private static volatile boolean isShutdown = false;          // are we done?
    private static volatile boolean isShutdownSignalled = false; // SIGINT/SIGTERM started a JVM shutdown
    private static volatile boolean isMainDone = false;          // main finished its teardown
    private static volatile int exitCode = 0;                    // set to 1 on a failure exit
    private static MessagingService messagingService;
    private static RequestReplyMessageReceiver receiver;
    // a single OutboundMessageBuilder reused for every reply; the per-reply difference goes in
    // reply()'s additional message properties
    private static OutboundMessageBuilder replyBuilder;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2 by default; any backend swap follows the logging rule in implement-mode.md Step 3

    /** This is the main app. Use this type of app to answer Direct (at-most-once) requests on a topic. */
    public static void main(String... args) {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            setupSolace(args);
            // graceful shutdown: a SIGINT (Ctrl-C) or SIGTERM signals the main loop to stop,
            // then the hook joins the main thread so the cleanup in teardownSolace() (stop the
            // replier, disconnect) runs to completion before the JVM halts. The JVM exits as
            // soon as all shutdown hooks return, so the hook must wait, not just set a flag.
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (isMainDone) {  // main already tore down and is exiting with exitCode
                    return;
                }
                isShutdownSignalled = true;
                trace("Shutdown signal received, stopping replier...");
                isShutdown = true;
                try {
                    mainThread.join(SHUTDOWN_WAIT_MS);  // wait for the receive loop to return, then the replier stop and disconnect
                } catch (InterruptedException e) {
                    // nothing more we can do; the JVM is halting
                }
            }));
            receiveRequests();
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
        // ServiceProperties.RECEIVER_DIRECT_SUBSCRIPTION_REAPPLY re-adds the direct request
        // topic subscription automatically after an automatic reconnect.
        // ConfigurationProfile.V1 already sets it to true (the JCSMP default is false), so
        // this sample does not set it. A direct subscription is not held on a broker queue,
        // so without it the replier would silently stop answering after a reconnect.
        messagingService = MessagingService.builder(ConfigurationProfile.V1)
                .fromProperties(properties)
                .build();
        // best practice: register the service event listeners BEFORE connect(), so no
        // reconnection or interruption event raised during or right after the connect is
        // lost, and handle each event appropriately rather than only logging it
        messagingService.addReconnectionAttemptListener(serviceEvent -> {
            // connection lost, automatic reconnect attempt in progress: the broker does not
            // store direct requests for this replier while it is disconnected (at-most-once);
            // receiveMessage() keeps timing out and the loop keeps going
            logger.warn("### Service reconnecting; request delivery is paused until re-established: " + serviceEvent);
        });
        messagingService.addReconnectionListener(serviceEvent -> {
            // automatic reconnect succeeded; the subscription is reapplied and delivery resumes
            logger.info("### Service reconnected; the subscription is restored and replies resume: " + serviceEvent);
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

        // request-reply receiver: the request topic subscription goes to build() (no queue,
        // no provisioning), and start() adds it. To spread requests across several replier
        // instances, pass a share name: build(subscription, ShareName.of("name")), and each
        // request then goes to one member of the group.
        // Capacity-bounded back pressure, requests: the builder has no method for the request
        // buffer, so the two receiver properties set it. Once RECEIVE_BUFFER_CAPACITY requests
        // wait for the receive loop, the receiver discards each new incoming request. The API
        // default (elastic) buffers without bound and the developer guide cautions it can
        // exhaust memory.
        // Capacity-bounded back pressure, replies: onReplierBackPressureReject(n) makes
        // replier.reply() throw PublisherOverflowException once REPLY_BUFFER_CAPACITY replies
        // wait unsent. onReplierBackPressureWait(n) blocks reply() instead; a blocked reply()
        // does not see the shutdown flag, so this sample rejects.
        final Properties receiverProperties = new Properties();
        receiverProperties.setProperty(ReceiverProperties.DIRECT_BACK_PRESSURE_STRATEGY,
                ReceiverConstants.RECEIVER_BACK_PRESSURE_STRATEGY_DROP_LATEST);
        receiverProperties.setProperty(ReceiverProperties.DIRECT_BACK_PRESSURE_BUFFER_CAPACITY,
                String.valueOf(RECEIVE_BUFFER_CAPACITY));
        receiver = messagingService.requestReply()
                .createRequestReplyMessageReceiverBuilder()
                .fromProperties(receiverProperties)
                .onReplierBackPressureReject(REPLY_BUFFER_CAPACITY)
                .build(TopicSubscription.of(REQUEST_TOPIC));
        // register the reply failure listener BEFORE start(), so no failure is missed. It runs
        // on an API thread and can be called for ACL violations and connection loss; direct
        // messaging has no broker ACK, so this is the only signal that a reply the API
        // accepted did not go out. The requestor of that reply times out.
        receiver.setReplyFailureListener(failedReplyEvent -> {
            logger.warn("### Direct reply failed for destination " + failedReplyEvent.getDestination(),
                    failedReplyEvent.getException());
        });
        replyBuilder = messagingService.messageBuilder();
        trace(String.format("Adding direct request-topic subscription '%s'.", REQUEST_TOPIC));
        receiver.start();
        // no receiveAsync(): the receive loop takes each request with receiveMessage(). One
        // receive style per receiver: the two are not for use together on one receiver.
    }

    private static void receiveRequests() {
        trace(String.format("%s subscribed and ready to reply. Press Ctrl-C to quit.", APP_NAME));
        long nextReport = System.currentTimeMillis() + STATS_INTERVAL_MS;
        while (!isShutdown) {
            try {
                // blocks up to RECEIVE_TIMEOUT_MS for the next request, then runs
                // processRequest() for it ON THIS THREAD before it returns. On a timeout it
                // returns without a call to processRequest(); either way the loop goes round
                // and the shutdown check above decides whether to stop
                receiver.receiveMessage(DirectReplierBlocking::processRequest, RECEIVE_TIMEOUT_MS);
            } catch (RuntimeException e) {
                // thrown once the service is down or the receiver is terminated. A service
                // interruption has already set isShutdown and exitCode
                if (!isShutdown) {
                    logger.error("receiveMessage() failed, quitting", e);
                    exitCode = 1;
                }
                break;
            }
            // simple way of calculating request rates, inline on this thread: no stats thread,
            // so the main thread stays the only application thread
            long now = System.currentTimeMillis();
            if (now >= nextReport) {
                trace(String.format("%s %s Requests/s: %,d",API,APP_NAME,msgRecvCounter));
                msgRecvCounter = 0;
                if (hasDetectedDiscard) {  // at least one request was dropped before this replier
                    trace("*** Discard detected (at-most-once: a direct request was dropped) ***");
                    hasDetectedDiscard = false;  // only show the warning once per report
                }
                nextReport = now + STATS_INTERVAL_MS;
            }
        }
    }

    /** The RequestMessageHandler of the receive loop: answers one request. receiveMessage()
     *  calls it on the main thread. The API catches and logs an exception that escapes it. */
    private static void processRequest(InboundMessage message, Replier replier) {
        msgRecvCounter++;
        // at-most-once discard evidence (API reference): the broker sets the broker discard
        // indication when it discarded direct messages destined for this replier for any reason
        // (e.g. a slow consumer overran its egress buffer), and the API sets the internal one
        // when its own request buffer dropped requests (back pressure, see
        // RECEIVE_BUFFER_CAPACITY). The indication arrives on the next request received after
        // the drop. There is NO redelivery: each dropped request times out at its requestor.
        if (message.getMessageDiscardNotification().hasBrokerDiscardIndication()
                || message.getMessageDiscardNotification().hasInternalDiscardIndication()) {
            hasDetectedDiscard = true;
        }
        // guard on the replier: the API passes null when the message has no reply-to
        // destination or no correlation id, and such a message cannot be answered. A
        // request-reply publisher sets both automatically; a message arriving here without them
        // is not a request.
        if (replier == null) {
            logger.warn("Received a message on the request topic with no reply-to or no correlation id; ignoring.");
            return;
        }
        final String msgId = message.getApplicationMessageId();
        try {
            // the requestor samples send a byte[] payload (the binary attachment), read back
            // with getPayloadAsBytes()
            byte[] payload = message.getPayloadAsBytes();
            String request = payload != null ? new String(payload, StandardCharsets.UTF_8) : message.getPayloadAsString();
            OutboundMessage reply = replyBuilder.build(
                    ("Reply from " + APP_NAME + " to: " + request).getBytes(StandardCharsets.UTF_8));
            // echo the request's application message id onto the reply for traceability; the
            // API copies the correlation itself, so the requestor matches the reply without it
            final Properties replyProperties = new Properties();
            if (msgId != null) {
                replyProperties.setProperty(MessageProperties.APPLICATION_MESSAGE_ID, msgId);
            }
            // reply() routes to the request's reply-to and copies the correlation id, so the
            // requestor's publishAwaitResponse() or reply handler receives it
            replier.reply(reply, replyProperties);
        } catch (PubSubPlusClientException.PublisherOverflowException e) {
            // back pressure: REPLY_BUFFER_CAPACITY replies wait unsent. Discard this reply
            // (at-most-once); the requestor times out.
            logger.warn("Reply buffer is full; discarding the reply for message id " + msgId);
        } catch (RuntimeException e) {
            // the reply could not be sent (for example the service is down); the requestor
            // times out
            logger.warn("### Caught while trying to replier.reply() for message id " + msgId, e);
        }
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (normal quit, SIGINT/SIGTERM, service interruption, or an
        // exception), so Solace teardown and application-side cleanup are never skipped.
        isShutdown = true;
        if (receiver != null && receiver.isRunning()) {
            // let the buffered replies go out (see TERMINATE_GRACE_PERIOD_MS): the receive
            // loop has stopped, so nothing takes more requests from the API's buffer.
            // Terminate the receiver before the disconnect: disconnect() stops it at once
            // and discards its buffers.
            try {
                receiver.terminate(TERMINATE_GRACE_PERIOD_MS);
            } catch (PubSubPlusClientException.IncompleteMessageDeliveryException e) {
                logger.warn("Replier stopped with requests or replies still in its buffers; they are lost (at-most-once)", e);
            } catch (RuntimeException e) {
                logger.error("Replier terminate() failed", e);
                exitCode = 1;
            }
        }
        if (messagingService != null && messagingService.isConnected()) {
            messagingService.disconnect();  // will also release the replier
        }
    }

    /** Demo narration sink: every status line in this sample funnels through this one
     *  method. An application replaces this single body to route narration to its
     *  logger or reporting system. The log4j2 logger calls for API events (service,
     *  reply failure) are a separate channel and stay as they are. */
    private static void trace(String message) {
        System.out.println(message);
    }
}
