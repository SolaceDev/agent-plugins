/*
 * Derived from patterns/DirectRequestorBlocking.java
 *   SolaceSamples/solace-samples-java-jcsmp
 *   https://github.com/SolaceSamples/solace-samples-java-jcsmp
 *   Licensed under the Apache License, Version 2.0.
 *
 * Elevated to documented best practices per the JCSMP Best Practices guide:
 *   https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *
 * Reference sample. Wired for a basic direct request-reply journey on the
 * requestor side: basic-auth connect with reconnect-configured channel properties, a
 * started consumer (required before any request), a blocking session.createRequestor()
 * + requestor.request(requestMsg,
 * REQUEST_TIMEOUT_MS, topic) with a POSITIVE timeout, a JCSMPRequestTimeoutException
 * catch (with a single optional retry to absorb a cold-start race), and a self-exit
 * after the correlated reply. The blocking request IS the synchronization point;
 * there is no SIGINT hook (the requestor is a foreground process, waited on, never
 * SIGINTed). Direct request-reply is at-most-once on both legs. Structured as
 * setupSolace(...), sendRequest(), and teardownSolace(), with teardown run from
 * main's finally on every exit path.
 *
 * Only practices documented in canonical Solace sources are encoded here.
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
import com.solacesystems.jcsmp.JCSMPRequestTimeoutException;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.Requestor;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import com.solacesystems.jcsmp.XMLMessageProducer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DirectRequestor {

    private static final String APP_NAME = DirectRequestor.class.getSimpleName();
    // request topic the replier subscribes to; matches the direct replier sample
    private static final String REQUEST_TOPIC = "solace/samples/jcsmp/direct/request";
    private static final String API = "JCSMP";
    // POSITIVE blocking-request timeout in milliseconds: requestor.request(...) blocks up
    // to this long for the correlated reply before throwing JCSMPRequestTimeoutException.
    // A positive timeout is the direct reliability knob (NOT a burst); 3000 ms matches the
    // upstream DirectRequestorBlocking sample.
    private static final int REQUEST_TIMEOUT_MS = 3000;

    private static JCSMPSession session;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2, but could also use SLF4J, JCL, etc.

    /** Main. */
    public static void main(String... args) throws JCSMPException, IOException, InterruptedException {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            setupSolace(args);
            sendRequest();
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
        // best practice: register a session event handler at session creation and handle
        // each event appropriately, rather than only logging it
        session = JCSMPFactory.onlyInstance().createSession(properties, null, new SessionEventHandler() {
            @Override
            public void handleEvent(SessionEventArgs event) {
                logger.info("### Received a Session event: " + event);
                switch (event.getEvent()) {
                    case RECONNECTING:  // session went down, automatic reconnect attempt in progress
                        logger.warn("Session reconnecting; the request is paused until re-established");
                        break;
                    case RECONNECTED:   // automatic reconnect succeeded, session re-established
                        logger.info("Session reconnected; the request can proceed");
                        break;
                    case DOWN_ERROR:    // session was up and then went down; reconnects exhausted
                        // Application cleanup signal: the session will not recover; trigger
                        // application-side cleanup from here. One-shot requestor: the blocking
                        // request/receive fails or times out on its own, and teardownSolace()
                        // still runs in main's finally.
                        logger.error("Session is down and reconnect attempts are exhausted; the pending request will fail.");
                        break;
                    default:
                        break;
                }
            }
        });
        session.connect();

        // a producer is REQUIRED before issuing a blocking request: the Requestor sends the
        // request through the session's message producer, so it must be acquired first (the
        // API throws "No producer to perform operation" otherwise). Direct messaging is
        // at-most-once with no broker ACK, so the streaming-publish event handler is a no-op
        // (responseReceivedEx never fires for an ACK), but the handler parameter is mandatory
        // (a null handler is rejected at runtime with "Blocking publishing mode is not
        // supported"). The handler's error callback only logs.
        @SuppressWarnings("unused")
        XMLMessageProducer producer = session.getMessageProducer(new RequestPublishEventHandler());

        // a started consumer is also REQUIRED before issuing a blocking request: the Requestor
        // receives the correlated reply through the session's message consumer, so the
        // consumer must be running first. A null listener is fine because the blocking
        // requestor.request(...) returns the reply directly rather than via onReceive.
        final XMLMessageConsumer consumer = session.getMessageConsumer((XMLMessageListener) null);
        consumer.start();
    }

    private static void sendRequest() throws JCSMPException {
        // build the request and the request destination
        Topic requestTopic = JCSMPFactory.onlyInstance().createTopic(REQUEST_TOPIC);
        // best practice: createMessage(...) makes a SESSION-INDEPENDENT message, the model
        // Solace recommends for new Java applications (session-dependent messages are legacy).
        BytesMessage requestMsg = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        requestMsg.setData(("Request from " + APP_NAME).getBytes(StandardCharsets.UTF_8));
        // set an applicationMessageId so the reply can echo it back for traceability
        requestMsg.setApplicationMessageId(UUID.randomUUID().toString());
        trace(String.format("%s sending a direct request to topic '%s' (timeout %d ms).",
                APP_NAME, REQUEST_TOPIC, REQUEST_TIMEOUT_MS));

        // session.createRequestor() handles the reply-to + correlation automatically; the
        // blocking requestor.request(...) sends the request and waits up to REQUEST_TIMEOUT_MS
        // for the correlated reply, throwing JCSMPRequestTimeoutException on timeout. This
        // is the DIRECT request-reply convenience, the inverse of the guaranteed requestor's
        // temp-queue + blocking flow.receive(...).
        Requestor requestor = session.createRequestor();
        BytesXMLMessage replyMsg = null;
        // optionally retry once on a request timeout to absorb a cold-start race (the
        // direct reliability knob, D-06): the replier may not yet have propagated its
        // subscription on the very first attempt.
        for (int attempt = 1; attempt <= 2 && replyMsg == null; attempt++) {
            try {
                replyMsg = requestor.request(requestMsg, REQUEST_TIMEOUT_MS, requestTopic);
            } catch (JCSMPRequestTimeoutException e) {
                if (attempt < 2) {
                    logger.warn("Request timed out after " + REQUEST_TIMEOUT_MS + " ms; retrying once.");
                } else {
                    logger.warn("Request timed out after " + REQUEST_TIMEOUT_MS + " ms on the retry; giving up.", e);
                }
            }
        }

        if (replyMsg != null) {
            // Payload lives in the binary attachment: write with setData(), read with getData().
            // NOT getBytes(), which reads the separate (here empty) XML-content part.
            final String reply = new String(((BytesMessage) replyMsg).getData(), StandardCharsets.UTF_8);
            trace(API + " " + APP_NAME + " received a correlated reply: " + reply);
        } else {
            trace(API + " " + APP_NAME + " received no reply within the timeout.");
        }
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (normal quit, ENTER, SIGINT, DOWN_ERROR, or an exception),
        // so Solace teardown and application-side cleanup are never skipped.
        // self-exit: there is no ENTER/SIGINT loop and no shutdown hook (the requestor is
        // a foreground process, waited on, never SIGINTed); closing the session also
        // closes the consumer
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

    /** Minimal mandatory producer event handler. The blocking Requestor sends the request
     *  through this producer; direct messaging is at-most-once with no broker ACK, so
     *  responseReceivedEx is not expected to fire for an ACK. The handler exists only because
     *  the producer requires one. handleErrorEx logs the error and renders the broker subcode
     *  when the cause carries one. **/
    private static class RequestPublishEventHandler implements JCSMPStreamingPublishCorrelatingEventHandler {

        @Override
        public void responseReceivedEx(Object key) {
            // no broker ACK in direct messaging; nothing to do
        }

        // can be called for ACL violations and connection loss; direct has no
        // Persistent NACKs (no correlation key), so this never fires for a NACK
        @Override
        public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
            logger.warn("### Direct requestor producer handleErrorEx() callback:", cause);
            if (cause instanceof JCSMPErrorResponseException) {  // might have some extra info
                JCSMPErrorResponseException ere = (JCSMPErrorResponseException) cause;
                logger.warn("Specifics: " + JCSMPErrorResponseSubcodeEx.getSubcodeAsString(ere.getSubcodeEx())
                        + ": " + ere.getResponsePhrase());
            }
        }
    }
}
