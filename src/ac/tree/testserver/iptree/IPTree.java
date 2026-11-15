package ac.tree.testserver.iptree;

import org.bukkit.plugin.java.JavaPlugin;

public class IPTree extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private DataProcessor dataProcessor;
    private LogProcessor logProcessor;
    private boolean sleepMode = false;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        this.databaseManager = new DatabaseManager(this);
        this.dataProcessor = new DataProcessor(this);
        this.logProcessor = new LogProcessor(this);

        if (!databaseManager.connect()) {
            getLogger().severe("Failed to connect to database. Entering Sleep Mode.");
            sleepMode = true;
        }
        
        CommandManager commandManager = new CommandManager(this);
        getCommand("iptree").setExecutor(commandManager);
        getCommand("iptree").setTabCompleter(commandManager);

        if (!sleepMode && configManager.isLiveMode()) {
            getServer().getPluginManager().registerEvents(new PlayerEventListener(this), this);
            getLogger().info("LiveMode enabled. Listening for player events.");
        }

        getLogger().info("IPTree Loaded");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("IPTree Disabled");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public DataProcessor getDataProcessor() {
        return dataProcessor;
    }

    public LogProcessor getLogProcessor() {
        return logProcessor;
    }

    public boolean isSleepMode() {
        return sleepMode;
    }
}