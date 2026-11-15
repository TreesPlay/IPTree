package ac.tree.testserver.iptree;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.ChatColor;

public class DatabaseManager {

    private final IPTree plugin;
    private Connection connection;

    public DatabaseManager(IPTree plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        ConfigManager config = plugin.getConfigManager();
        
        //Parameters prevent errors like "Unknown initial character set index '255' received from server. Initial client character set can be forced via the 'characterEncoding' property."
        String url = "jdbc:mysql://" + config.getSqlHost() + ":" + config.getSqlPort() + "/" + config.getSqlDatabase() + "?autoReconnect=true&characterEncoding=utf8&useSSL=false&useUnicode=true";
        
        try {
            synchronized (this) {
                if (connection != null && !connection.isClosed()) {
                    return true;
                }
                Class.forName("com.mysql.jdbc.Driver");
                connection = DriverManager.getConnection(url, config.getSqlUsername(), config.getSqlPassword());
                plugin.getLogger().info("Connected to database.");
                createTable();
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Could not connect to database: " + e.getMessage());
            return false;
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void createTable() {
        String sql = "CREATE TABLE IF NOT EXISTS iptree (" +
                     "id INT AUTO_INCREMENT PRIMARY KEY, " +
                     "uuid VARCHAR(36) NOT NULL, " +
                     "jointime TIMESTAMP NOT NULL, " +
                     "exittime TIMESTAMP NULL, " +
                     "ip VARCHAR(45) NOT NULL, " +
                     "ign VARCHAR(16) NOT NULL, " +
                     "srvid VARCHAR(32), " +
                     "INDEX idx_uuid (uuid), " +
                     "INDEX idx_ip (ip)" +
                     ");";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not create table: " + e.getMessage());
        }
    }

    public void insertEntry(UUID uuid, Timestamp joinTime, Timestamp exitTime, String ip, String ign, String srvid) {
        String sql = "INSERT INTO iptree (uuid, jointime, exittime, ip, ign, srvid) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());
            pstmt.setTimestamp(2, joinTime);
            pstmt.setTimestamp(3, exitTime);
            pstmt.setString(4, ip);
            pstmt.setString(5, ign);
            pstmt.setString(6, srvid);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not insert entry: " + e.getMessage());
        }
    }

    public void updateExitTime(UUID uuid, Timestamp joinTime, Timestamp exitTime) {
        String sql = "UPDATE iptree SET exittime = ? WHERE uuid = ? AND jointime = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setTimestamp(1, exitTime);
            pstmt.setString(2, uuid.toString());
            pstmt.setTimestamp(3, joinTime);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not update exit time: " + e.getMessage());
        }
    }
    
