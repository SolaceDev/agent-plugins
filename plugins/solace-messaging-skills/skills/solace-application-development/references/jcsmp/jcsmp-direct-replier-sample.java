/*
 * Derived from patterns/DirectReplier.java
 *   SolaceSamples/solace-samples-java-jcsmp
 *   https://github.com/SolaceSamples/solace-samples-java-jcsmp
 *   Licensed under the Apache License, Version 2.0.
 *
 * Elevated to documented best practices per the JCSMP Best Practices guide:
 *   https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *
 * Reference sample. Wired for a basic direct request-reply journey on the
 * replier side: basic-auth connect with reconnect-configured channel properties, a
 * plain topic subscription on the request topic (no queue, no provisioning), an
 * async consumer whose onReceive guards on
 * a non-null reply-to and answers each request with the DIRECT reply convenience
 * producer.sendReply(requestMsg, replyMsg) (which auto-routes to the reply-to and
 * copies the correlation), session-event handling, and a graceful SIGINT shutdown.
 * Direct request-reply is at-most-once on both legs; the convenience reply is the
 * inverse of the guaranteed replier's manual producer.send(reply, getReplyTo()).
 * Structured as setupSolace(...), awaitRequests(), and teardownSolace(), with
 * teardown run from main's finally on every exit path.
 *
 * Only practices documented in canonical Solace sources are encoded here.
 *
 * Any generated adaptation of this sample MUST begin with the exact line:
 *   AI-assisted code. Review before production use.
 * (This reference sample itself carries no such header by design.)
 */

