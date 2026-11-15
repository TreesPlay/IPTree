package ac.tree.testserver.iptree;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

public class MultiServerManager {

    private final IPTree plugin;

    public MultiServerManager(IPTree plugin) {
        this.plugin = plugin;
    }

    public void exportLogData(CommandSender sender) {
        String srvid = plugin.getConfigManager().getServerIdentifier();
        File cacheDir = new File(new File(plugin.getDataFolder().getParentFile().getParentFile(), "logs"), "iptree_multisrvcache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        File outputFile = new File(cacheDir, "iptree_logdata_" + srvid + ".yml");
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            // Write Header
            writer.write("server_id: \"" + srvid + "\"");
            writer.newLine();
            writer.write("entries:");
            writer.newLine();

            // Process Logs and Stream to File
            plugin.getLogProcessor().processLogs(sender, (uuid, join, exit, ip, ign) -> {
                try {
                    writer.write("  - uuid: \"" + uuid.toString() + "\"");
                    writer.newLine();
                    writer.write("    ip: \"" + ip + "\"");
                    writer.newLine();
                    writer.write("    ign: \"" + ign + "\"");
                    writer.newLine();
                    writer.write("    join: " + join.getTime());
                    writer.newLine();
                    if (exit != null) {
                        writer.write("    leave: " + exit.getTime());
                        writer.newLine();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            sender.sendMessage(ChatColor.GREEN + "Exported data to " + outputFile.getPath());
            sender.sendMessage(ChatColor.GREEN + "Please copy this file to your Architect server's 'iptree_multisrvcache' folder.");
            
            // Suggest deleting local cache
            File localCache = new File(plugin.getDataFolder().getParentFile().getParentFile(), "logs/iptree_cache");
            if (localCache.exists()) {
                 sender.sendMessage(ChatColor.YELLOW + "You can now delete the 'logs/iptree_cache' folder to free up space.");
            }

        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "Failed to export data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public List<LogDataEntry> parseLogDataFile(File file) {
        List<LogDataEntry> entries = new ArrayList<>();
        String currentSrvId = null;
        long fileSize = file.length();
        
        // Try to extract srvid from filename first as fallback
        String filename = file.getName();
        if (filename.startsWith("iptree_logdata_") && filename.endsWith(".yml")) {
            currentSrvId = filename.substring(15, filename.length() - 4);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            LogDataEntry currentEntry = null;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                if (line.startsWith("server_id:")) {
                    String val = getValue(line);
                    if (val != null && !val.isEmpty()) {
                        currentSrvId = val;
                    }
                } else if (line.startsWith("- uuid:")) {
                    if (currentEntry != null && currentSrvId != null) {
                        currentEntry.srvid = currentSrvId;
                        currentEntry.sourceFileSize = fileSize;
                        entries.add(currentEntry);
                    }
                    currentEntry = new LogDataEntry();
                    currentEntry.uuid = getValue(line);
                } else if (currentEntry != null) {
                    if (line.startsWith("ip:")) {
                        currentEntry.ip = getValue(line);
                    } else if (line.startsWith("ign:")) {
                        currentEntry.ign = getValue(line);
                    } else if (line.startsWith("join:")) {
                        currentEntry.joinTime = Long.parseLong(getValue(line));
                    } else if (line.startsWith("leave:")) {
                        currentEntry.exitTime = Long.parseLong(getValue(line));
                    }
                }
            }
            // Add last entry
            if (currentEntry != null && currentSrvId != null) {
                currentEntry.srvid = currentSrvId;
                currentEntry.sourceFileSize = fileSize;
                entries.add(currentEntry);
            }
            
        } catch (Exception e) {
            plugin.getLogger().severe("Error parsing file " + file.getName() + ": " + e.getMessage());
        }
        
        return entries;
    }

    private String getValue(String line) {
        int colonIndex = line.indexOf(':');
        if (colonIndex == -1) return null;
        String val = line.substring(colonIndex + 1).trim();
        if (val.startsWith("\"") && val.endsWith("\"")) {
            return val.substring(1, val.length() - 1);
        }
        return val;
    }

    public static class LogDataEntry {
        public String srvid;
        public String uuid;
        public String ip;
        public String ign;
        public long joinTime;
        public Long exitTime; // Can be null
        public long sourceFileSize;
    }
}