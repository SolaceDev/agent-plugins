/*
 * Derived from patterns/GuaranteedPublisher.java
 *   SolaceSamples/solace-samples-java-jcsmp
 *   https://github.com/SolaceSamples/solace-samples-java-jcsmp
 *   Licensed under the Apache License, Version 2.0.
 *
 * Elevated to documented best practices per the JCSMP Best Practices guide:
 *   https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *
 * Reference sample. Wired for a basic publish-subscribe journey: basic-auth
 * connect, PERSISTENT binary publish to a topic, publish ACK/NACK handling,
 * session-event and reconnect-event handling that pauses publishing while the
 * transport is down, and a graceful SIGINT shutdown that finishes outstanding
 * ACKs before closing. Structured as setupSolace(...), runPublishLoop(), and
 * teardownSolace(), with teardown run from main's finally on every exit path.
 *
 * Only practices documented in canonical Solace sources are encoded here.
 */

package com.solace.samples.jcsmp;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPErrorResponseSubcodeEx;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProducerEventHandler;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPReconnectEventHandler;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.ProducerEventArgs;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageProducer;

public class GuaranteedPublisher {

    private static final String APP_NAME = GuaranteedPublisher.class.getSimpleName();
    static final String TOPIC_PREFIX = "solace/samples/";  // used as the topic "root"
    private static final String API = "JCSMP";
    private static final int PUBLISH_WINDOW_SIZE = 255;  // max guaranteed-publish window (range 1-255)
    private static final int APPROX_MSG_RATE_PER_SEC = 100;
    private static final int PAYLOAD_SIZE = 512;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2, but could also use SLF4J, JCL, etc.

    private static volatile int msgSentCounter = 0;                   // num messages sent
    private static volatile boolean isShutdown = false;
    private static volatile boolean isConnected = true;               // tracks transport state via reconnect events
    private static JCSMPSession session;
    private static XMLMessageProducer producer;
    private static ScheduledExecutorService statsPrintingThread;

