package ru.jointworld.domainstats;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

public final class Settings {
    public final Set<String> domains;
    public final boolean importForcedHosts;
    public final int pageSize;

    private Settings(Set<String> domains, boolean importForcedHosts, int pageSize) {
        this.domains = Collections.unmodifiableSet(domains);
        this.importForcedHosts = importForcedHosts;
        this.pageSize = pageSize;
    }

    public static Settings load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("config.properties");
        if (!Files.exists(file)) {
            try (InputStream defaults = Settings.class.getResourceAsStream("/config.properties")) {
                if (defaults == null) throw new IOException("Missing default configuration");
                Files.copy(defaults, file);
            }
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        Set<String> domains = new TreeSet<>();
        for (String domain : props.getProperty("domains", "").split(",")) {
            if (!domain.isBlank()) domains.add(DomainNames.normalize(domain));
        }
        int pageSize = Integer.parseInt(props.getProperty("page-size", "8").strip());
        if (pageSize < 1 || pageSize > 30) throw new IllegalArgumentException("page-size must be from 1 to 30");
        String forced = props.getProperty("import-forced-hosts", "true").strip();
        if (!forced.equalsIgnoreCase("true") && !forced.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("import-forced-hosts must be true or false");
        }
        return new Settings(domains, Boolean.parseBoolean(forced), pageSize);
    }
}
