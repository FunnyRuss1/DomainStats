package ru.jointworld.domainstats;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Accessed only by the plugin's single database worker after initialization */
public final class StatsStore implements AutoCloseable {
    private final Connection connection;

    public StatsStore(Path file) throws SQLException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                if (version.next() && version.getInt(1) > 1) {
                    throw new SQLException("Database created by a newer DomainStats version");
                }
            }
            statement.execute("CREATE TABLE IF NOT EXISTS visits (domain TEXT NOT NULL, nick_key TEXT NOT NULL, nickname TEXT NOT NULL, joins INTEGER NOT NULL CHECK(joins > 0), first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, PRIMARY KEY(domain,nick_key))");
            statement.execute("CREATE INDEX IF NOT EXISTS visits_nick ON visits(nick_key)");
            statement.execute("PRAGMA user_version=1");
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
    }

    public void record(String domain, String nickname, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO visits(domain,nick_key,nickname,joins,first_seen,last_seen) VALUES(?,?,?,1,?,?) "
                        + "ON CONFLICT(domain,nick_key) DO UPDATE SET joins=visits.joins+1,nickname=excluded.nickname,last_seen=excluded.last_seen")) {
            statement.setString(1, domain);
            statement.setString(2, nickname.toLowerCase(Locale.ROOT));
            statement.setString(3, nickname);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    public Map<String, Totals> domains() throws SQLException {
        Map<String, Totals> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT domain,COUNT(*),SUM(joins) FROM visits GROUP BY domain ORDER BY SUM(joins) DESC,domain")) {
            while (rows.next()) result.put(rows.getString(1), new Totals(rows.getLong(2), rows.getLong(3)));
        }
        return result;
    }

    public Totals total() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(DISTINCT nick_key),COALESCE(SUM(joins),0) FROM visits")) {
            rows.next();
            return new Totals(rows.getLong(1), rows.getLong(2));
        }
    }

    public Totals domain(String domain) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*),COALESCE(SUM(joins),0) FROM visits WHERE domain=?")) {
            statement.setString(1, domain);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return new Totals(rows.getLong(1), rows.getLong(2));
            }
        }
    }

    public List<Visitor> players(String domain, int limit, long offset) throws SQLException {
        List<Visitor> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT nickname,joins FROM visits WHERE domain=? ORDER BY joins DESC,nick_key LIMIT ? OFFSET ?")) {
            statement.setString(1, domain);
            statement.setInt(2, limit);
            statement.setLong(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new Visitor(rows.getString(1), rows.getLong(2)));
            }
        }
        return result;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    public static final class Totals {
        public final long unique;
        public final long joins;
        public Totals(long unique, long joins) { this.unique = unique; this.joins = joins; }
    }

    public static final class Visitor {
        public final String nickname;
        public final long joins;
        public Visitor(String nickname, long joins) { this.nickname = nickname; this.joins = joins; }
    }
}
