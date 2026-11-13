/*
 * Derived from patterns/DirectRequestorNonBlocking.java
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
 * side, NON-BLOCKING variant: basic-auth connect, a RequestReplyMessagePublisher,
 * several non-blocking publish() calls with a POSITIVE reply timeout that each return
 * at once, so the requests are in flight together, a ReplyMessageHandler that handles
 * the correlated reply, the timeout, and the failure of each request, service event
 * listeners registered BEFORE connect(), and a graceful SIGINT/SIGTERM shutdown. The
 * requestor sends its requests, waits until every request has its result, and exits
 * (0 when every request sent got a reply, 1 otherwise). There is no retry.
 * Request-reply in the Java API is direct only, so it is at-most-once on both legs.
 * The blocking variant, one request at a time, is
 * java-messaging-direct-requestor-blocking-sample.java. Structured as
 * setupSolace(...), sendRequests(), and teardownSolace(), with teardown run from
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solace.messaging.MessagingService;
import com.solace.messaging.PubSubPlusClientException;
import com.solace.messaging.config.SolaceProperties.TransportLayerProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.OutboundMessageBuilder;
import com.solace.messaging.publisher.RequestReplyMessagePublisher;
import com.solace.messaging.publisher.RequestReplyMessagePublisher.ReplyMessageHandler;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.resources.Topic;

public class DirectRequestor {

    private static final String APP_NAME = DirectRequestor.class.getSimpleName();
    // request topic the replier subscribes to; matches the direct replier samples
    private static final String REQUEST_TOPIC = "solace/samples/java/direct/request";
    private static final String API = "Java";
    // how many requests go out
    private static final int REQUEST_COUNT = 5;
    // POSITIVE reply timeout in MILLISECONDS, per request: the reply handler gets the
    // correlated reply, or a TimeoutException once this long passes without one. The API has
    // no default and requires a value of 1 or more.
    private static final long REQUEST_TIMEOUT_MS = 3000;
    // how often the wait for the request results checks the shutdown flag, in milliseconds
    private static final long RESULT_WAIT_INTERVAL_MS = 1000;
    // terminate(gracePeriod): how long to wait for requests still waiting on their replies
    // before the requestor stops; terminate() throws IncompleteMessageDeliveryException when
    // requests are still outstanding after that. The API default is 600000 ms (10 minutes).
    private static final long TERMINATE_GRACE_PERIOD_MS = 10_000;
    // how long the shutdown hook waits for main's teardown: the grace period plus a margin
    // for the disconnect. The JVM halts when the hook returns.
    private static final long SHUTDOWN_WAIT_MS = TERMINATE_GRACE_PERIOD_MS + 5_000;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2 by default; any backend swap follows the logging rule in implement-mode.md Step 3

    private static final AtomicInteger replyCounter = new AtomicInteger();  // num correlated replies received
    // counts down once per request when its result (reply, timeout, or failure) arrives
    private static final CountDownLatch requestResults = new CountDownLatch(REQUEST_COUNT);
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
                    mainThread.join(SHUTDOWN_WAIT_MS);  // wait for the main thread's requestor stop and disconnect
                } catch (InterruptedException e) {
                    // nothing more we can do; the JVM is halting
                }
            }));
            sendRequests();
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
            // that request then times out
            logger.warn("### Service reconnecting; requests in flight can time out: " + serviceEvent);
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
            // further request goes out, the wait for the results ends, and
            // teardownSolace() runs in main's finally.
            exitCode = 1;
            isShutdown = true;
        });
        messagingService.connect();  // blocking connect

        // request-reply publisher: the API sets the reply-to topic (the service's own inbox
        // topic, the same for every request) and a unique correlation id on each request, and
        // correlates each reply to its request, so the requestor needs no reply subscription
        // and no correlation id of its own. Its builder has no back-pressure method (unlike
        // the direct publisher), so there is nothing more to configure.
        requestor = messagingService.requestReply()
                .createRequestReplyMessagePublisherBuilder()
                .build();
        requestor.start();
    }

    private static void sendRequests() throws InterruptedException {
        // one OutboundMessageBuilder and one reply handler serve every request; see the bottom
        // of the file for the ReplyHandler class
        OutboundMessageBuilder messageBuilder = messagingService.messageBuilder();
        ReplyMessageHandler replyHandler = new ReplyHandler();
        Topic requestTopic = Topic.of(REQUEST_TOPIC);
        trace(String.format("%s sending %d direct requests to topic '%s' (timeout %d ms each).",
                APP_NAME, REQUEST_COUNT, REQUEST_TOPIC, REQUEST_TIMEOUT_MS));
        // send phase: publish() returns at once and the API delivers each result on its own
        // threads, so the requests are in flight together. A result can arrive before the
        // last request goes out.
        int requestsSent = 0;
        for (int count = 1; count <= REQUEST_COUNT && !isShutdown; count++) {  // a shutdown signal or a service interruption: send no further request
            // an application message id lets the replier echo it onto the reply for
            // traceability; the API does the request-reply correlation itself and does not
            // need it
            String msgId = UUID.randomUUID().toString();
            // a byte[] payload travels as the binary attachment; the replier reads it back
            // with getPayloadAsBytes()
            OutboundMessage request = messageBuilder.withApplicationMessageId(msgId)
                    .build(("Request " + count + " from " + APP_NAME).getBytes(StandardCharsets.UTF_8));
            try {
                // the message id goes in as the user context: the API hands it back to the
                // reply handler with the result of this request, so the handler can name the
                // request in a timeout or a failure, where there is no reply message. A
                // request that cannot go out while the requestor runs (for example during a
                // reconnect attempt) is not thrown here: the API reports it to the reply
                // handler. After a service interruption the requestor is terminated, and
                // publish() throws.
                requestor.publish(request, replyHandler, msgId, requestTopic, REQUEST_TIMEOUT_MS);
                requestsSent++;
            } catch (RuntimeException e) {  // threw from publish(): the requestor is terminated; a retry would not help
                logger.error("### Caught while trying to requestor.publish() for message id " + msgId + ", quitting", e);
                exitCode = 1;
                break;
            }
        }
        // a request that did not go out has no result to wait for
        for (int count = requestsSent; count < REQUEST_COUNT; count++) {
            requestResults.countDown();
        }

        // wait phase: each request gets its result within REQUEST_TIMEOUT_MS of its send. The
        // wait wakes every RESULT_WAIT_INTERVAL_MS to check the shutdown flag, so a shutdown
        // signal or a service interruption ends it early.
        while (!isShutdown && !requestResults.await(RESULT_WAIT_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
            // results still outstanding and no shutdown: wait again
        }

        int replies = replyCounter.get();
        trace(String.format("%s %s received %d of %d replies.", API, APP_NAME, replies, requestsSent));
        // a request without its reply is a failure: no silent retry. A shutdown signal that
        // stopped the send phase early is a clean stop, so only the requests sent count.
        if (replies < requestsSent) {
            exitCode = 1;
        }
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (all replies, a missing reply, SIGINT/SIGTERM, service
        // interruption, or an exception), so Solace teardown and application-side cleanup
        // are never skipped.
        isShutdown = true;
        if (requestor != null && requestor.isRunning()) {
            // terminate() gives requests still waiting on their replies up to the grace
            // period. Terminate the requestor before the disconnect: disconnect() stops it at
            // once.
            try {
                requestor.terminate(TERMINATE_GRACE_PERIOD_MS);
            } catch (PubSubPlusClientException.IncompleteMessageDeliveryException e) {
                logger.error("Requestor stopped with requests still outstanding after "
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

    ////////////////////////////////////////////////////////////////////////////

    /** Very simple static inner class, used for handling the result of each request: the
     *  correlated reply, the timeout, or the failure. Runs on API threads: the replies, the
     *  timeouts, and the failures each arrive on a different thread, so two calls can run at
     *  the same time. Keep the state it shares thread-safe, and return promptly, since the
     *  results behind it on the same thread wait while it runs. **/
    private static class ReplyHandler implements ReplyMessageHandler {

        @Override
        public void onMessage(InboundMessage message, Object userContext, PubSubPlusClientException exception) {
            try {
                if (exception == null) {  // the correlated reply
                    // the replier samples send a byte[] payload (the binary attachment), read
                    // back with getPayloadAsBytes()
                    byte[] payload = message.getPayloadAsBytes();
                    String text = payload != null ? new String(payload, StandardCharsets.UTF_8) : message.getPayloadAsString();
                    trace(API + " " + APP_NAME + " received a correlated reply (message id "
                            + message.getApplicationMessageId() + "): " + text);
                    // count the reply only after it is processed: the API catches an exception
                    // that escapes this handler, so a reply that fails in processing must not
                    // count, and the requestor then exits with code 1
                    replyCounter.incrementAndGet();
                } else if (exception instanceof PubSubPlusClientException.TimeoutException) {
                    // no reply within REQUEST_TIMEOUT_MS: no replier answered, or the request
                    // or the reply was lost (at-most-once). userContext is the message id.
                    logger.warn("Request " + userContext + " got no reply within " + REQUEST_TIMEOUT_MS + " ms.");
                } else {
                    // the request could not be sent (for example the service is down)
                    logger.error("Request " + userContext + " failed", exception);
                }
            } finally {
                requestResults.countDown();  // this request has its result
            }
        }
    }
}