    public Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                connect();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return connection;
    }
    
    // Methods for data retrieval will be added as needed for the commands

    public Boolean isDatabaseEmpty() {
        String sql = "SELECT 1 FROM iptree LIMIT 1";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return !rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    public int getEntryCount() {
        String sql = "SELECT COUNT(*) FROM iptree";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public int getUniqueIPCount() {
        String sql = "SELECT COUNT(DISTINCT ip) FROM iptree";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public int getUniqueAccountCount() {
        String sql = "SELECT COUNT(DISTINCT uuid) FROM iptree";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }
    
    public List<String> getConflicts() {
        List<String> conflicts = new ArrayList<>();
        String sql = "SELECT id, ign, jointime FROM iptree WHERE exittime IS NULL";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String ign = rs.getString("ign");
                Timestamp joinTime = rs.getTimestamp("jointime");
                conflicts.add(ChatColor.GOLD + "ID: " + ChatColor.RED + id + ChatColor.GOLD + " | IGN: " + ChatColor.RED + ign + ChatColor.GOLD + " | Join: " + ChatColor.RED + joinTime);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return conflicts;
    }

    public List<Session> getSessions(String ip) {
        List<Session> sessions = new ArrayList<>();
        String sql = "SELECT uuid, jointime, exittime FROM iptree WHERE ip = ? ORDER BY jointime ASC";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, ip);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    sessions.add(new Session(
                        rs.getString("uuid"),
                        rs.getTimestamp("jointime"),
                        rs.getTimestamp("exittime")
                    ));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return sessions;
    }

    public List<String> getRelatedIPs(String uuid) {
        List<String> ips = new ArrayList<>();
        String sql = "SELECT DISTINCT ip FROM iptree WHERE uuid = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, uuid);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ips.add(rs.getString("ip"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return ips;
    }

    /**
     * Returns all distinct UUIDs that have ever logged in with any of the given IPs,
     * regardless of time window.
     */
    public Set<String> getAccountsByIPs(Set<String> ips) {
        Set<String> uuids = new HashSet<>();
        if (ips == null || ips.isEmpty()) return uuids;

        List<String> ipList = new ArrayList<>(ips);
        final int batchSize = 500;

        for (int start = 0; start < ipList.size(); start += batchSize) {
            List<String> batch = ipList.subList(start, Math.min(start + batchSize, ipList.size()));
            StringBuilder sql = new StringBuilder("SELECT DISTINCT uuid FROM iptree WHERE ip IN (");
            sql.append(String.join(",", Collections.nCopies(batch.size(), "?")));
            sql.append(")");

            try (PreparedStatement pstmt = getConnection().prepareStatement(sql.toString())) {
                for (int i = 0; i < batch.size(); i++) {
                    pstmt.setString(i + 1, batch.get(i));
                }
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        uuids.add(rs.getString("uuid"));
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return uuids;
    }
    
    public String getLastKnownName(String uuid) {
        String sql = "SELECT ign FROM iptree WHERE uuid = ? ORDER BY jointime DESC LIMIT 1";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, uuid);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("ign");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "Unknown";
    }
    
    public List<String> getUuidsByName(String name) {
        List<String> uuids = new ArrayList<>();
        String sql = "SELECT DISTINCT uuid FROM iptree WHERE ign = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, name);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    uuids.add(rs.getString("uuid"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return uuids;
    }

    public List<String> getRecentPlayers(int limit) {
        List<String> uuids = new ArrayList<>();
        if (limit <= 0) return uuids;

        String sql = "SELECT uuid FROM iptree GROUP BY uuid ORDER BY MAX(COALESCE(exittime, jointime)) DESC LIMIT ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    uuids.add(rs.getString("uuid"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return uuids;
    }

    /**
     * Finds all UUIDs that are related to the given UUID via shared IPs within the time limit.
     * This uses a self-join to find connections in a single query per UUID.
     */
    public Set<String> getRelatedAccounts(String uuid, int limitHours) {
        Set<String> related = new HashSet<>();
        long limitSeconds = TimeUnit.HOURS.toSeconds(limitHours);
        String sql = "SELECT DISTINCT b.uuid " +
                     "FROM iptree a " +
                     "JOIN iptree b ON a.ip = b.ip " +
                     "WHERE a.uuid = ? " +
                     "AND b.uuid != a.uuid " +
                     "AND TIMESTAMPDIFF(SECOND, COALESCE(a.exittime, DATE_ADD(a.jointime, INTERVAL 30 MINUTE)), b.jointime) <= ? " +
                     "AND TIMESTAMPDIFF(SECOND, COALESCE(b.exittime, DATE_ADD(b.jointime, INTERVAL 30 MINUTE)), a.jointime) <= ?";

        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, uuid);
            pstmt.setLong(2, limitSeconds);
            pstmt.setLong(3, limitSeconds);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    related.add(rs.getString("uuid"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return related;
    }

    public TargetStats getClusterStats(Set<String> uuids, Set<String> ips) {
        if (uuids.isEmpty() && ips.isEmpty()) return null;

        StringBuilder sql = new StringBuilder("SELECT MIN(jointime) as first_seen, MAX(COALESCE(exittime, jointime)) as last_seen, COUNT(*) as entries FROM iptree WHERE ");
        List<Object> params = new ArrayList<>();

        if (!uuids.isEmpty()) {
            sql.append("uuid IN (");
            sql.append(String.join(",", Collections.nCopies(uuids.size(), "?")));
            sql.append(")");
            params.addAll(uuids);
        } else {
            sql.append("ip IN (");
            sql.append(String.join(",", Collections.nCopies(ips.size(), "?")));
            sql.append(")");
            params.addAll(ips);
        }

        try (PreparedStatement pstmt = getConnection().prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Timestamp first = rs.getTimestamp("first_seen");
                    Timestamp last = rs.getTimestamp("last_seen");
                    int count = rs.getInt("entries");
                    if (first != null && last != null) {
                        return new TargetStats(first, last, count);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static class TargetStats {
        public final Timestamp firstSeen;
        public final Timestamp lastSeen;
        public final int entryCount;

        public TargetStats(Timestamp firstSeen, Timestamp lastSeen, int entryCount) {
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.entryCount = entryCount;
        }
    }

    public static class Session {
        public final String uuid;
        public final Timestamp joinTime;
        public final Timestamp exitTime;

        public Session(String uuid, Timestamp joinTime, Timestamp exitTime) {
            this.uuid = uuid;
            this.joinTime = joinTime;
            this.exitTime = exitTime;
        }
    }
}