package com.solace.samples.jcsmp;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPErrorResponseSubcodeEx;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import com.solacesystems.jcsmp.XMLMessageProducer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DirectReplier {

    private static final String APP_NAME = DirectReplier.class.getSimpleName();
    // request topic to subscribe to directly; the requestor sends to this topic
    private static final String REQUEST_TOPIC = "solace/samples/jcsmp/direct/request";
    private static final String API = "JCSMP";

    private static volatile int msgRecvCounter = 0;     // num requests received
    private static volatile boolean isShutdown = false; // are we done?
    private static JCSMPSession session;
    private static XMLMessageProducer producer;         // shared producer used to send replies

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2 by default; any backend swap follows the logging rule in implement-mode.md Step 3

    /** This is the main app. Use this type of app to answer Direct (at-most-once) requests on a topic. */
    public static void main(String... args) throws JCSMPException, InterruptedException, IOException {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            setupSolace(args);
            // graceful shutdown: a SIGINT (Ctrl-C) signals the main loop to stop, then the
            // hook joins the main thread so the cleanup in teardownSolace() (stop the
            // consumer, close the session) runs to completion before the JVM halts. The
            // JVM exits as soon as all shutdown hooks return, so the hook must wait, not
            // just set a flag.
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                trace("Shutdown signal received, stopping replier...");
                isShutdown = true;
                try {
                    mainThread.join(5000);  // wait for the main thread's session close
                } catch (InterruptedException e) {
                    // nothing more we can do; the JVM is halting
                }
            }));
            awaitRequests();
        } finally {
            teardownSolace();
            trace("Main thread quitting.");
        }
    }

    private static void setupSolace(String[] args) throws JCSMPException {
        // basic username/password connection details, built by the shared SolaceConnectionConfig
        // helper: read from a config.json in the working directory if present, else from the
        // command line (<host:port> <message-vpn> <client-username> [password])
        final JCSMPProperties properties = SolaceConnectionConfig.load(args, APP_NAME).toSessionProperties();
        JCSMPChannelProperties channelProps = new JCSMPChannelProperties();
        // reconnect budget: a few minutes of reconnect attempts, a reasonable default
        // when the design says nothing about high availability
        channelProps.setReconnectRetries(20);
        channelProps.setConnectRetriesPerHost(3);
        properties.setProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES, channelProps);
        // For HA failover resilience, use the JCSMP Best Practices values
        // (https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md):
        //   HA failover: setConnectRetries(1), setReconnectRetries(20),
        //     setReconnectRetryWaitInMillis(3000), setConnectRetriesPerHost(5)
        // REAPPLY_SUBSCRIPTIONS re-adds the Direct request-topic subscription automatically
        // after an automatic reconnect (JCSMP default is false). A Direct subscription is a
        // client-side session subscription, not held on a broker queue, so without this it
        // would be lost on reconnect and the replier would silently stop answering requests.
        properties.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);
        // best practice: register a session event handler at session creation and handle
        // each event appropriately, rather than only logging it
        session = JCSMPFactory.onlyInstance().createSession(properties, null, new SessionEventHandler() {
            @Override
            public void handleEvent(SessionEventArgs event) {
                logger.info("### Received a Session event: " + event);
                switch (event.getEvent()) {
                    case RECONNECTING:  // session went down, automatic reconnect attempt in progress
                        logger.warn("Session reconnecting; reply delivery is paused until re-established");
                        break;
                    case RECONNECTED:   // automatic reconnect succeeded, session re-established
                        logger.info("Session reconnected; the subscription is restored and replies resume");
                        break;
                    case DOWN_ERROR:    // session was up and then went down; reconnects exhausted
                        logger.error("Session is down and reconnect attempts are exhausted, quitting.");
                        // Application cleanup signal: the session will not recover. Trigger
                        // application-side cleanup from here. This sample sets isShutdown, so
                        // the main loop exits and teardownSolace() runs in main's finally.
                        isShutdown = true;
                        break;
                    default:
                        break;
                }
            }
        });
        session.connect();

        // acquire the producer used to send replies; direct messaging is at-most-once so
        // the streaming-publish event handler is effectively a no-op (no broker ACK), but
        // the handler parameter is mandatory (a null handler is rejected at runtime with
        // "Blocking publishing mode is not supported"). The error callback only logs.
        producer = session.getMessageProducer(new ReplyPublishEventHandler());

        // DIRECT replier: acquire the async consumer, add a plain topic subscription on
        // the request topic (no queue, no provisioning), then start the consumer. Direct
        // messaging is at-most-once: requests flow straight to this replier with no broker
        // ACK and no redelivery.
        final XMLMessageConsumer consumer = session.getMessageConsumer(new RequestMessageListener());
        Topic requestTopic = JCSMPFactory.onlyInstance().createTopic(REQUEST_TOPIC);
        trace(String.format("Adding direct request-topic subscription '%s'.", REQUEST_TOPIC));
        session.addSubscription(requestTopic);
        consumer.start();
        trace(String.format("%s subscribed and ready to reply. Press [ENTER] or Ctrl-C to quit.", APP_NAME));
    }

    private static void awaitRequests() throws IOException, InterruptedException {
        // async direct receive working now, so time to wait until done...
        while (System.in.available() == 0 && !isShutdown) {
            Thread.sleep(1000);  // wait 1 second
            trace(String.format("%s %s Requests/s: %,d",API,APP_NAME,msgRecvCounter));  // simple way of calculating request rates
            msgRecvCounter = 0;
        }
        isShutdown = true;
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (normal quit, ENTER, SIGINT, DOWN_ERROR, or an exception),
        // so Solace teardown and application-side cleanup are never skipped.
        isShutdown = true;
        // direct is at-most-once: there are no acknowledgements to drain before exit, so
        // close the session directly (closing the session also closes the consumer)
        if (session != null) {
            session.closeSession();
        }
    }

    /** Demo narration sink: every status line in this sample funnels through this one
     *  method. An application replaces this single body to route narration to its
     *  logger or reporting system. The log4j2 logger calls for API events (session,
     *  flow, producer) are a separate channel and stay as they are. */
    private static void trace(String message) {
        System.out.println(message);
    }

    ////////////////////////////////////////////////////////////////////////////

    /** Static inner class, used for receiving direct requests and replying to each. **/
    private static class RequestMessageListener implements XMLMessageListener {

        @Override
        public void onReceive(BytesXMLMessage requestMsg) {
            msgRecvCounter++;
            // guard on a non-null reply-to: a request without a reply-to destination cannot
            // be answered, so it is ignored. The requestor sets reply-to automatically via
            // session.createRequestor(); a message arriving here without one is not a request.
            if (requestMsg.getReplyTo() == null) {
                logger.warn("Received a message on the request topic with no reply-to; ignoring.");
                return;
            }
            try {
                // Payload lives in the binary attachment: write with setData(), read with getData().
                // NOT getBytes(), which reads the separate (here empty) XML-content part.
                final String request = new String(((BytesMessage) requestMsg).getData(), StandardCharsets.UTF_8);
                // build the reply and answer with the DIRECT reply convenience: sendReply
                // auto-routes to requestMsg.getReplyTo() and copies the correlation id, so
                // the requestor's blocking requestor.request(...) matches it. This is the
                // inverse of the guaranteed replier, which uses manual producer.send(reply,
                // request.getReplyTo()) with a PERSISTENT reply.
                // best practice: createMessage(...) makes a SESSION-INDEPENDENT message, the model
                // Solace recommends for new Java applications (session-dependent messages are legacy).
                BytesMessage replyMsg = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
                replyMsg.setData(("Reply from " + APP_NAME + " to: " + request).getBytes(StandardCharsets.UTF_8));
                // echo the request's applicationMessageId onto the reply for traceability
                if (requestMsg.getApplicationMessageId() != null) {
                    replyMsg.setApplicationMessageId(requestMsg.getApplicationMessageId());
                }
                producer.sendReply(requestMsg, replyMsg);
            } catch (JCSMPException e) {
                logger.warn("### Caught while trying to producer.sendReply()", e);
            }
        }

        @Override
        public void onException(JCSMPException e) {
            logger.warn("### Direct replier consumer handler received exception. Stopping!!", e);
            if (e instanceof JCSMPTransportException) {  // all reconnect attempts failed
                isShutdown = true;  // let's quit; or, could initiate a new connection attempt
            } else if (e instanceof JCSMPErrorResponseException) {  // broker error response carries extra detail
                JCSMPErrorResponseException ere = (JCSMPErrorResponseException) e;
                logger.warn("Specifics: " + JCSMPErrorResponseSubcodeEx.getSubcodeAsString(ere.getSubcodeEx())
                        + ": " + ere.getResponsePhrase());
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////

    /** Minimal mandatory producer event handler. Direct messaging is at-most-once with
     *  no broker ACK, so responseReceivedEx is not expected to fire for an ACK; the
     *  handler exists only because the producer requires one. handleErrorEx logs the
     *  error, quits on an exhausted-reconnect transport failure, and renders the broker
     *  subcode when the cause carries one. **/
    private static class ReplyPublishEventHandler implements JCSMPStreamingPublishCorrelatingEventHandler {

        @Override
        public void responseReceivedEx(Object key) {
            // no broker ACK in direct messaging; nothing to do
        }

        // can be called for ACL violations and connection loss; direct has no
        // Persistent NACKs (no correlation key), so this never fires for a NACK
        @Override
        public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
            logger.warn("### Direct reply producer handleErrorEx() callback:", cause);
            if (cause instanceof JCSMPTransportException) {  // all reconnect attempts failed
                isShutdown = true;  // let's quit; or, could initiate a new connection attempt
            } else if (cause instanceof JCSMPErrorResponseException) {  // might have some extra info
                JCSMPErrorResponseException ere = (JCSMPErrorResponseException) cause;
                logger.warn("Specifics: " + JCSMPErrorResponseSubcodeEx.getSubcodeAsString(ere.getSubcodeEx())
                        + ": " + ere.getResponsePhrase());
            }
        }
    }
}
