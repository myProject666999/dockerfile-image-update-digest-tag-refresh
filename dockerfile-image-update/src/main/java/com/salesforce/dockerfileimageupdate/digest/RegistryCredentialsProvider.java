package com.salesforce.dockerfileimageupdate.digest;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Provides credentials for private registries, keyed by registry host.
 *
 * The default implementation reads the {@value #CREDENTIALS_ENV_VAR} environment variable
 * with entries of the form {@code host=user:password} separated by commas, e.g.
 * {@code my.registry.com=robot:secret,other.io=ci:hunter2}.
 */
public class RegistryCredentialsProvider {

    public static final String CREDENTIALS_ENV_VAR = "DFIU_REGISTRY_CREDENTIALS";

    /**
     * A username/password pair for one registry.
     */
    public static class RegistryCredential {
        private final String username;
        private final String password;

        public RegistryCredential(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }
    }

    private final Map<String, RegistryCredential> credentialsByHost;

    public RegistryCredentialsProvider(Map<String, RegistryCredential> credentialsByHost) {
        this.credentialsByHost = new HashMap<>(credentialsByHost);
    }

    public static RegistryCredentialsProvider empty() {
        return new RegistryCredentialsProvider(new HashMap<>());
    }

    public static RegistryCredentialsProvider fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    public static RegistryCredentialsProvider fromEnvironment(Function<String, String> env) {
        Map<String, RegistryCredential> credentials = new HashMap<>();
        String raw = env.apply(CREDENTIALS_ENV_VAR);
        if (raw != null && !raw.trim().isEmpty()) {
            for (String entry : raw.split(",")) {
                String trimmed = entry.trim();
                int eq = trimmed.indexOf('=');
                int colon = trimmed.indexOf(':', eq);
                if (eq <= 0 || colon <= eq + 1) {
                    continue;
                }
                credentials.put(trimmed.substring(0, eq),
                        new RegistryCredential(trimmed.substring(eq + 1, colon), trimmed.substring(colon + 1)));
            }
        }
        return new RegistryCredentialsProvider(credentials);
    }

    /**
     * @return credentials for {@code registryHost}, or empty when none are configured; the
     * resolver will then attempt anonymous access
     */
    public Optional<RegistryCredential> credentialsFor(String registryHost) {
        return Optional.ofNullable(credentialsByHost.get(registryHost));
    }
}
