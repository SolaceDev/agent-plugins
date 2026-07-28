/*
 * Shared connection-config helper for the JCSMP reference samples.
 *
 * Loads connection properties and turns them into a JCSMPProperties. Source
 * precedence:
 *   1. a config.json in the working directory (the project root), when present.
 *      Its keys are JCSMP property-name strings, the VALUES of the JCSMPProperties
 *      constants (for example the HOST constant's value is "host"): host, vpn_name,
 *      username, password. EVERY flat string key in the file is passed through to
 *      JCSMPProperties.setProperty(key, value), so further STRING-TYPED session
 *      properties (for example "client_name") work with no parser change.
 *      Pass-through limitations: string-typed session properties only. A boolean-
 *      or integer-typed property set from a string is stored but not read back as
 *      its real type, and channel properties (reconnect tuning) live in a nested
 *      JCSMPChannelProperties object that a flat string key cannot express. A
 *      mistyped key is stored and silently ignored by the API. config.json holds
 *      broker credentials, so it MUST be gitignored and never committed.
 *   2. otherwise the command-line arguments:
 *      <host:port> <message-vpn> <client-username> [password]
 *
 * The config.json reader is a deliberately minimal parser for a flat, string-keyed
 * file. It avoids adding a JSON-library dependency so the samples keep their single
 * Solace dependency (sol-jcsmp); it is NOT a general-purpose JSON parser and reads
 * only flat string key/value pairs (string pairs anywhere in the file are read as if
 * flat, so keep config.json a single flat object).
 */

package com.solace.samples.jcsmp;

import com.solacesystems.jcsmp.JCSMPProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SolaceConnectionConfig {

    private static final String CONFIG_FILE = "config.json";

    // every flat string key/value pair matches a "key": "value" form; both sides unescape
    private static final Pattern STRING_PAIR = Pattern.compile(
            "\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    // all loaded properties, keyed by JCSMP property-name strings ("host", "vpn_name", ...)
    private final Map<String, String> properties;

    private SolaceConnectionConfig(Map<String, String> properties) {
        requireNonBlank(properties.get(JCSMPProperties.HOST), JCSMPProperties.HOST);
        requireNonBlank(properties.get(JCSMPProperties.VPN_NAME), JCSMPProperties.VPN_NAME);
        requireNonBlank(properties.get(JCSMPProperties.USERNAME), JCSMPProperties.USERNAME);
        // password is optional: absent or blank means no password is configured
        this.properties = properties;
    }

    /**
     * Load connection details from config.json when present in the working directory,
     * otherwise from the command-line arguments, and log which source was used. Prints a
     * usage line and exits when neither source supplies host, message-vpn, and
     * client-username; rejects a blank host, message-vpn, or client-username from either
     * source.
     *
     * @param args    the app's command-line arguments (the fallback source)
     * @param appName the application name, used only in the usage message
     * @return the loaded connection details
     */
    public static SolaceConnectionConfig load(String[] args, String appName) {
        SolaceConnectionConfig fromFile = tryLoadConfigFile();
        if (fromFile != null) {
            trace(String.format("%s: using connection details from %s (host=%s).",
                    appName, CONFIG_FILE, fromFile.properties.get(JCSMPProperties.HOST)));
            return fromFile;
        }
        if (args.length < 3) {
            trace(String.format("No %s in the working directory; provide connection details on the command line.",
                    CONFIG_FILE));
            trace(String.format("Usage: %s <host:port> <message-vpn> <client-username> [password]%n", appName));
            System.exit(-1);
        }
        Map<String, String> fromArgs = new LinkedHashMap<>();
        fromArgs.put(JCSMPProperties.HOST, args[0]);
        fromArgs.put(JCSMPProperties.VPN_NAME, args[1]);
        fromArgs.put(JCSMPProperties.USERNAME, args[2]);
        if (args.length > 3) {
            fromArgs.put(JCSMPProperties.PASSWORD, args[3]);
        }
        SolaceConnectionConfig config = new SolaceConnectionConfig(fromArgs);
        trace(String.format("%s: using connection details from command-line arguments (host=%s).",
                appName, args[0]));
        return config;
    }

    /**
     * Build a JCSMPProperties from every loaded property (generic pass-through). Each
     * key is a JCSMP property-name string applied via setProperty(key, value); only a
     * blank password is skipped (blank means no password is configured).
     */
    public JCSMPProperties toSessionProperties() {
        final JCSMPProperties sessionProperties = new JCSMPProperties();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String value = entry.getValue();
            if (JCSMPProperties.PASSWORD.equals(entry.getKey()) && (value == null || value.isEmpty())) {
                continue;
            }
            sessionProperties.setProperty(entry.getKey(), value);
        }
        return sessionProperties;
    }

    private static SolaceConnectionConfig tryLoadConfigFile() {
        Path path = Paths.get(CONFIG_FILE);
        if (!Files.exists(path)) {
            return null;  // no config file: fall back to the command-line arguments
        }
        if (!Files.isReadable(path)) {
            // present but unreadable (for example a permission-locked credentials file): fail
            // loud rather than silently falling back to different command-line connection args
            throw new IllegalStateException(CONFIG_FILE + " exists but is not readable; fix its "
                    + "permissions or remove it to use command-line arguments instead");
        }
        final String json;
        try {
            json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + CONFIG_FILE, e);
        }
        Map<String, String> parsed = parseFlatStringPairs(json);
        for (String required : new String[] {
                JCSMPProperties.HOST, JCSMPProperties.VPN_NAME, JCSMPProperties.USERNAME}) {
            if (!parsed.containsKey(required)) {
                throw new IllegalStateException(CONFIG_FILE + " is missing required key \"" + required + "\"");
            }
        }
        return new SolaceConnectionConfig(parsed);
    }

    /** Read every flat string key/value pair; a later duplicate of a key wins. */
    private static Map<String, String> parseFlatStringPairs(String json) {
        Map<String, String> pairs = new LinkedHashMap<>();
        Matcher matcher = STRING_PAIR.matcher(json);
        while (matcher.find()) {
            pairs.put(unescape(matcher.group(1)), unescape(matcher.group(2)));
        }
        return pairs;
    }

    /** Reject a null or blank required connection value, whatever its source. */
    private static String requireNonBlank(String value, String key) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("connection value for \"" + key + "\" must not be blank");
        }
        return value;
    }

    /** Minimal JSON string unescaping for the sequences a connection value may contain. */
    private static String unescape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    default:  sb.append(next); break;  // covers \" \\ and \/
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Demo narration sink: every status line in this helper funnels through this one
     *  method. An application replaces this single body to route narration to its
     *  logger or reporting system. */
    private static void trace(String message) {
        System.out.println(message);
    }
}
