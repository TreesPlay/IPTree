package ac.tree.testserver.iptree;

import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    private final IPTree plugin;
    private String sqlHost;
    private int sqlPort;
    private String sqlDatabase;
    private String sqlUsername;
    private String sqlPassword;
    private int joinLeaveRelationHours;
    private int accountIPRelationHours;
    private boolean skipDirtyData;
    private boolean liveMode;
    private String ipDisplayMode;
    private String serverIdentifier;
    private boolean multiServer;
    private String multiServerRole;

    public ConfigManager(IPTree plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        config.addDefault("SQLHost", "127.0.0.1");
        config.addDefault("SQLPort", 3306);
        config.addDefault("SQLDatabase", "mcserver");
        config.addDefault("SQLUsername", "mcserver");
        config.addDefault("SQLPassword", "your-secure-password");
        config.addDefault("JoinLeaveRelationHours", 24);
        config.addDefault("AccountIPRelationHours", 48);
        config.addDefault("SkipDirtyData", true);
        config.addDefault("LiveMode", false);
        config.addDefault("IPDisplayMode", "raw");
        
        if (!config.contains("ServerIdentifier") || "undefined".equals(config.getString("ServerIdentifier"))) {
            config.set("ServerIdentifier", generateServerIdentifier());
            plugin.saveConfig();
        }
        config.addDefault("MultiServer", false);
        config.addDefault("MultiServerRole", "undefined");

        config.options().copyDefaults(true);
        plugin.saveConfig();

        this.sqlHost = config.getString("SQLHost");
        this.sqlPort = config.getInt("SQLPort");
        this.sqlDatabase = config.getString("SQLDatabase");
        this.sqlUsername = config.getString("SQLUsername");
        this.sqlPassword = config.getString("SQLPassword");
        this.joinLeaveRelationHours = config.getInt("JoinLeaveRelationHours");
        this.accountIPRelationHours = config.getInt("AccountIPRelationHours");
        this.skipDirtyData = config.getBoolean("SkipDirtyData", true);
        this.liveMode = config.getBoolean("LiveMode");
        this.ipDisplayMode = config.getString("IPDisplayMode", "raw");
        this.serverIdentifier = config.getString("ServerIdentifier");
        this.multiServer = config.getBoolean("MultiServer");
        this.multiServerRole = config.getString("MultiServerRole");
    }

    private String generateServerIdentifier() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] bytes = new byte[5]; // 40 bits
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public String getSqlHost() { return sqlHost; }
    public int getSqlPort() { return sqlPort; }
    public String getSqlDatabase() { return sqlDatabase; }
    public String getSqlUsername() { return sqlUsername; }
    public String getSqlPassword() { return sqlPassword; }
    public int getJoinLeaveRelationHours() { return joinLeaveRelationHours; }
    public int getAccountIPRelationHours() { return accountIPRelationHours; }
    public boolean isSkipDirtyData() { return skipDirtyData; }
    public boolean isLiveMode() { return liveMode; }
    public String getIpDisplayMode() { return ipDisplayMode; }
    public String getServerIdentifier() { return serverIdentifier; }
    public boolean isMultiServer() { return multiServer; }
    public String getMultiServerRole() { return multiServerRole; }
    
    public void setJoinLeaveRelationHours(int hours) {
        this.joinLeaveRelationHours = hours;
        plugin.getConfig().set("JoinLeaveRelationHours", hours);
        plugin.saveConfig();
    }

    public void setAccountIPRelationHours(int hours) {
        this.accountIPRelationHours = hours;
        plugin.getConfig().set("AccountIPRelationHours", hours);
        plugin.saveConfig();
    }

    public boolean setConfigOption(String key, String value) {
        if (key.toUpperCase().startsWith("SQL") || key.startsWith("MultiServer") || key.equals("ServerIdentifier")) {
            return false; // Not allowed
        }
        
        if (!plugin.getConfig().contains(key)) {
            return false; // Key doesn't exist
        }

        Object currentValue = plugin.getConfig().get(key);
        Object newValue = null;

        try {
            if (currentValue instanceof Integer) {
                newValue = Integer.parseInt(value);
            } else if (currentValue instanceof Boolean) {
                newValue = Boolean.parseBoolean(value);
            } else {
                newValue = value;
            }
        } catch (Exception e) {
            return false; // Parsing failed
        }

        plugin.getConfig().set(key, newValue);
        plugin.saveConfig();
        loadConfig(); // Refresh local fields
        return true;
    }

    public String getConfigOption(String key) {
         if (key.toUpperCase().startsWith("SQL")) {
            return null; // Not allowed
        }
        if (!plugin.getConfig().contains(key)) {
            return null;
        }
        return plugin.getConfig().get(key).toString();
    }

    public java.util.List<String> getAccessibleConfigKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String key : plugin.getConfig().getKeys(false)) {
            if (!key.toUpperCase().startsWith("SQL")) {
                keys.add(key);
            }
        }
        return keys;
    }

    public boolean isModifiableBooleanOption(String key) {
        if (key.toUpperCase().startsWith("SQL") || key.startsWith("MultiServer") || key.equals("ServerIdentifier")) {
            return false;
        }
        if (!plugin.getConfig().contains(key)) {
            return false;
        }
        return plugin.getConfig().get(key) instanceof Boolean;
    }
}