/*
 * Runtime smoke test for SolaceConnectionConfig, driven by check-samples.sh.
 *
 * NOT a reference sample: compiling the helper never executes its config.json
 * parser or CLI-args fallback, so this tiny driver is compiled against the
 * just-built helper and RUN to prove those code paths actually behave. It
 * exercises the round-trip, JSON unescaping, the generic extra-key pass-through,
 * the CLI-args fallback, and the three fail-fast paths (missing key, blank config
 * value, blank CLI arg). The round-trip case also builds a MessagingService from
 * the loaded Properties (build() does not connect), so the property keys the
 * helper produces are accepted by the Java API.
 *
 * One case per JVM invocation: the parser reads a CWD-relative config.json and
 * the fail-fast paths throw/exit, so check-samples.sh drives each case from its own
 * working directory and checks the exit code. The case name is argv[0]; any
 * connection arguments the case needs are built here, not taken from argv.
 */
import com.solace.messaging.MessagingService;
import com.solace.messaging.config.SolaceProperties.AuthenticationProperties;
import com.solace.messaging.config.SolaceProperties.ClientProperties;
import com.solace.messaging.config.SolaceProperties.ServiceProperties;
import com.solace.messaging.config.SolaceProperties.TransportLayerProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.samples.java.SolaceConnectionConfig;
import java.util.Properties;

public final class ConfigSmokeTest {

    public static void main(String[] argv) {
        String mode = argv.length > 0 ? argv[0] : "";
        switch (mode) {
            case "file-roundtrip": {
                Properties p = SolaceConnectionConfig.load(new String[0], "smoke").toServiceProperties();
                check(p, TransportLayerProperties.HOST, "tcps://localhost:55443");
                check(p, ServiceProperties.VPN_NAME, "smoke-vpn");
                check(p, AuthenticationProperties.SCHEME_BASIC_USER_NAME, "smoke-user");
                check(p, AuthenticationProperties.SCHEME_BASIC_PASSWORD, "smoke-pass");
                // the API accepts the loaded keys: build() validates the configuration and
                // resolves the host without connecting
                MessagingService.builder(ConfigurationProfile.V1).fromProperties(p).build();
                break;
            }
            case "file-escaped": {
                // config.json username is "us\"er\\name"; unescaping yields us"er\name.
                Properties p = SolaceConnectionConfig.load(new String[0], "smoke").toServiceProperties();
                check(p, AuthenticationProperties.SCHEME_BASIC_USER_NAME, "us\"er\\name");
                break;
            }
            case "args-fallback": {
                Properties p = SolaceConnectionConfig.load(
                        new String[] {"tcp://args.example:55555", "args-vpn", "args-user", "args-pass"}, "smoke")
                        .toServiceProperties();
                check(p, TransportLayerProperties.HOST, "tcp://args.example:55555");
                check(p, ServiceProperties.VPN_NAME, "args-vpn");
                check(p, AuthenticationProperties.SCHEME_BASIC_USER_NAME, "args-user");
                break;
            }
            case "extra-key-passthrough": {
                // config.json carries an extra flat string key (the client name) beyond the
                // four connection keys; the generic pass-through must land it in the Properties.
                Properties p = SolaceConnectionConfig.load(new String[0], "smoke").toServiceProperties();
                check(p, TransportLayerProperties.HOST, "tcps://smoke.example:55443");
                check(p, ClientProperties.NAME, "smoke-client");
                break;
            }
            case "missing-key":
                // config.json omits the required host key.
                expectFailure(() -> SolaceConnectionConfig.load(new String[0], "smoke"));
                break;
            case "blank-value":
                // config.json has a blank host value.
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

    private static void check(Properties props, String key, String expected) {
        String actual = props.getProperty(key);
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
