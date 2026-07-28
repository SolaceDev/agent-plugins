/*
 * Derived from features/RRGuaranteedRequester.java in
 * SolaceSamples/solace-samples-java-jcsmp (Apache License 2.0), elevated to the
 * practices in the JCSMP guides:
 *   Best Practices: https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *   Request-Reply:  https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Request-Reply-Messaging.md
 *
 * Reference sample: the requestor side of a guaranteed (PERSISTENT) request-reply.
 * Connects with basic auth and reconnect-configured channel properties, sends a
 * PERSISTENT request to the request topic with reply-to set to a per-request temporary
 * queue, then blocks on flow.receive(timeout) for the reply and self-exits. The reply is
 * correlated by a unique CorrelationID (set here, echoed by the replier, matched on
 * receipt) plus that dedicated temporary reply queue. Structured as setupSolace(...),
 * sendRequest(), and teardownSolace(), with teardown run from main's finally on every
 * exit path.
 *
 * Only practices documented in canonical Solace sources are encoded here.
 */

package com.solace.samples.jcsmp;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPErrorResponseSubcodeEx;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageProducer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GuaranteedRequestor {

    private static final String APP_NAME = GuaranteedRequestor.class.getSimpleName();
    // request topic the replier subscribes to (via its durable request queue); matches
    // the guaranteed replier sample
    private static final String REQUEST_TOPIC = "solace/samples/jcsmp/guaranteed/request";
    private static final String API = "JCSMP";
    // POSITIVE blocking-receive timeout in milliseconds: flow.receive(...) blocks up to
    // this long for the correlated reply on the temporary reply queue before returning
    // null. A single PERSISTENT request plus a temp-queue reply is reliable, so there is
    // no burst and no retry (the guaranteed reliability knob is PERSISTENT delivery).
    private static final int REPLY_TIMEOUT_MS = 5000;
    // notable CorrelationID prefix marking requests from THIS application: one of the
    // agreed reply-acceptance conditions (see isExpectedReply below). The prefix value is
    // an application-chosen convention the requestor and replier agree on out of band.
    private static final String CORRELATION_ID_PREFIX = "REQ-";

    private static JCSMPSession session;
    private static XMLMessageProducer producer;
    private static Queue replyQueue;
    private static FlowReceiver replyFlow;

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

        // acquire the producer used to send the PERSISTENT request. Guaranteed messages are
        // broker-ACKed, so the streaming-publish event handler tracks the request's ACK/NACK
        // by correlation key; the handler parameter is mandatory (a null handler is rejected
        // at runtime with "Blocking publishing mode is not supported").
        producer = session.getMessageProducer(new RequestPublishEventHandler());

        // create a per-request TEMPORARY reply queue: a non-durable queue the broker
        // auto-creates for this client and auto-deletes when the flow/session closes, so the
        // reply has nowhere stale to land. This dedicated queue is one correlation mechanism;
        // the CorrelationID set on the request below is the other (both are used here).
        replyQueue = session.createTemporaryQueue();

        // bind a FlowReceiver to the temporary reply queue with a NULL listener: the blocking
        // model returns the reply directly from flow.receive(...) rather than via an async
        // onReceive. This is the inverse of the direct requestor's blocking-Requestor
        // convenience: the guaranteed path does NOT use that direct-only convenience API.
        final ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
        flowProps.setEndpoint(replyQueue);
        replyFlow = session.createFlow(null, flowProps);
        replyFlow.start();
    }

    private static void sendRequest() throws JCSMPException {
        // build the PERSISTENT request, set its reply-to to the temporary reply queue, and
        // send it to the request topic
        Topic requestTopic = JCSMPFactory.onlyInstance().createTopic(REQUEST_TOPIC);
        // best practice: createMessage(...) makes a SESSION-INDEPENDENT message, the model
        // Solace recommends for new Java applications (session-dependent messages are legacy).
        BytesMessage requestMsg = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        requestMsg.setData(("Request from " + APP_NAME).getBytes(StandardCharsets.UTF_8));
        requestMsg.setDeliveryMode(DeliveryMode.PERSISTENT);  // required for Guaranteed
        requestMsg.setReplyTo(replyQueue);                    // reply-to = the temporary reply queue
        // set a unique CorrelationID on the request (the doc-recommended request/reply
        // matching mechanism). The replier echoes it onto the reply; the requestor is
        // responsible for matching it on receipt (see the flow.receive check below). This
        // is the on-the-wire correlation id, distinct from setCorrelationKey. The notable
        // CORRELATION_ID_PREFIX marks the id as one of this application's requests.
        final String correlationId = CORRELATION_ID_PREFIX + UUID.randomUUID();
        requestMsg.setCorrelationId(correlationId);
        // setCorrelationKey is for PUBLISHER ACK correlation ONLY (it identifies the request
        // in this producer's ACK callback); it is a local object, never sent on the wire, and
        // is NOT the request/reply matching id set above.
        requestMsg.setCorrelationKey(requestMsg);
        trace(String.format("%s sending a guaranteed request to topic '%s' (reply timeout %d ms).",
                APP_NAME, REQUEST_TOPIC, REPLY_TIMEOUT_MS));
        producer.send(requestMsg, requestTopic);

        // BLOCKING receive of the reply off the temporary reply queue: it blocks up to
        // REPLY_TIMEOUT_MS and returns null on timeout. A single PERSISTENT request plus
        // a temp-queue reply is reliable, so there is no burst and no retry (D-06).
        BytesXMLMessage replyMsg = replyFlow.receive(REPLY_TIMEOUT_MS);

        if (isExpectedReply(replyMsg, correlationId)) {
            // Payload lives in the binary attachment: write with setData(), read with getData().
            // NOT getBytes(), which reads the separate (here empty) XML-content part.
            final String reply = new String(((BytesMessage) replyMsg).getData(), StandardCharsets.UTF_8);
            trace(API + " " + APP_NAME + " received a correlated reply: " + reply);
        } else if (replyMsg != null) {
            trace(API + " " + APP_NAME + " received a reply that did not meet the reply-acceptance contract.");
        } else {
            trace(API + " " + APP_NAME + " received no reply within the timeout.");
        }
    }

    /** Reply-acceptance contract: the requestor and the replier must AGREE on what marks
     *  a message as the reply to a given request, and that agreed contract lives in this
     *  one method. For THIS requestor a message is the reply ONLY when ALL of these hold:
     *    1. the message's reply field is set (read with isReplyMessage(); the replier
     *       marks every reply with setAsReplyMessage(true)),
     *    2. its CorrelationID carries this application's notable CORRELATION_ID_PREFIX,
     *       marking it as a reply to one of THIS application's requests, and
     *    3. its CorrelationID equals the id set on THIS request (the doc-recommended
     *       CorrelationID echo; the replier copies it back verbatim).
     *  The exact-match condition subsumes the prefix condition in this one-request
     *  sample; both are checked so every condition of the contract stays visible, and
     *  the prefix earns its keep when many in-flight requests share a reply queue.
     *  Whatever conditions an application picks, encode them here and mirror the same
     *  contract on the replier side. */
    private static boolean isExpectedReply(BytesXMLMessage replyMsg, String correlationId) {
        if (replyMsg == null) {
            return false;  // timeout: the blocking receive returned no message at all
        }
        if (!replyMsg.isReplyMessage()) {
            return false;  // condition 1 failed: the message's reply field is not set
        }
        final String replyCorrelationId = replyMsg.getCorrelationId();
        if (replyCorrelationId == null || !replyCorrelationId.startsWith(CORRELATION_ID_PREFIX)) {
            return false;  // condition 2 failed: not a reply to one of this app's requests
        }
        return correlationId.equals(replyCorrelationId);  // condition 3: the reply to THIS request
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (normal quit, ENTER, SIGINT, DOWN_ERROR, or an exception),
        // so Solace teardown and application-side cleanup are never skipped.
        // self-exit: closing the session auto-deletes the temporary reply queue. There is
        // no ENTER/SIGINT loop and no shutdown hook (the requestor is a foreground
        // process, waited on by verify.sh, never SIGINTed).
        if (replyFlow != null) {
            replyFlow.stop();
        }
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

    /** Static inner class, used for handling the request publish ACK/NACK from the broker.
     *  Guaranteed (PERSISTENT) requests are broker-ACKed, so responseReceivedEx fires for the
     *  request's ACK (correlated by the correlation key set above); handleErrorEx fires on a
     *  NACK or other producer error. **/
    private static class RequestPublishEventHandler implements JCSMPStreamingPublishCorrelatingEventHandler {

        @Override
        public void responseReceivedEx(Object key) {
            // ACK for the sent request (correlated by the correlation key set above); the
            // broker now has it. Nothing more to do for this one-shot sample.
            logger.debug(String.format("ACK for request %s", key));
        }

        @Override
        public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
            if (key != null) {  // NACK: the broker rejected the PERSISTENT request
                logger.warn(String.format("NACK for request %s - %s", key, cause));
            } else {  // not a NACK, but some other producer error (ACL violation, connection loss, ...)
                logger.warn("### Guaranteed request producer handleErrorEx() callback:", cause);
                if (cause instanceof JCSMPErrorResponseException) {  // broker error response carries extra detail
                    JCSMPErrorResponseException e = (JCSMPErrorResponseException) cause;
                    logger.warn("Specifics: " + JCSMPErrorResponseSubcodeEx.getSubcodeAsString(e.getSubcodeEx()) + ": " + e.getResponsePhrase());
                }
            }
        }
    }
}
