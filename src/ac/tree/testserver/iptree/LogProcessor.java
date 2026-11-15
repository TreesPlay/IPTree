package ac.tree.testserver.iptree;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.io.*;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class LogProcessor {

    private final IPTree plugin;
    private boolean isRunning = false;
    private static final Pattern JOIN_PATTERN = Pattern.compile("\\[(\\d{2}:\\d{2}:\\d{2})\\] \\[.*?\\]: (.*)\\[/(.*):(\\d+)\\] logged in with entity id \\d+ at .*");
    private static final Pattern UUID_PATTERN = Pattern.compile("\\[(\\d{2}:\\d{2}:\\d{2})\\] \\[.*?\\]: UUID of player (.*) is (.*)");
    private static final Pattern LEAVE_PATTERN = Pattern.compile("\\[(\\d{2}:\\d{2}:\\d{2})\\] \\[.*?\\]: (.*) lost connection: .*");
    private static final Pattern DATED_LOG_FILENAME_PATTERN = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(?:-.*)?\\.log(?:\\.gz)?$");
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

    public interface LogEntryHandler {
        void handle(UUID uuid, Timestamp joinTime, Timestamp exitTime, String ip, String ign);
    }

    public LogProcessor(IPTree plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void createDatabase(CommandSender sender) {
        String srvid = plugin.getConfigManager().getServerIdentifier();
        final boolean skipDirty = plugin.getConfigManager().isSkipDirtyData();
        final EnumMap<IpUtils.IPSkipReason, Integer> skipped = new EnumMap<>(IpUtils.IPSkipReason.class);

        processLogs(sender, (uuid, join, exit, ip, ign) -> {
            if (skipDirty) {
                IpUtils.IPSkipReason type = IpUtils.classify(ip);
                if (type != null) {
                    Integer cur = skipped.get(type);
                    skipped.put(type, cur == null ? 1 : (cur + 1));
                    return;
                }
            }
            plugin.getDatabaseManager().insertEntry(uuid, join, exit, ip, ign, srvid);
        });

        if (skipDirty) {
            for (IpUtils.IPSkipReason type : IpUtils.IPSkipReason.values()) {
                Integer count = skipped.get(type);
                if (count != null && count > 0) {
                    sender.sendMessage(ChatColor.YELLOW + IpUtils.formatSkippedMessage(type, count));
                }
            }
        }
    }

    public void processLogs(CommandSender sender, LogEntryHandler handler) {
        isRunning = true;
        File logsDir = new File(plugin.getDataFolder().getParentFile().getParentFile(), "logs");
        File tempDir = new File(logsDir, "iptree_cache");

        if (!logsDir.exists()) {
            sender.sendMessage(ChatColor.RED + "Logs directory not found.");
            isRunning = false;
            return;
        }

        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }

        File[] files = logsDir.listFiles();
        if (files == null) {
            sender.sendMessage(ChatColor.RED + "Could not list files in logs directory.");
            isRunning = false;
            return;
        }

        // Count valid dated .log.gz files for progress tracking
        int totalGzFiles = 0;
        for (File file : files) {
            if (isValidDatedLogFile(file) && file.getName().endsWith(".log.gz")) {
                totalGzFiles++;
            }
        }

        sender.sendMessage(ChatColor.YELLOW + "Unzipping " + totalGzFiles + " log files...");
        
        // 1. Unzip logs and sort them
        List<File> logFiles = new ArrayList<>();
        int unzippedCount = 0;

        for (File file : files) {
            if (!isValidDatedLogFile(file)) {
                continue;
            }

            if (file.getName().endsWith(".log.gz")) {
                try {
                    File unzipped = unzip(file, tempDir, sender);
                    logFiles.add(unzipped);
                    
                    unzippedCount++;
                    if (unzippedCount % 100 == 0) {
                        sender.sendMessage(ChatColor.YELLOW + "Unzipped " + unzippedCount + " out of " + totalGzFiles + " log files...");
                    }

                } catch (IOException e) {
                    if ("Insufficient disk space".equals(e.getMessage())) {
                        cleanup(tempDir);
                        isRunning = false;
                        return;
                    }
                    plugin.getLogger().warning("Failed to unzip " + file.getName());
                }
            } else if (file.getName().endsWith(".log")) {
                File gzipVersion = new File(logsDir, file.getName() + ".gz");
                if (!gzipVersion.exists()) {
                    logFiles.add(file);
                }
            }
        }
        
        // Sort files by name (date)
        Collections.sort(logFiles, Comparator.comparing(File::getName));

        sender.sendMessage(ChatColor.YELLOW + "Processing " + logFiles.size() + " log files...");

        int totalEntries = 0;
        int processedFiles = 0;
        int totalFiles = logFiles.size();
        
        for (File logFile : logFiles) {
            totalEntries += processLogFile(logFile, sender, handler);
            processedFiles++;
            
            if (processedFiles % 50 == 0) {
                sender.sendMessage(ChatColor.YELLOW + "Processed " + processedFiles + " out of " + totalFiles + " log files");
            }
        }

        cleanup(tempDir);

        sender.sendMessage(ChatColor.GREEN + "Log processing complete. Processed " + totalEntries + " entries.");
        isRunning = false;
    }

    private File unzip(File zipFile, File destDir, CommandSender sender) throws IOException {
        long uncompressedSize = getUncompressedSize(zipFile);
        long usableSpace = destDir.getUsableSpace();

        if (usableSpace < uncompressedSize) {
            long sizeMib = uncompressedSize / (1024 * 1024);
            long spaceMib = usableSpace / (1024 * 1024);
            sender.sendMessage(ChatColor.RED + "Unzipped file size of log " + zipFile.getName() + " is " + sizeMib + " MiB but only " + spaceMib + " MiB disk space is available. Process aborted.");
            throw new IOException("Insufficient disk space");
        }

        File destFile = new File(destDir, zipFile.getName().replace(".gz", ""));
        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(zipFile));
             FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
        return destFile;
    }

    private long getUncompressedSize(File file) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            long size = 0;
            int len;
            while ((len = gis.read(buffer)) > 0) {
                size += len;
            }
            return size;
        }
    }

    private void cleanup(File tempDir) {
        File[] tempFiles = tempDir.listFiles();
        if (tempFiles != null) {
            for (File file : tempFiles) {
                file.delete();
            }
        }
        tempDir.delete();
    }

    private boolean isValidDatedLogFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        Matcher matcher = DATED_LOG_FILENAME_PATTERN.matcher(file.getName());
        if (!matcher.matches()) {
            return false;
        }
        SimpleDateFormat dateOnlyFormat = new SimpleDateFormat("yyyy-MM-dd");
        dateOnlyFormat.setLenient(false);
        try {
            dateOnlyFormat.parse(matcher.group(1));
            return true;
        } catch (ParseException e) {
            return false;
        }
    }

    private int processLogFile(File logFile, CommandSender sender, LogEntryHandler handler) {
        if (!isValidDatedLogFile(logFile)) {
            return 0;
        }

        // Check for large files (approx > 200MB which is roughly > 2M lines)
        if (logFile.length() > 200 * 1024 * 1024) {
            sender.sendMessage(ChatColor.YELLOW + "Log file " + logFile.getName() + " is very large (>2M lines), processing may take some time");
        }

        int entries = 0;
        String dateStr = logFile.getName().substring(0, 10); // 2021-06-05
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        
        Map<String, String> uuidCache = new HashMap<>(); // IGN -> UUID
        Map<String, JoinEntry> activeJoins = new HashMap<>(); // IGN -> JoinEntry

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher uuidMatcher = UUID_PATTERN.matcher(line);
                if (uuidMatcher.find()) {
                    String rawUuid = uuidMatcher.group(3).trim();
                    try {
                        UUID.fromString(rawUuid);
                        uuidCache.put(uuidMatcher.group(2), rawUuid);
                    } catch (IllegalArgumentException ignored) {
                        // Ignore malformed UUID lines from custom/modded loggers
                    }
                    continue;
                }

                Matcher joinMatcher = JOIN_PATTERN.matcher(line);
                if (joinMatcher.find()) {
                    String timeStr = joinMatcher.group(1);
                    String ign = joinMatcher.group(2);
                    String ip = joinMatcher.group(3);
                    
                    if (uuidCache.containsKey(ign)) {
                        try {
                            Date date = dateFormat.parse(dateStr + " " + timeStr);
                            activeJoins.put(ign, new JoinEntry(uuidCache.get(ign), new Timestamp(date.getTime()), ip, ign));
                        } catch (ParseException e) {
                            e.printStackTrace();
                        }
                    }
                    continue;
                }

                Matcher leaveMatcher = LEAVE_PATTERN.matcher(line);
                if (leaveMatcher.find()) {
                    String timeStr = leaveMatcher.group(1);
                    String ign = leaveMatcher.group(2);
                    
                    if (activeJoins.containsKey(ign)) {
                        JoinEntry join = activeJoins.remove(ign);
                        try {
                            Date date = dateFormat.parse(dateStr + " " + timeStr);
                            Timestamp exitTime = new Timestamp(date.getTime());
                            
                            // Check JoinLeaveRelationHours
                            long diff = exitTime.getTime() - join.joinTime.getTime();
                            long maxDiff = plugin.getConfigManager().getJoinLeaveRelationHours() * 3600000L;
                            
                            if (diff <= maxDiff && diff >= 0) {
                                handler.handle(UUID.fromString(join.uuid), join.joinTime, exitTime, join.ip, join.ign);
                                entries++;
                            } else {
                                // Too long session or negative time (midnight cross?), treat as separate or ignore exit?
                                // Plan says: "The leave message only counts as "corresponding" when the time between the join time and the leave/exit time is JoinLeaveRelationHours or less."
                                // If not corresponding, we treat it as if no leave was found (step 8)
                                handleNoLeaveFound(join, handler);
                                entries++;
                            }
                        } catch (ParseException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            
            // Handle remaining active joins (no leave found)
            for (JoinEntry join : activeJoins.values()) {
                handleNoLeaveFound(join, handler);
                entries++;
            }

        } catch (IOException e) {
            plugin.getLogger().severe("Error reading log file " + logFile.getName());
        }
        
        return entries;
    }

    private void handleNoLeaveFound(JoinEntry join, LogEntryHandler handler) {
        // Plan Step 8: If no leave timestamp can be found, assume the player left after 30 minutes
        // Plan Step 9: Push the join entry to the SQL database (or handler)
        // The table definition allows NULL for exittime.
        handler.handle(UUID.fromString(join.uuid), join.joinTime, null, join.ip, join.ign);
    }

    private static class JoinEntry {
        String uuid;
        Timestamp joinTime;
        String ip;
        String ign;

        public JoinEntry(String uuid, Timestamp joinTime, String ip, String ign) {
            this.uuid = uuid;
            this.joinTime = joinTime;
            this.ip = ip;
            this.ign = ign;
        }
    }
}