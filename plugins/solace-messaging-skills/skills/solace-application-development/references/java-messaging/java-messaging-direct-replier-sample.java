/*
 * Derived from patterns/DirectReplierNonBlocking.java
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
 * side: basic-auth connect, a RequestReplyMessageReceiver built with the request
 * topic subscription (no queue, no provisioning) and capacity-bounded buffers for the
 * requests and the replies, an async RequestMessageHandler set with receiveAsync()
 * that guards on a replier (a message without a reply-to or a correlation id cannot
 * be answered), detects discards, and answers each request with replier.reply(),
 * which routes to the request's reply-to and copies the correlation, a
 * ReplyFailureListener for the reply failures the API reports asynchronously, service
 * event listeners registered BEFORE connect(), and a graceful SIGINT/SIGTERM shutdown.
 * Request-reply in the Java API is direct only, so it is at-most-once on both legs: a
 * reply that cannot go out is logged and discarded, and the requestor times out. A
 * service interruption stops the replier with exit code 1. Answers both requestor
 * samples. The blocking variant is java-messaging-direct-replier-blocking-sample.java.
 * Structured as setupSolace(...), awaitRequests(), and teardownSolace(), with teardown
 * run from main's finally on every exit path.
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
import com.solace.messaging.receiver.RequestReplyMessageReceiver.RequestMessageHandler;
import com.solace.messaging.resources.TopicSubscription;

public class DirectReplier {

    private static final String APP_NAME = DirectReplier.class.getSimpleName();
    // request topic to subscribe to; the requestor samples send to this topic
    private static final String REQUEST_TOPIC = "solace/samples/java/direct/request";
    private static final String API = "Java";
    // back pressure, requests: once this many received requests wait for the handler, the
    // receiver discards each new incoming request until there is room (that requestor times
    // out)
    // The capacity is a count of messages, not a size in bytes: the memory it holds depends on
    // the size of each message. Tune it to the memory the application's deployment can give
    // the buffer. The API has no default capacity (its default strategy, elastic, buffers
    // without bound); 1000 is the value the Java developer guide uses in its back pressure
    // examples.
    private static final int RECEIVE_BUFFER_CAPACITY = 1000;
    // back pressure, replies: once this many replies wait unsent, replier.reply() throws
    // PublisherOverflowException until there is room. Same unit and tuning as above.
    private static final int REPLY_BUFFER_CAPACITY = 1000;
    // terminate(gracePeriod): how long the request handler gets to answer the requests the
    // API has already received, and then how long the buffered replies get to go out, before
    // the replier stops. The API applies the grace period to the request buffer first and to
    // the reply buffer second, so terminate() can take up to two grace periods; it throws
    // IncompleteMessageDeliveryException when requests or replies remain after that. The API
    // default is 600000 ms (10 minutes).
    private static final long TERMINATE_GRACE_PERIOD_MS = 10_000;
    // how long the shutdown hook waits for main's teardown: two grace periods (one for each
    // buffer) plus a margin for the main loop's 1 s sleep and the disconnect. The JVM halts
    // when the hook returns.
    private static final long SHUTDOWN_WAIT_MS = 2 * TERMINATE_GRACE_PERIOD_MS + 5_000;

    private static volatile int msgRecvCounter = 0;              // num requests received
    private static volatile boolean hasDetectedDiscard = false;  // any discards seen?
    private static volatile boolean isShutdown = false;          // are we done?
    private static volatile boolean isShutdownSignalled = false; // SIGINT/SIGTERM started a JVM shutdown
    private static volatile boolean isMainDone = false;          // main finished its teardown
    private static volatile int exitCode = 0;                    // set to 1 on a failure exit
    private static MessagingService messagingService;
    private static RequestReplyMessageReceiver receiver;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2 by default; any backend swap follows the logging rule in implement-mode.md Step 3

    /** This is the main app. Use this type of app to answer Direct (at-most-once) requests on a topic. */
    public static void main(String... args) throws InterruptedException {
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
                    mainThread.join(SHUTDOWN_WAIT_MS);  // wait for the main thread's replier stop and disconnect
                } catch (InterruptedException e) {
                    // nothing more we can do; the JVM is halting
                }
            }));
            awaitRequests();
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
            // store direct requests for this replier while it is disconnected (at-most-once)
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
            // the main loop exits and teardownSolace() runs in main's finally.
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
        // wait for the handler, the receiver discards each new incoming request. The API
        // default (elastic) buffers without bound and the developer guide cautions it can
        // exhaust memory.
        // Capacity-bounded back pressure, replies: onReplierBackPressureReject(n) makes
        // replier.reply() throw PublisherOverflowException once REPLY_BUFFER_CAPACITY replies
        // wait unsent. The request handler runs on an API thread and must not block, which
        // rules out onReplierBackPressureWait(n) here.
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
        trace(String.format("Adding direct request-topic subscription '%s'.", REQUEST_TOPIC));
        receiver.start();
        // tell the API to start delivering requests to the handler; see the bottom of the file
        // for the RequestHandler class. One receive style per receiver: receiveAsync() and
        // receiveMessage() are not for use together on one receiver.
        receiver.receiveAsync(new RequestHandler());
    }

    private static void awaitRequests() throws InterruptedException {
        // async request receive working now, so time to wait until done...
        trace(String.format("%s subscribed and ready to reply. Press Ctrl-C to quit.", APP_NAME));
        while (!isShutdown) {
            Thread.sleep(1000);  // wait 1 second
            trace(String.format("%s %s Requests/s: %,d",API,APP_NAME,msgRecvCounter));  // simple way of calculating request rates
            msgRecvCounter = 0;
            if (hasDetectedDiscard) {  // at least one request was dropped before this replier
                trace("*** Discard detected (at-most-once: a direct request was dropped) ***");
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
            // request-reply is at-most-once: there are no acknowledgements to drain, but
            // terminate() lets the handler answer the requests already received and lets the
            // buffered replies go out. Terminate the receiver before the disconnect:
            // disconnect() stops it at once and discards its buffers.
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

    ////////////////////////////////////////////////////////////////////////////

    /** Very simple static inner class, used for answering the direct requests from the topic.
     *  Runs on an API thread: return promptly, since delivery stalls while it runs. **/
    private static class RequestHandler implements RequestMessageHandler {

        // one OutboundMessageBuilder for every reply; the per-reply difference goes in
        // reply()'s additional message properties
        private final OutboundMessageBuilder messageBuilder = messagingService.messageBuilder();

        @Override
        public void onMessage(InboundMessage message, Replier replier) {
            msgRecvCounter++;
            // at-most-once discard evidence (API reference): the broker sets the broker discard
            // indication when it discarded direct messages destined for this replier for any
            // reason (e.g. a slow consumer overran its egress buffer), and the API sets the
            // internal one when its own request buffer dropped requests (back pressure, see
            // RECEIVE_BUFFER_CAPACITY). The indication arrives on the next request received
            // after the drop. There is NO redelivery: each dropped request times out at its
            // requestor.
            if (message.getMessageDiscardNotification().hasBrokerDiscardIndication()
                    || message.getMessageDiscardNotification().hasInternalDiscardIndication()) {
                hasDetectedDiscard = true;
            }
            // guard on the replier: the API passes null when the message has no reply-to
            // destination or no correlation id, and such a message cannot be answered. A
            // request-reply publisher sets both automatically; a message arriving here without
            // them is not a request.
            if (replier == null) {
                logger.warn("Received a message on the request topic with no reply-to or no correlation id; ignoring.");
                return;
            }
            final String msgId = message.getApplicationMessageId();
            try {
                // the requestor samples send a byte[] payload (the binary attachment), read
                // back with getPayloadAsBytes()
                byte[] payload = message.getPayloadAsBytes();
                String request = payload != null ? new String(payload, StandardCharsets.UTF_8) : message.getPayloadAsString();
                OutboundMessage reply = messageBuilder.build(
                        ("Reply from " + APP_NAME + " to: " + request).getBytes(StandardCharsets.UTF_8));
                // echo the request's application message id onto the reply for traceability;
                // the API copies the correlation itself, so the requestor matches the reply
                // without it
                final Properties replyProperties = new Properties();
                if (msgId != null) {
                    replyProperties.setProperty(MessageProperties.APPLICATION_MESSAGE_ID, msgId);
                }
                // reply() routes to the request's reply-to and copies the correlation id, so
                // the requestor's publishAwaitResponse() or reply handler receives it
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
    }
}
