/*
 * Derived from features/RRGuaranteedReplier.java in
 * SolaceSamples/solace-samples-java-jcsmp (Apache License 2.0), elevated to the
 * practices in the JCSMP guides:
 *   Best Practices: https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *   Request-Reply:  https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Request-Reply-Messaging.md
 *
 * Reference sample: the replier side of a guaranteed (PERSISTENT) request-reply.
 * Connects with basic auth and reconnect-configured channel properties, idempotently
 * provisions a durable request queue and subscribes it to the request topic (the doc's
 * "queue assigned a topic subscription" option, not a durable topic endpoint), then
 * answers each request with a manual PERSISTENT producer.send(reply, request.getReplyTo()),
 * echoing the request's CorrelationID onto the reply. Handles session events and shuts
 * down gracefully on SIGINT. Structured as setupSolace(...), awaitRequests(), and
 * teardownSolace(), with teardown run from main's finally on every exit path.
 *
 * Only practices documented in canonical Solace sources are encoded here.
 */

package com.solace.samples.jcsmp;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.CapabilityType;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.Destination;
import com.solacesystems.jcsmp.EndpointProperties;
import com.solacesystems.jcsmp.FlowEventArgs;
import com.solacesystems.jcsmp.FlowEventHandler;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPErrorResponseSubcodeEx;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageListener;
import com.solacesystems.jcsmp.XMLMessageProducer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GuaranteedReplier {

    private static final String APP_NAME = GuaranteedReplier.class.getSimpleName();
    // durable request queue this replier provisions and binds; the requestor sends
    // PERSISTENT requests to the request topic, which is mapped onto this queue
    private static final String REQUEST_QUEUE_NAME = "q_jcsmp_guaranteed_request";
    // request topic the requestor publishes to; mapped onto the request queue below
    private static final String REQUEST_TOPIC = "solace/samples/jcsmp/guaranteed/request";
    private static final String API = "JCSMP";

    private static volatile int msgRecvCounter = 0;     // num requests received
    private static volatile boolean isShutdown = false; // are we done?
    private static FlowReceiver requestFlowReceiver;
    private static XMLMessageProducer producer;         // shared producer used to send replies
    private static JCSMPSession session;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2, but could also use SLF4J, JCL, etc.

    /** This is the main app. Use this type of app to answer Guaranteed (PERSISTENT) requests off a durable queue. */
    public static void main(String... args) throws JCSMPException, InterruptedException, IOException {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            if (!setupSolace(args)) {
                return;
            }
            // graceful shutdown: a SIGINT (Ctrl-C) signals the main loop to stop, then the
            // hook joins the main thread so the cleanup in teardownSolace() (stop the flow,
            // finish outstanding ACKs, close the session) runs to completion before the JVM
            // halts. The JVM exits as soon as all shutdown hooks return, so the hook must
            // wait, not just set a flag.
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                trace("Shutdown signal received, stopping replier...");
                isShutdown = true;
                try {
                    mainThread.join(5000);  // wait for the main thread's flow stop, ACK drain, and session close
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

    private static boolean setupSolace(String[] args) throws JCSMPException {
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
        // re-run idempotency: addSubscription() on the request queue below throws
        // "Subscription Already Exists" on every run after the first unless the session
        // ignores duplicate-subscription errors (this property defaults to false)
        properties.setProperty(JCSMPProperties.IGNORE_DUPLICATE_SUBSCRIPTION_ERROR, true);
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
                        logger.info("Session reconnected; the flow rebinds and replies resume");
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

        // acquire the producer used to send replies. Guaranteed (PERSISTENT) replies are
        // ACKed by the broker, so the streaming-publish event handler tracks each reply's
        // ACK/NACK by correlation key; the handler parameter is mandatory (a null handler
        // is rejected at runtime with "Blocking publishing mode is not supported").
        producer = session.getMessageProducer(new ReplyPublishEventHandler());

        // configure the durable request queue locally
        final Queue requestQueue = JCSMPFactory.onlyInstance().createQueue(REQUEST_QUEUE_NAME);

        // ELEVATION: provision the durable request queue in-process and map the request
        // topic onto it at startup, so a fresh broker works out of the box (the upstream
        // sample provisions with flag 0, which throws "already exists" on a re-run).
        // best practice: confirm the broker allows client-side endpoint management first
        if (!session.isCapable(CapabilityType.ENDPOINT_MANAGEMENT)) {
            System.err.println("This client/broker does not allow client-side endpoint management; "
                    + "provision the queue out-of-band or grant the capability. Exiting.");
            return false;  // teardownSolace() in main's finally closes the session
        }
        EndpointProperties endpointProps = new EndpointProperties();
        endpointProps.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);  // single-replier sample
        endpointProps.setPermission(EndpointProperties.PERMISSION_CONSUME);
        // provision the durable request queue IDEMPOTENTLY so a re-run is safe (NOT the
        // upstream flag 0, which throws on the second run)
        session.provision(requestQueue, endpointProps, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
        // map the request topic onto the queue with a subscription (PERSISTENT pub/sub onto
        // a queue); WAIT_FOR_CONFIRM blocks until the broker confirms the subscription
        Topic requestTopic = JCSMPFactory.onlyInstance().createTopic(REQUEST_TOPIC);
        session.addSubscription(requestQueue, requestTopic, JCSMPSession.WAIT_FOR_CONFIRM);

        // Create a Flow to bind to and consume requests from the durable request queue.
        final ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
        flowProps.setEndpoint(requestQueue);
        flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);  // best practice: ACK after replying

        trace(String.format("Attempting to bind to request queue '%s' on the broker.", REQUEST_QUEUE_NAME));
        // best practice: register a flow event handler at flow creation and handle each
        // event appropriately, rather than only logging it. See the RequestFlowListener
        // class at the bottom of the file for the request handler that builds each reply.
        requestFlowReceiver = session.createFlow(new RequestFlowListener(), flowProps, null, new FlowEventHandler() {
            @Override
            public void handleEvent(Object source, FlowEventArgs event) {
                logger.info("### Received a Flow event: " + event);
                switch (event.getEvent()) {
                    case FLOW_ACTIVE:        // bound and actively receiving requests from the queue
                        logger.info("Flow active: consuming requests from queue " + REQUEST_QUEUE_NAME);
                        break;
                    case FLOW_INACTIVE:      // flow is up but this replier is not the active one
                        logger.warn("Flow inactive: bound but not receiving (another replier holds the active flow on this exclusive queue)");
                        break;
                    case FLOW_RECONNECTING:  // flow unbound (e.g. queue disabled); API is rebinding
                        logger.warn("Flow reconnecting: request delivery paused while the API rebinds");
                        break;
                    case FLOW_RECONNECTED:   // flow rebind succeeded, request delivery resumes
                        logger.info("Flow reconnected: request delivery resumed");
                        break;
                    case FLOW_UP:            // the flow is established
                        logger.info("Flow up: bound to queue " + REQUEST_QUEUE_NAME);
                        break;
                    case FLOW_DOWN:          // the flow was established and then went down
                        logger.error("Flow down: delivery from queue " + REQUEST_QUEUE_NAME + " has stopped");
                        // Application cleanup signal: decide here whether to recreate the flow
                        // or shut down. This sample shuts down: isShutdown ends the main loop
                        // and teardownSolace() runs in main's finally.
                        isShutdown = true;
                        break;
                    default:
                        break;
                }
            }
        });
        return true;
    }

    private static void awaitRequests() throws JCSMPException, IOException, InterruptedException {
        // tell the broker to start sending requests on this queue receiver
        requestFlowReceiver.start();
        // async request receive working now, so time to wait until done...
        trace(String.format("%s subscribed and ready to reply. Press [ENTER] or Ctrl-C to quit.", APP_NAME));
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
        if (requestFlowReceiver != null) {
            // gracefully stop the flow and finish outstanding ACKs before exit
            requestFlowReceiver.stop();
            try {
                // A fixed sleep is a simple approximation. The ideal shutdown waits on an
                // explicit condition instead of a timer: tag each sent reply with its
                // correlation key, record each key the producer callback acknowledges
                // (responseReceivedEx), and proceed with the close once the last
                // acknowledged key matches the last sent key.
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();  // preserve interrupt; still close the session below
            }
        }
        if (session != null) {
            session.closeSession();  // will also close the flow object
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

    /** Static inner class, used for receiving Guaranteed requests off the queue flow and
     *  answering each with a MANUAL PERSISTENT reply to the request's reply-to destination. **/
    private static class RequestFlowListener implements XMLMessageListener {

        @Override
        public void onReceive(BytesXMLMessage requestMsg) {
            msgRecvCounter++;
            // guard on a non-null reply-to: a request without a reply-to destination cannot
            // be answered, so it is ignored. The guaranteed requestor sets reply-to to its
            // per-request temporary reply queue; a message arriving here without one is not
            // a request. The reply is correlated to the request by the CorrelationID echoed
            // below plus that dedicated per-request temporary reply queue.
            final Destination replyTo = requestMsg.getReplyTo();
            if (replyTo == null) {
                logger.warn("Received a message on the request queue with no reply-to; ignoring.");
                requestMsg.ackMessage();  // still ACK so the broker removes it from the queue
                return;
            }
            try {
                // Payload lives in the binary attachment: write with setData(), read with getData().
                // NOT getBytes(), which reads the separate (here empty) XML-content part.
                final String request = new String(((BytesMessage) requestMsg).getData(), StandardCharsets.UTF_8);
                // build the reply and answer with a MANUAL producer.send(reply, getReplyTo()):
                // guaranteed request-reply sets the reply PERSISTENT and sends it explicitly to
                // the requestor's temporary reply queue. This is the inverse of the direct
                // replier's reply convenience (Pitfall 2): the guaranteed path does NOT use that
                // direct-only convenience method, so the reply delivery mode and the reply-to
                // destination are controlled explicitly.
                // best practice: createMessage(...) makes a SESSION-INDEPENDENT message, the model
                // Solace recommends for new Java applications (session-dependent messages are legacy).
                BytesMessage replyMsg = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
                replyMsg.setData(("Reply from " + APP_NAME + " to: " + request).getBytes(StandardCharsets.UTF_8));
                replyMsg.setDeliveryMode(DeliveryMode.PERSISTENT);  // guaranteed reply
                // set the message's reply field to mark this message as a reply; the
                // requestor side can read it back with isReplyMessage() as one of the
                // agreed reply-acceptance conditions
                replyMsg.setAsReplyMessage(true);
                // echo the request's CorrelationID onto the reply so the requestor can match
                // the reply to its request (the doc-recommended request/reply mechanism). The
                // requestor sets this id; the replier just copies it back verbatim.
                replyMsg.setCorrelationId(requestMsg.getCorrelationId());
                // correlation key for the reply's PUBLISHER ACK/NACK (tracked in
                // ReplyPublishEventHandler), NOT request/reply matching: using the fresh
                // per-request reply message as its own key is safe because, unlike the
                // publisher's reused message, this object is not sent again.
                replyMsg.setCorrelationKey(replyMsg);
                producer.send(replyMsg, replyTo);
                // ACK the request only after the reply has been sent, so a crash before the
                // reply leaves the request on the queue for redelivery (Guaranteed semantics)
                requestMsg.ackMessage();
            } catch (JCSMPException e) {
                logger.warn("### Caught while trying to producer.send() the reply", e);
            }
        }

        @Override
        public void onException(JCSMPException e) {
            logger.warn("### Guaranteed replier flow handler received exception. Stopping!!", e);
            if (e instanceof JCSMPTransportException) {  // all reconnect attempts failed
                isShutdown = true;  // let's quit; or, could initiate a new connection attempt
            } else {
                if (e instanceof JCSMPErrorResponseException) {  // broker error response carries extra detail
                    JCSMPErrorResponseException ere = (JCSMPErrorResponseException) e;
                    logger.warn("Specifics: " + JCSMPErrorResponseSubcodeEx.getSubcodeAsString(ere.getSubcodeEx()) + ": " + ere.getResponsePhrase());
                }
                // Generally unrecoverable exception, probably need to recreate and restart the flow
                requestFlowReceiver.close();
                // add logic in the main thread to restart the FlowReceiver, or exit the program
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////

    /** Static inner class, used for handling reply publish ACKs/NACKs from the broker.
     *  Guaranteed (PERSISTENT) replies are broker-ACKed, so responseReceivedEx fires per
     *  reply; handleErrorEx fires on a NACK or other producer error. **/
    private static class ReplyPublishEventHandler implements JCSMPStreamingPublishCorrelatingEventHandler {

        @Override
        public void responseReceivedEx(Object key) {
            // ACK for a sent reply (correlated by the reply's correlation key); the broker
            // now has it. Nothing more to do for this sample.
            logger.debug(String.format("ACK for reply %s", key));
        }

        @Override
        public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
            if (key != null) {  // NACK: the broker rejected the PERSISTENT reply
                logger.warn(String.format("NACK for reply %s - %s", key, cause));
            } else {  // not a NACK, but some other producer error (ACL violation, connection loss, ...)
                logger.warn("### Guaranteed reply producer handleErrorEx() callback:", cause);
                if (cause instanceof JCSMPTransportException) {  // all reconnect attempts failed
                    isShutdown = true;  // let's quit; or, could initiate a new connection attempt
                } else if (cause instanceof JCSMPErrorResponseException) {  // broker error response carries extra detail
                    JCSMPErrorResponseException e = (JCSMPErrorResponseException) cause;
                    logger.warn("Specifics: " + JCSMPErrorResponseSubcodeEx.getSubcodeAsString(e.getSubcodeEx()) + ": " + e.getResponsePhrase());
                }
            }
        }
    }
}
