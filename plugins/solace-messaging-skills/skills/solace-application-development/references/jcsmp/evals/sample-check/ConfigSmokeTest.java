/*
 * Runtime smoke test for SolaceConnectionConfig, driven by compile.sh.
 *
 * NOT a reference sample: compiling the helper never executes its config.json
 * parser or CLI-args fallback, so this tiny driver is compiled against the
 * just-built helper and RUN to prove those code paths actually behave. It
 * exercises the round-trip, JSON unescaping, the generic extra-key pass-through,
 * the CLI-args fallback, and the three fail-fast paths (missing key, blank config
 * value, blank CLI arg).
 *
 * One case per JVM invocation: the parser reads a CWD-relative config.json and
 * the fail-fast paths throw/exit, so compile.sh drives each case from its own
 * working directory and checks the exit code. The case name is argv[0]; any
 * connection arguments the case needs are built here, not taken from argv.
 */
import com.solace.samples.jcsmp.SolaceConnectionConfig;
import com.solacesystems.jcsmp.JCSMPProperties;

public final class ConfigSmokeTest {

    public static void main(String[] argv) {
        String mode = argv.length > 0 ? argv[0] : "";
        switch (mode) {
            case "file-roundtrip": {
                JCSMPProperties p = SolaceConnectionConfig.load(new String[0], "smoke").toSessionProperties();
                check(p, JCSMPProperties.HOST, "tcps://smoke.example:55443");
                check(p, JCSMPProperties.VPN_NAME, "smoke-vpn");
                check(p, JCSMPProperties.USERNAME, "smoke-user");
                check(p, JCSMPProperties.PASSWORD, "smoke-pass");
                break;
            }
            case "file-escaped": {
                // config.json username is "us\"er\\name"; unescaping yields us"er\name.
                JCSMPProperties p = SolaceConnectionConfig.load(new String[0], "smoke").toSessionProperties();
                check(p, JCSMPProperties.USERNAME, "us\"er\\name");
                break;
            }
            case "args-fallback": {
                JCSMPProperties p = SolaceConnectionConfig.load(
                        new String[] {"tcp://args.example:55555", "args-vpn", "args-user", "args-pass"}, "smoke")
                        .toSessionProperties();
                check(p, JCSMPProperties.HOST, "tcp://args.example:55555");
                check(p, JCSMPProperties.VPN_NAME, "args-vpn");
                check(p, JCSMPProperties.USERNAME, "args-user");
                break;
            }
            case "extra-key-passthrough": {
                // config.json carries an extra flat string key (client_name) beyond the
                // four connection keys; the generic pass-through must land it in the
                // JCSMPProperties (string-typed session properties only).
                JCSMPProperties p = SolaceConnectionConfig.load(new String[0], "smoke").toSessionProperties();
                check(p, JCSMPProperties.HOST, "tcps://smoke.example:55443");
                check(p, JCSMPProperties.CLIENT_NAME, "smoke-client");
                break;
            }
            case "missing-key":
                // config.json omits the required host key.
                expectFailure(() -> SolaceConnectionConfig.load(new String[0], "smoke"));
                break;
            case "blank-value":
                // config.json has "host": "".
                expectFailure(() -> SolaceConnectionConfig.load(new String[0], "smoke"));
                break;
            case "args-blank":
                // no config.json in CWD; a blank host arg must fail fast (not connect blank).
                expectFailure(() -> SolaceConnectionConfig.load(new String[] {"", "args-vpn", "args-user"}, "smoke"));
                break;
            default:
                fail("unknown smoke mode: \"" + mode + "\"");
        }
        System.out.println("SMOKE OK: " + mode);
    }

    private static void check(JCSMPProperties props, String key, String expected) {
        String actual = props.getStringProperty(key);
        if (actual == null || !actual.equals(expected)) {
            fail("key " + key + " expected \"" + expected + "\" but was \"" + actual + "\"");
        }
    }

    private static void expectFailure(Runnable load) {
        try {
            load.run();
        } catch (RuntimeException expected) {
            return;  // fail-fast as designed
        }
        fail("expected a fail-fast exception, but load() returned normally");
    }

    private static void fail(String msg) {
        System.err.println("SMOKE FAIL: " + msg);
        System.exit(1);
    }
}
