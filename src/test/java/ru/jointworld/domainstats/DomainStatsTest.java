package ru.jointworld.domainstats;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DomainStatsTest {
    @TempDir Path temp;

    @Test void normalizesDomainsWithoutMergingSubdomains() {
        assertEquals("play.example.org", DomainNames.normalize("  PLAY.Example.org.:25565  "));
        assertEquals("play.example.org", DomainNames.normalize("play.example.org\0FML\0"));
        assertEquals("xn--e1afmkfd.xn--p1ai", DomainNames.normalize("пример.рф"));
        assertNotEquals(DomainNames.normalize("a.example.org"), DomainNames.normalize("b.example.org"));
        assertEquals("2001:db8::1", DomainNames.normalize("[2001:DB8::1]:25565"));
        assertEquals("127.0.0.1", DomainNames.normalize("127.0.0.1:25565"));
        assertEquals(DomainNames.UNKNOWN, DomainNames.fromConnection(null));
    }

    @Test void rejectsUnsafeDomainText() {
        for (String domain : new String[]{"https://example.org", "bad domain.org", "x\nforged", "<red>hi", "abc|def", ".", "a/b"}) {
            assertThrows(IllegalArgumentException.class, () -> DomainNames.normalize(domain), domain);
            assertEquals(DomainNames.UNKNOWN, DomainNames.fromConnection(domain));
        }
    }

    @Test void countsUniqueNicksRepeatJoinsAndGlobalDeduplication() throws Exception {
        try (StatsStore store = new StatsStore(temp.resolve("stats.db"))) {
            store.record("a.example.org", "Nick", 1);
            store.record("a.example.org", "nick", 2);
            store.record("a.example.org", "Second", 3);
            store.record("b.example.org", "Nick", 4);
            assertEquals(2, store.domain("a.example.org").unique);
            assertEquals(3, store.domain("a.example.org").joins);
            assertEquals(2, store.total().unique);
            assertEquals(4, store.total().joins);
            assertEquals(2, store.domains().size());
            assertEquals("nick", store.players("a.example.org", 1, 0).get(0).nickname);
            assertEquals(2, store.players("a.example.org", 1, 0).get(0).joins);
            assertEquals("Second", store.players("a.example.org", 1, 1).get(0).nickname);
            assertTrue(store.players("a.example.org", 1, Long.MAX_VALUE).isEmpty());
            assertEquals(0, store.domain("unused.example.org").joins);
        }
    }

    @Test void persistsAfterClosingAndReopening() throws Exception {
        Path file = temp.resolve("persist.db");
        try (StatsStore store = new StatsStore(file)) { store.record("a.example.org", "Nick", 1); }
        try (StatsStore store = new StatsStore(file)) {
            assertEquals(1, store.total().joins);
            store.record("a.example.org", "NICK", 2);
            assertEquals(1, store.total().unique);
            assertEquals(2, store.total().joins);
        }
    }

    @Test void storesNicknamesAsDataAndPreservesCounters() throws Exception {
        try (StatsStore store = new StatsStore(temp.resolve("sql.db"))) {
            store.record("example.org", "x'); DROP TABLE visits;--", 1);
            store.record("example.org", "Bedrock.Name", 2);
            assertEquals(2, store.total().unique);
            assertEquals(2, store.total().joins);
        }
    }

    @Test void doesNotCountServerSwitchOrConfuseReconnections() {
        SessionTracker sessions = new SessionTracker();
        Object oldPlayer = new String("same UUID");
        Object newPlayer = new String("same UUID");
        assertTrue(sessions.firstConnection(oldPlayer));
        assertFalse(sessions.firstConnection(oldPlayer));
        assertTrue(sessions.firstConnection(newPlayer));
        sessions.disconnected(oldPlayer);
        assertFalse(sessions.firstConnection(newPlayer));
    }

    @Test void loadsDefaultsAndDeduplicatesConfiguredDomains() throws Exception {
        Settings defaults = Settings.load(temp);
        assertTrue(defaults.importForcedHosts);
        assertEquals(8, defaults.pageSize);
        Files.writeString(temp.resolve("config.properties"), "domains=PLAY.example.org,play.example.org,пример.рф\npage-size=3\nimport-forced-hosts=false\n");
        Settings configured = Settings.load(temp);
        assertEquals(2, configured.domains.size());
        assertFalse(configured.importForcedHosts);
        assertEquals(3, configured.pageSize);
    }

    @Test void rejectsInvalidConfigRatherThanPartiallyApplying() throws Exception {
        Files.writeString(temp.resolve("config.properties"), "page-size=0\n");
        assertThrows(IllegalArgumentException.class, () -> Settings.load(temp));
        Files.writeString(temp.resolve("config.properties"), "import-forced-hosts=maybe\n");
        assertThrows(IllegalArgumentException.class, () -> Settings.load(temp));
    }

    @Test void checksOnlyExactPermissionAndTrustsProviderDenials() {
        CommandSource denied = (CommandSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{CommandSource.class}, (proxy, method, args) -> {
            if (method.getName().equals("hasPermission")) {
                assertEquals("domain.use", args[0]);
                return false;
            }
            return null;
        });
        assertFalse(DomainStatsPlugin.allowed(denied));
        CommandSource permitted = (CommandSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{CommandSource.class},
                (proxy, method, args) -> method.getName().equals("hasPermission") ? Boolean.TRUE : null);
        assertTrue(DomainStatsPlugin.allowed(permitted));
        ConsoleCommandSource console = (ConsoleCommandSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ConsoleCommandSource.class},
                (proxy, method, args) -> { throw new AssertionError("Console must be allowed"); });
        assertTrue(DomainStatsPlugin.allowed(console));
    }
}
