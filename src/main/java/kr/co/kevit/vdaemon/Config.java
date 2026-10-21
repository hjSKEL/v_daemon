package kr.co.kevit.vdaemon;

import java.io.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.Properties;
import javax.net.ssl.*;

final class Config {
    private final Properties values = new Properties();
    Config(Path path) throws IOException {
        if (Files.exists(path)) try (var in = Files.newInputStream(path)) { values.load(in); }
    }
    String get(String key, String fallback) {
        String env = System.getenv("VDAEMON_" + key.toUpperCase().replace('.', '_'));
        return env == null ? values.getProperty(key, fallback) : env;
    }
    int number(String key, int fallback) { return Integer.parseInt(get(key, String.valueOf(fallback))); }
    SSLContext sslContext() throws Exception {
        KeyManager[] keys = null;
        TrustManager[] trust = null;
        if (!get("tls.keyStore", "").isBlank()) {
            var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(store("keyStore"), get("tls.keyStorePassword", "").toCharArray());
            keys = km.getKeyManagers();
        }
        if (!get("tls.trustStore", "").isBlank()) {
            var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(store("trustStore")); trust = tm.getTrustManagers();
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys, trust, null);
        return context;
    }
    private KeyStore store(String name) throws Exception {
        var store = KeyStore.getInstance(get("tls." + name + "Type", "PKCS12"));
        try (var in = Files.newInputStream(Path.of(get("tls." + name, "")))) {
            store.load(in, get("tls." + name + "Password", "").toCharArray());
        }
        return store;
    }
}