    /** Main. */
    public static void main(String... args) throws JCSMPException, IOException, InterruptedException {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            setupSolace(args);
            // graceful shutdown: a SIGINT (Ctrl-C) signals the main loop to stop, then the
            // hook joins the main thread so the cleanup in teardownSolace() (finish
            // outstanding ACKs, close the session) runs to completion before the JVM halts.
            // The JVM exits as soon as all shutdown hooks return, so the hook must wait,
            // not just set a flag.
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                trace("Shutdown signal received, stopping publisher...");
                isShutdown = true;
                try {
                    mainThread.join(5000);  // wait for the main thread's ACK drain and session close
                } catch (InterruptedException e) {
                    // nothing more we can do; the JVM is halting
                }
            }));
            runPublishLoop();
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
        // PUB_ACK_WINDOW_SIZE: max unACKed Guaranteed messages in flight, range 1-255
        // (JCSMP default 1). 255 favors throughput, at the cost of more memory and a
        // chance of out-of-order redelivery on NACKs; lower it for stricter
        // ordering / less memory.
        properties.setProperty(JCSMPProperties.PUB_ACK_WINDOW_SIZE, PUBLISH_WINDOW_SIZE);
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
                        isConnected = false;
                        break;
                    case RECONNECTED:   // automatic reconnect succeeded, session re-established
                        isConnected = true;
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

        producer = session.getMessageProducer(new PublishCallbackHandler(), new JCSMPProducerEventHandler() {
            @Override
            public void handleEvent(ProducerEventArgs event) {
                // as of JCSMP v10.10, this event only occurs when republishing unACKed messages on an unknown flow
                logger.info("*** Received a producer event: " + event);
            }
        });

        // best practice for publish-only applications: transport reconnect events are only
        // exposed through a consumer, so acquire an empty one (never started) and register a
        // reconnect event handler to pause publishing while the connection is re-established
        session.getMessageConsumer(new JCSMPReconnectEventHandler() {
            @Override
            public boolean preReconnect() throws JCSMPException {
                logger.info("### preReconnect(): transport down, pausing publishing");
                isConnected = false;
                return true;  // true means let the API proceed with its reconnect attempts
            }

            @Override
            public void postReconnect() throws JCSMPException {
                logger.info("### postReconnect(): transport restored, resuming publishing");
                isConnected = true;
            }
        }, null);  // no message listener: this consumer exists only to surface transport events
    }

    private static void runPublishLoop() throws IOException, InterruptedException {
        statsPrintingThread = Executors.newSingleThreadScheduledExecutor();
        statsPrintingThread.scheduleAtFixedRate(() -> {
            trace(String.format("%s %s Published msgs/s: %,d",API,APP_NAME,msgSentCounter));  // simple way of calculating message rates
            msgSentCounter = 0;
        }, 1, 1, TimeUnit.SECONDS);

        trace(API + " " + APP_NAME + " connected, and running. Press [ENTER] or Ctrl-C to quit.");
        byte[] payload = new byte[PAYLOAD_SIZE];  // preallocate
        // BytesMessage carries a binary payload; "XML" in XMLMessageProducer/BytesXMLMessage
        // is legacy JCSMP API naming, NOT an XML payload format
        // best practice: createMessage(...) makes a SESSION-INDEPENDENT message, the model
        // Solace recommends for new Java applications (session-dependent messages are legacy).
        BytesMessage message = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);  // preallocate
        trace("Publishing to topic '"+ TOPIC_PREFIX + API.toLowerCase() +
                "/pers/pub/...', please ensure queue has matching subscription.");
        while (System.in.available() == 0 && !isShutdown) {  // loop until ENTER pressed, or shutdown flag
            if (!isConnected) {  // transport is down: wait for the API's automatic reconnect
                Thread.sleep(100);
                continue;
            }
            message.reset();  // ready for reuse
            // each loop, change the payload as an example
            char chosenCharacter = (char)(Math.round(msgSentCounter % 26) + 65);  // choose a "random" letter [A-Z]
            Arrays.fill(payload,(byte)chosenCharacter);  // fill the payload completely with that char
            // setData() writes the binary attachment; consumers read it back with getData(),
            // NOT getBytes() (which reads the separate, empty XML-content part).
            message.setData(payload);
            message.setDeliveryMode(DeliveryMode.PERSISTENT);  // required for Guaranteed
            String msgId = UUID.randomUUID().toString();
            message.setApplicationMessageId(msgId);  // as an example
            // correlation key for local ACK/NACK correlation: use an immutable per-send
            // identifier, NOT the reused message object, which this loop keeps mutating
            // while up to PUBLISH_WINDOW_SIZE sends are still awaiting their ACK
            message.setCorrelationKey(msgId);
            String topicString = new StringBuilder(TOPIC_PREFIX).append(API.toLowerCase())
            		.append("/pers/pub/").append(chosenCharacter).toString();
            // NOTE: publishing to topic, so make sure the consumer's queue is subscribed to the same topic,
            //       or enable "Reject Message to Sender on No Subscription Match" the client-profile
            Topic topic = JCSMPFactory.onlyInstance().createTopic(topicString);
            try {
                producer.send(message, topic);
                msgSentCounter++;
            } catch (JCSMPException e) {  // threw from send(), only thing that is throwing here, but keep trying (unless shutdown?)
                logger.warn("### Caught while trying to producer.send()",e);
                if (e instanceof JCSMPTransportException) {  // all reconnect attempts failed
                    isShutdown = true;  // let's quit; or, could initiate a new connection attempt
                }
            } finally {  // add a delay between messages
                try {
                    Thread.sleep(1000 / APPROX_MSG_RATE_PER_SEC);  // do Thread.sleep(0) for max speed
                    // Note: STANDARD Edition Solace PubSub+ broker is limited to 10k msg/s max ingress
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();  // restore the interrupt status, do not swallow it
                    isShutdown = true;
                }
            }
        }
        isShutdown = true;
    }

    private static void teardownSolace() {
        // Application cleanup belongs here: teardownSolace() runs in main's finally on
        // EVERY exit path (normal quit, ENTER, SIGINT, DOWN_ERROR, or an exception),
        // so Solace teardown and application-side cleanup are never skipped.
        isShutdown = true;
        if (statsPrintingThread != null) {
            statsPrintingThread.shutdown();  // stop printing stats
        }
        if (session != null) {
            // gracefully finish outstanding ACKs before exit: give the broker time to
            // confirm in-flight PERSISTENT messages before the session is closed. Tolerate an
            // interrupt here (the loop above may have restored the interrupt status) so the
            // session is still closed rather than skipped.
            try {
                // A fixed sleep is a simple approximation. The ideal shutdown waits on
                // an explicit condition instead of a timer: each send already sets a
                // correlation key, and responseReceivedEx reports each key the broker
                // acknowledges, so record the last sent key and the last acknowledged
                // key and proceed with the close once the two match.
                Thread.sleep(1500);  // give time for the ACKs to arrive from the broker
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();  // preserve interrupt; still close the session below
            }
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

    /** Very simple static inner class, used for handling publish ACKs/NACKs from broker. **/
    private static class PublishCallbackHandler implements JCSMPStreamingPublishCorrelatingEventHandler {

        @Override
        public void responseReceivedEx(Object key) {
            assert key != null;  // this shouldn't happen, this should only get called for an ACK
            logger.debug(String.format("ACK for Message ID %s", key));  // good enough, the broker has it now
        }

        @Override
        public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
            if (key != null) {  // NACK
                logger.warn(String.format("NACK for Message ID %s - %s", key, cause));
                // probably want to do something here.  some error handling possibilities:
                //  - look the message up by this ID in your application's outbound store and send it again
                //  - send it somewhere else (error handling queue?)
                //  - log and continue
                //  - pause and retry (backoff) - maybe set a flag to slow down the publisher
            } else {  // not a NACK, but some other error (ACL violation, connection loss, message too big, ...)
                logger.warn("### Producer handleErrorEx() callback:", cause);
                if (cause instanceof JCSMPTransportException) {  // all reconnect attempts failed
                    isShutdown = true;  // let's quit; or, could initiate a new connection attempt
                } else if (cause instanceof JCSMPErrorResponseException) {  // might have some extra info
                    JCSMPErrorResponseException e = (JCSMPErrorResponseException)cause;
                    logger.warn("Specifics: " + JCSMPErrorResponseSubcodeEx.getSubcodeAsString(e.getSubcodeEx()) + ": " + e.getResponsePhrase());
                }
            }
        }
    }
}
