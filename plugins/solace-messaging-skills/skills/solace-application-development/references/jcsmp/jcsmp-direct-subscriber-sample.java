/*
 * Derived from patterns/DirectSubscriber.java
 *   SolaceSamples/solace-samples-java-jcsmp
 *   https://github.com/SolaceSamples/solace-samples-java-jcsmp
 *   Licensed under the Apache License, Version 2.0.
 *
 * Elevated to documented best practices per the JCSMP Best Practices guide:
 *   https://docs.solace.com/API/API-Developer-Guide-JCSMP/JCSMP-API-Best-Practices.md
 *
 * Reference sample. Wired for a basic direct (at-most-once) pub/sub journey:
 * basic-auth connect with reconnect-configured channel properties, a plain topic
 * subscription (no queue, no provisioning), an async DIRECT consumer that detects
 * egress discards, session-event handling, and a graceful SIGINT shutdown. Direct
 * messaging is at-most-once: there is no broker ACK and no redelivery, so there is
 * NO CLIENT-ack flow here (the inverse of the guaranteed consumer). Structured as
 * setupSolace(...), awaitMessages(), and teardownSolace(), with teardown run from
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
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DirectSubscriber {

    private static final String APP_NAME = DirectSubscriber.class.getSimpleName();
    // topic to subscribe to directly; matches the direct publisher sample's topic root
    private static final String TOPIC_NAME = "solace/samples/jcsmp/direct/pub/>";
    private static final String API = "JCSMP";

    private static volatile int msgRecvCounter = 0;        // num messages received
    private static volatile boolean hasDetectedDiscard = false;  // any egress discards seen?
    private static volatile boolean isShutdown = false;    // are we done?
    private static JCSMPSession session;

    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2, but could also use SLF4J, JCL, etc.

    /** This is the main app. Use this type of app for receiving Direct (at-most-once) messages from a topic. */
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
                trace("Shutdown signal received, stopping subscriber...");
                isShutdown = true;
                try {
                    mainThread.join(5000);  // wait for the main thread's session close
                } catch (InterruptedException e) {
                    // nothing more we can do; the JVM is halting
                }
            }));
            awaitMessages();
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
        // REAPPLY_SUBSCRIPTIONS re-adds the Direct topic subscription automatically after an
        // automatic reconnect (JCSMP default is false). A Direct subscription is a client-side
        // session subscription, not held on a broker queue, so without this it would be lost
        // on reconnect and delivery would silently stop.
        properties.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);
        // best practice: register a session event handler at session creation and handle
        // each event appropriately, rather than only logging it
        session = JCSMPFactory.onlyInstance().createSession(properties, null, new SessionEventHandler() {
            @Override
            public void handleEvent(SessionEventArgs event) {
                logger.info("### Received a Session event: " + event);
                switch (event.getEvent()) {
                    case RECONNECTING:  // session went down, automatic reconnect attempt in progress
                        logger.warn("Session reconnecting; direct delivery is paused until re-established");
                        break;
                    case RECONNECTED:   // automatic reconnect succeeded, session re-established
                        logger.info("Session reconnected; the subscription is restored and delivery resumes");
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

        // DIRECT consumer: acquire the async message consumer, add a topic subscription
        // on the session (no queue, no provisioning), then start the consumer. Direct
        // messaging is at-most-once: messages flow straight to the subscriber with no
        // broker ACK and no redelivery.
        final XMLMessageConsumer consumer = session.getMessageConsumer(new DirectMessageListener());
        Topic topic = JCSMPFactory.onlyInstance().createTopic(TOPIC_NAME);
        trace(String.format("Adding direct topic subscription '%s'.", TOPIC_NAME));
        // pass true (wait-for-confirm) to block until the broker confirms the subscription is
        // in place, so the route is live before this subscriber reports ready. Direct is
        // at-most-once: a message published to an unconfirmed route would simply be dropped.
        session.addSubscription(topic, true);
        consumer.start();
        trace(String.format("%s subscribed and consuming. Press [ENTER] or Ctrl-C to quit.", APP_NAME));
    }

    private static void awaitMessages() throws IOException, InterruptedException {
        // async direct receive working now, so time to wait until done...
        while (System.in.available() == 0 && !isShutdown) {
            Thread.sleep(1000);  // wait 1 second
            trace(String.format("%s %s Received msgs/s: %,d",API,APP_NAME,msgRecvCounter));  // simple way of calculating message rates
            msgRecvCounter = 0;
            if (hasDetectedDiscard) {  // the broker dropped at least one message before this subscriber
                trace("*** Egress discard detected (at-most-once: a direct message was dropped) ***");
                hasDetectedDiscard = false;  // only show the warning once per second
            }
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

    /** Very simple static inner class, used for receiving direct messages from the topic. **/
    private static class DirectMessageListener implements XMLMessageListener {

        @Override
        public void onReceive(BytesXMLMessage msg) {
            msgRecvCounter++;
            // Payload lives in the binary attachment: the publisher writes it with setData(),
            // so read it with getData(), NOT getBytes() (the separate, empty XML-content part).
            if (msg instanceof BytesMessage) {
                trace(API + " " + APP_NAME + " received: "
                        + new String(((BytesMessage) msg).getData(), StandardCharsets.UTF_8));
            }
            // at-most-once egress-discard evidence: the broker sets the discard indication
            // when it had to drop direct messages destined for this subscriber (e.g. a slow
            // consumer overran its egress buffer). There is NO redelivery in direct messaging.
            if (msg.getDiscardIndication()) {
                hasDetectedDiscard = true;
            }
            // Direct messaging is at-most-once: there is no consumer-side acknowledgement
            // here (the inverse of the guaranteed CLIENT-ack consumer). The message is
            // consumed as it arrives; the broker holds no copy and expects no ack.
        }

        @Override
        public void onException(JCSMPException e) {
            logger.warn("### Direct consumer flow handler received exception. Stopping!!", e);
            if (e instanceof JCSMPTransportException) {  // all reconnect attempts failed
                isShutdown = true;  // let's quit; or, could initiate a new connection attempt
            } else if (e instanceof JCSMPErrorResponseException) {  // broker error response carries extra detail
                JCSMPErrorResponseException ere = (JCSMPErrorResponseException) e;
                logger.warn("Specifics: " + JCSMPErrorResponseSubcodeEx.getSubcodeAsString(ere.getSubcodeEx())
                        + ": " + ere.getResponsePhrase());
            }
        }
    }
}
