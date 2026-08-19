/*
 * Derived from patterns/DirectPublisher.java
 *   SolaceSamples/solace-samples-java-jcsmp
 *   https://github.com/SolaceSamples/solace-samples-java-jcsmp
 *   Licensed under the Apache License, Version 2.0.
 *
 * Elevated to documented best practices per the JCSMP Best Practices guide:
 *   https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *
 * Reference sample. Wired for a basic direct (at-most-once) pub/sub journey:
 * basic-auth connect with reconnect-configured channel properties, a continuous
 * DIRECT binary publish loop with a rotating payload to a topic, session-event
 * handling, and a graceful SIGINT shutdown. Direct messaging is at-most-once: there
 * is no broker ACK, so the producer's mandatory event handler is effectively a no-op
 * and there is NO correlation key here (the inverse of the guaranteed publisher,
 * which acts on each ACK/NACK). There are no outstanding ACKs to drain at shutdown.
 * Structured as setupSolace(...), runPublishLoop(), and teardownSolace(), with
 * teardown run from main's finally on every exit path.
 *
 * Only practices documented in canonical Solace sources are encoded here.
 *
 * Any generated adaptation of this sample MUST begin with the exact line:
 *   AI-assisted code. Review before production use.
 * (This reference sample itself carries no such header by design.)
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
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageProducer;

public class DirectPublisher {

    private static final String APP_NAME = DirectPublisher.class.getSimpleName();
    static final String TOPIC_PREFIX = "solace/samples/";  // used as the topic "root"
    private static final String API = "JCSMP";
    private static final int APPROX_MSG_RATE_PER_SEC = 100;
    private static final int PAYLOAD_SIZE = 512;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2 by default; any backend swap follows the logging rule in implement-mode.md Step 3

    private static volatile int msgSentCounter = 0;                   // num messages sent
    private static volatile boolean isShutdown = false;
    private static JCSMPSession session;
    private static XMLMessageProducer producer;
    private static ScheduledExecutorService statsPrintingThread;

    /** Main. */
    public static void main(String... args) throws JCSMPException, IOException, InterruptedException {
        trace(API + " " + APP_NAME + " initializing...");
        try {
            setupSolace(args);
            // graceful shutdown: a SIGINT (Ctrl-C) signals the main loop to stop, then the
            // hook joins the main thread so the cleanup in teardownSolace() (stop
            // publishing, close the session) runs to completion before the JVM halts. The
            // JVM exits as soon as all shutdown hooks return, so the hook must wait, not
            // just set a flag.
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                trace("Shutdown signal received, stopping publisher...");
                isShutdown = true;
                try {
                    mainThread.join(5000);  // wait for the main thread's session close
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
                        logger.warn("Session reconnecting; direct messages sent while down are lost (at-most-once)");
                        break;
                    case RECONNECTED:   // automatic reconnect succeeded, session re-established
                        logger.info("Session reconnected; publishing continues");
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

        // DIRECT producer: the streaming-publish event handler parameter is MANDATORY
        // (the API rejects a null handler with "Blocking publishing mode is not
        // supported"), but direct messaging is at-most-once so the broker sends no ACK:
        // the handler's callbacks effectively never fire for an ACK, and the error
        // callback only logs. This is the inverse of the guaranteed publisher, which acts
        // on each ACK/NACK and correlates by key.
        producer = session.getMessageProducer(new DirectPublishEventHandler());
    }

    private static void runPublishLoop() throws IOException {
        statsPrintingThread = Executors.newSingleThreadScheduledExecutor();
        statsPrintingThread.scheduleAtFixedRate(() -> {
            trace(String.format("%s %s Published msgs/s: %,d",API,APP_NAME,msgSentCounter));  // simple way of calculating message rates
            msgSentCounter = 0;
        }, 1, 1, TimeUnit.SECONDS);

        trace(API + " " + APP_NAME + " connected, and running. Press [ENTER] or Ctrl-C to quit.");
        byte[] payload = new byte[PAYLOAD_SIZE];  // preallocate
        // BytesMessage carries a binary payload; "XML" in XMLMessageProducer/BytesMessage
        // is legacy JCSMP API naming, NOT an XML payload format
        // best practice: createMessage(...) makes a SESSION-INDEPENDENT message, the model
        // Solace recommends for new Java applications (session-dependent messages are legacy).
        BytesMessage message = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);  // preallocate
        trace("Publishing to topic '"+ TOPIC_PREFIX + API.toLowerCase() +
                "/direct/pub/...', at-most-once (no broker ACK, no redelivery).");
        while (System.in.available() == 0 && !isShutdown) {  // loop until ENTER pressed, or shutdown flag
            message.reset();  // ready for reuse
            // each loop, change the payload as an example
            char chosenCharacter = (char)(Math.round(msgSentCounter % 26) + 65);  // choose a "random" letter [A-Z]
            Arrays.fill(payload,(byte)chosenCharacter);  // fill the payload completely with that char
            // setData() writes the binary attachment; consumers read it back with getData(),
            // NOT getBytes() (which reads the separate, empty XML-content part).
            message.setData(payload);
            // explicit DIRECT delivery: DIRECT is the API default, but stating it makes the
            // at-most-once intent unambiguous (the inverse of the guaranteed publisher's PERSISTENT)
            message.setDeliveryMode(DeliveryMode.DIRECT);
            message.setApplicationMessageId(UUID.randomUUID().toString());  // as an example
            // NO correlation key: direct is at-most-once with no broker ACK, so there is
            // nothing to correlate (the inverse of the guaranteed publisher, which sets a
            // key for local ACK/NACK correlation)
            String topicString = new StringBuilder(TOPIC_PREFIX).append(API.toLowerCase())
                    .append("/direct/pub/").append(chosenCharacter).toString();
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
        // direct is at-most-once: no outstanding broker ACKs to drain, so close immediately
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

    /** Minimal mandatory producer event handler. Direct messaging is at-most-once with
     *  no broker ACK, so responseReceivedEx is not expected to fire for an ACK; the
     *  handler exists only because the producer requires one. handleErrorEx logs the
     *  error, quits on an exhausted-reconnect transport failure, and renders the broker
     *  subcode when the cause carries one. **/
    private static class DirectPublishEventHandler implements JCSMPStreamingPublishCorrelatingEventHandler {

        @Override
        public void responseReceivedEx(Object key) {
            // no broker ACK in direct messaging; nothing to do
        }

        // can be called for ACL violations and connection loss; direct has no
        // Persistent NACKs (no correlation key), so this never fires for a NACK
        @Override
        public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
            logger.warn("### Direct producer handleErrorEx() callback:", cause);
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
