package ac.tree.testserver.iptree;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import ac.tree.testserver.iptree.IpUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CommandManager implements TabExecutor {

    private static final String GET_USAGE = "/iptree get <uuid/username/ip> [joinclusters (true/false)]";
    private static final String MASSGET_USAGE = "/iptree massget <joinclusters (true/false)> <uuid1/username1/ip1,uuidN/usernameN/ipN>";
    private static final String RECENTGET_USAGE = "/iptree recentget <num> [joinclusters (true/false)]";

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "preparemultiserver", "db", "cfg", "reload", "get", "massget", "recentget"
    );
    private static final List<String> DB_SUBCOMMANDS = Arrays.asList("create", "stats", "conflicts");
    private static final List<String> BOOLEAN_OPTIONS = Arrays.asList("true", "false");

    private final IPTree plugin;
    private final LogProcessor logProcessor;
    private final MultiServerManager multiServerManager;

    public CommandManager(IPTree plugin) {
        this.plugin = plugin;
        this.logProcessor = new LogProcessor(plugin);
        this.multiServerManager = new MultiServerManager(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (plugin.isSleepMode()) {
            sender.sendMessage(ChatColor.RED + "IPTree is in Sleep Mode due to database connection failure.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "db":
                handleDbCommand(sender, args);
                break;
            case "get":
                handleGetCommand(sender, args);
                break;
            case "massget":
                handleMassGetCommand(sender, args);
                break;
            case "recentget":
                handleRecentGetCommand(sender, args);
                break;
            case "cfg":
                handleCfgCommand(sender, args);
                break;
            case "reload":
                handleReloadCommand(sender);
                break;
            case "preparemultiserver":
                handlePrepareMultiServerCommand(sender);
                break;
            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (plugin.isSleepMode() || !sender.hasPermission("iptree.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterByPrefix(SUBCOMMANDS, args[0]);
        }

        String subCommand = args[0].toLowerCase();

        if (args.length == 2) {
            switch (subCommand) {
                case "db":
                    return filterByPrefix(DB_SUBCOMMANDS, args[1]);
                case "cfg":
                    return filterByPrefix(plugin.getConfigManager().getAccessibleConfigKeys(), args[1]);
                case "massget":
                    return filterByPrefix(BOOLEAN_OPTIONS, args[1]);
                default:
                    return Collections.emptyList();
            }
        }

        if (args.length == 3) {
            switch (subCommand) {
                case "get":
                case "recentget":
                    return filterByPrefix(BOOLEAN_OPTIONS, args[2]);
                case "cfg":
                    if (plugin.getConfigManager().isModifiableBooleanOption(args[1])) {
                        return filterByPrefix(BOOLEAN_OPTIONS, args[2]);
                    }
                    return Collections.emptyList();
                default:
                    return Collections.emptyList();
            }
        }

        return Collections.emptyList();
    }

    private List<String> filterByPrefix(List<String> candidates, String prefix) {
        String lowerPrefix = prefix.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase().startsWith(lowerPrefix)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private void handlePrepareMultiServerCommand(CommandSender sender) {
        if (!plugin.getConfigManager().isMultiServer()) {
            sender.sendMessage(ChatColor.RED + "MultiServer is not enabled in config.");
            return;
        }
        
        if (logProcessor.isRunning()) {
            sender.sendMessage(ChatColor.RED + "A process is already running.");
            return;
        }

        sender.sendMessage(ChatColor.GREEN + "Starting MultiServer preparation...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            multiServerManager.exportLogData(sender);
        });
    }

    private void handleDbCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }

        String dbAction = args[1].toLowerCase();

        switch (dbAction) {
            case "create":
                handleDbCreate(sender);
                break;
            case "stats":
                sender.sendMessage(ChatColor.GREEN + "--- IPTree Database Stats ---");
                sender.sendMessage(ChatColor.GOLD + "Entries: " + ChatColor.YELLOW + plugin.getDatabaseManager().getEntryCount());
                sender.sendMessage(ChatColor.GOLD + "Unique IPs: " + ChatColor.YELLOW + plugin.getDatabaseManager().getUniqueIPCount());
                sender.sendMessage(ChatColor.GOLD + "Unique Accounts: " + ChatColor.YELLOW + plugin.getDatabaseManager().getUniqueAccountCount());
                break;
            case "conflicts":
                sender.sendMessage(ChatColor.GREEN + "--- IPTree Conflicts ---");
                sender.sendMessage(ChatColor.YELLOW + "Listing entries with no exit time (potential crashes/timeouts):");
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    java.util.List<String> conflicts = plugin.getDatabaseManager().getConflicts();
                    if (conflicts.isEmpty()) {
                        sender.sendMessage(ChatColor.GREEN + "No conflicts found.");
                    } else {
                        for (String conflict : conflicts) {
                            sender.sendMessage(ChatColor.GREEN + conflict);
                        }
                        sender.sendMessage(ChatColor.YELLOW + "Total conflicts: " + conflicts.size());
                    }
                });
                break;
            default:
                sender.sendMessage(ChatColor.RED + "Unknown db action: " + dbAction);
                break;
        }
    }

    private Boolean parseStrictBoolean(String input) {
        if ("true".equalsIgnoreCase(input)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(input)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private java.util.List<DataProcessor.GroupData> resolveTargetGroups(String target, boolean joinClusters) {
        java.util.List<DataProcessor.GroupData> groups;
        if (IpUtils.isIpAddress(target)) {
            groups = plugin.getDataProcessor().getIPGroups(target);
        } else if (target.length() == 36) {
            // UUID
            groups = new java.util.ArrayList<>();
            groups.add(plugin.getDataProcessor().getGroup(target, joinClusters));
        } else {
            // Username
            groups = plugin.getDataProcessor().getGroupsByName(target, joinClusters);
        }
        return mergeGroupsIfRequested(groups, joinClusters);
    }

    private java.util.List<DataProcessor.GroupData> mergeGroupsIfRequested(java.util.List<DataProcessor.GroupData> groups, boolean joinClusters) {
        if (joinClusters && groups.size() > 1) {
            java.util.Set<String> allAccounts = new java.util.HashSet<>();
            java.util.Set<String> allIps = new java.util.HashSet<>();
            for (DataProcessor.GroupData group : groups) {
                allAccounts.addAll(group.accounts);
                allIps.addAll(group.ips);
            }
            java.util.List<DataProcessor.GroupData> merged = new java.util.ArrayList<>();
            merged.add(new DataProcessor.GroupData(allAccounts, allIps));
            return merged;
        }
        return groups;
    }

    private void sendGroupLookupOutput(CommandSender sender, String targetLabel, java.util.List<DataProcessor.GroupData> groups, boolean isPlayerLookup, boolean joinClusters) {
        sender.sendMessage(ChatColor.GREEN + "--- IPTree Lookup: " + ChatColor.GOLD + targetLabel + ChatColor.GREEN + " ---");

        if (groups.size() > 1) {
            sender.sendMessage(ChatColor.YELLOW + "Found " + groups.size() + " disjoint groups (use 'true' to merge).");
        } else if (isPlayerLookup && !joinClusters && groups.size() == 1) {
            DataProcessor.GroupData singleGroup = groups.get(0);
            if (!singleGroup.directIps.isEmpty()) {
                java.util.Set<String> extraAccounts = plugin.getDatabaseManager().getAccountsByIPs(singleGroup.directIps);
                extraAccounts.removeAll(singleGroup.accounts);
                if (!extraAccounts.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "Found " + extraAccounts.size() + " other account(s) sharing this player's IPs outside the time window.");
                }
            }
        }

        int groupIndex = 1;
        for (DataProcessor.GroupData group : groups) {
            if (groups.size() > 1) {
                sender.sendMessage(ChatColor.AQUA + "--- Group " + groupIndex++ + " ---");
            }

            DatabaseManager.TargetStats stats = plugin.getDatabaseManager().getClusterStats(group.accounts, group.ips);
            if (stats != null) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
                String dateA = sdf.format(stats.firstSeen);
                String dateB = sdf.format(stats.lastSeen);
                sender.sendMessage(ChatColor.GREEN + "Seen between " +
                    ChatColor.GOLD + dateA + ChatColor.GREEN + " and " +
                    ChatColor.GOLD + dateB + ChatColor.GREEN + " in " +
                    ChatColor.GOLD + stats.entryCount + ChatColor.GREEN + " log entries.");
            }

            sender.sendMessage(ChatColor.GOLD + "Related Accounts (" + ChatColor.YELLOW + group.accounts.size() + ChatColor.GOLD + "):");

            for (String accUuid : group.accounts) {
                String name = plugin.getDatabaseManager().getLastKnownName(accUuid);

                if (sender instanceof Player) {
                    TextComponent message = new TextComponent("- ");
                    message.setColor(net.md_5.bungee.api.ChatColor.YELLOW);

                    TextComponent nameComponent = new TextComponent(name);
                    nameComponent.setColor(net.md_5.bungee.api.ChatColor.GREEN);
                    nameComponent.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, name));
                    nameComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to get Username").create()));

                    TextComponent uuidComponent = new TextComponent(" (" + accUuid + ")");
                    uuidComponent.setColor(net.md_5.bungee.api.ChatColor.DARK_GREEN);
                    uuidComponent.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, accUuid));
                    uuidComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to get UUID").create()));

                    message.addExtra(nameComponent);
                    message.addExtra(uuidComponent);

                    ((Player) sender).spigot().sendMessage(message);
                } else {
                    sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.GREEN + name + ChatColor.DARK_GREEN + " (" + ChatColor.YELLOW + accUuid + ChatColor.GOLD + ")");
                }
            }

            sender.sendMessage(ChatColor.GOLD + "Related IPs (" + ChatColor.YELLOW + group.ips.size() + ChatColor.GOLD + "):");
            sendIpList(sender, new java.util.ArrayList<>(group.ips));
        }
    }

    private void handleGetCommand(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(ChatColor.RED + "Usage: " + GET_USAGE);
            return;
        }

        final String target = args[1];
        final boolean joinClusters;
        if (args.length == 3) {
            Boolean parsed = parseStrictBoolean(args[2]);
            if (parsed == null) {
                sender.sendMessage(ChatColor.RED + "Usage: " + GET_USAGE);
                return;
            }
            joinClusters = parsed;
        } else {
            joinClusters = false;
        }

        final boolean isPlayerLookup = !IpUtils.isIpAddress(target);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            java.util.List<DataProcessor.GroupData> groups = resolveTargetGroups(target, joinClusters);

            if (groups.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No data found for " + target);
                return;
            }

            sendGroupLookupOutput(sender, target, groups, isPlayerLookup, joinClusters);
        });
    }

    private void handleMassGetCommand(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(ChatColor.RED + "Usage: " + MASSGET_USAGE);
            return;
        }

        Boolean parsedJoinClusters = parseStrictBoolean(args[1]);
        if (parsedJoinClusters == null) {
            sender.sendMessage(ChatColor.RED + "Usage: " + MASSGET_USAGE);
            return;
        }

        final boolean joinClusters = parsedJoinClusters;
        final String[] targets = args[2].split(",");

        sender.sendMessage(ChatColor.YELLOW + "Starting sequential lookup for " + targets.length + " targets...");

        // Fire ONE asynchronous task. The loop runs inside this single background thread.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            for (String target : targets) {
                if (target.isEmpty()) continue;

                boolean isPlayerLookup = !IpUtils.isIpAddress(target);
                java.util.List<DataProcessor.GroupData> groups = resolveTargetGroups(target, joinClusters);
                if (groups.isEmpty()) {
                    continue;
                }

                sendGroupLookupOutput(sender, target, groups, isPlayerLookup, joinClusters);
            }
            sender.sendMessage(ChatColor.YELLOW + "Mass lookup complete.");
        });
    }

    private void handleRecentGetCommand(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(ChatColor.RED + "Usage: " + RECENTGET_USAGE);
            return;
        }

        final int num;
        try {
            num = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Usage: " + RECENTGET_USAGE);
            return;
        }

        if (num <= 0) {
            sender.sendMessage(ChatColor.RED + "Usage: " + RECENTGET_USAGE);
            return;
        }

        final boolean joinClusters;
        if (args.length == 3) {
            Boolean parsed = parseStrictBoolean(args[2]);
            if (parsed == null) {
                sender.sendMessage(ChatColor.RED + "Usage: " + RECENTGET_USAGE);
                return;
            }
            joinClusters = parsed;
        } else {
            joinClusters = false;
        }

        sender.sendMessage(ChatColor.YELLOW + "Starting lookup for the " + num + " most recent players...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            java.util.List<String> recentUuids = plugin.getDatabaseManager().getRecentPlayers(num);
            if (recentUuids.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No player data found in database.");
                return;
            }

            java.util.Set<String> shownUuids = new java.util.HashSet<>();
            java.util.List<String> displayNames = new java.util.ArrayList<>();
            java.util.List<java.util.List<DataProcessor.GroupData>> resolvedGroups = new java.util.ArrayList<>();

            for (String uuid : recentUuids) {
                if (shownUuids.contains(uuid)) {
                    continue;
                }

                java.util.List<DataProcessor.GroupData> groups = new java.util.ArrayList<>();
                groups.add(plugin.getDataProcessor().getGroup(uuid, joinClusters));

                for (DataProcessor.GroupData group : groups) {
                    shownUuids.addAll(group.accounts);
                }

                displayNames.add(plugin.getDatabaseManager().getLastKnownName(uuid));
                resolvedGroups.add(groups);
            }

            // Output in reverse order so the most recent player is printed last (bottom of console/chat)
            for (int i = resolvedGroups.size() - 1; i >= 0; i--) {
                sendGroupLookupOutput(sender, displayNames.get(i), resolvedGroups.get(i), true, joinClusters);
            }

            sender.sendMessage(ChatColor.YELLOW + "Recent lookup complete.");
        });
    }

    private void sendIpList(CommandSender sender, java.util.List<String> ips) {
        String mode = plugin.getConfigManager().getIpDisplayMode().toLowerCase();

        if (mode.equals("raw")) {
            if (sender instanceof Player) {
                TextComponent message = new TextComponent("");
                int currentLength = 0;
                final int MAX_CHARS = 32000;

                for (int i = 0; i < ips.size(); i++) {
                    String ip = ips.get(i);
                    
                    // Prepare IP Component
                    TextComponent ipComponent = new TextComponent(ip);
                    ipComponent.setColor(net.md_5.bungee.api.ChatColor.GREEN);
                    ipComponent.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, ip));
                    ipComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to get IP").create()));
                    
                    // Prepare Separator
                    TextComponent separator = null;
                    int separatorLen = 0;
                    if (i < ips.size() - 1) {
                        separator = new TextComponent(", ");
                        separator.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
                        separatorLen = 2;
                    }

                    // Check for overflow
                    if (currentLength + ip.length() + separatorLen > MAX_CHARS) {
                        ((Player) sender).spigot().sendMessage(message);
                        message = new TextComponent("");
                        currentLength = 0;
                    }

                    // Add to buffer
                    message.addExtra(ipComponent);
                    if (separator != null) {
                        message.addExtra(separator);
                    }
                    currentLength += ip.length() + separatorLen;
                }

                // Send remaining buffer
                if (currentLength > 0) {
                    ((Player) sender).spigot().sendMessage(message);
                }

            } else {
                // Console logic (String builder)
                StringBuilder sb = new StringBuilder();
                for (String ip : ips) {
                    sb.append(ip).append(" ");
                }
                sender.sendMessage(ChatColor.GREEN + sb.toString().trim());
            }
        } else if (mode.equals("bundle")) {
            java.util.List<String> ipv4 = new java.util.ArrayList<>();
            java.util.List<String> ipv6 = new java.util.ArrayList<>();
            
            for (String ip : ips) {
                if (ip.contains(":")) ipv6.add(ip);
                else ipv4.add(ip);
            }

            sendBundledIps(sender, ipv4, 4);
            sendBundledIps(sender, ipv6, 2);
        } else {
            // Default "single"
            for (String ip : ips) {
                if (sender instanceof Player) {
                    TextComponent message = new TextComponent(" - ");
                    message.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
                    
                    TextComponent ipComponent = new TextComponent(ip);
                    ipComponent.setColor(net.md_5.bungee.api.ChatColor.GREEN);
                    ipComponent.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, ip));
                    ipComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to get IP").create()));
                    
                    message.addExtra(ipComponent);
                    ((Player) sender).spigot().sendMessage(message);
                } else {
                    sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.GREEN + ip);
                }
            }
        }
    }

    private void sendBundledIps(CommandSender sender, java.util.List<String> ips, int bundleSize) {
        if (ips.isEmpty()) return;
        
        if (sender instanceof Player) {
            TextComponent message = new TextComponent("");
            for (int i = 0; i < ips.size(); i++) {
                String ip = ips.get(i);
                TextComponent ipComponent = new TextComponent(ip);
                ipComponent.setColor(net.md_5.bungee.api.ChatColor.GREEN);
                ipComponent.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, ip));
                ipComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to get IP").create()));
                
                message.addExtra(ipComponent);
                
                if ((i + 1) % bundleSize == 0 || i == ips.size() - 1) {
                    ((Player) sender).spigot().sendMessage(message);
                    message = new TextComponent("");
                } else {
                    TextComponent separator = new TextComponent(" ");
                    separator.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
                    message.addExtra(separator);
                }
            }
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ips.size(); i++) {
                sb.append(ips.get(i));
                if ((i + 1) % bundleSize == 0 || i == ips.size() - 1) {
                    sender.sendMessage(ChatColor.GREEN + sb.toString());
                    sb = new StringBuilder();
                } else {
                    sb.append(" ");
                }
            }
        }
    }

    private void handleCfgCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /iptree cfg <config-option-name> [value]");
            return;
        }

        String option = args[1];

        if (args.length == 2) {
            // Get value
            String value = plugin.getConfigManager().getConfigOption(option);
            if (value == null) {
                sender.sendMessage(ChatColor.RED + "Option not found or access denied (SQL options cannot be accessed).");
            } else {
                sender.sendMessage(ChatColor.GREEN + option + ": " + ChatColor.YELLOW + value);
            }
        } else {
            // Set value
            String value = args[2];
            boolean success = plugin.getConfigManager().setConfigOption(option, value);
            if (success) {
                sender.sendMessage(ChatColor.GREEN + "Configuration updated: " + option + " = " + value);
            } else {
                sender.sendMessage(ChatColor.RED + "Failed to update configuration. Check if the option exists, is not an SQL option, and the value format is correct.");
            }
        }
    }

    private void handleReloadCommand(CommandSender sender) {
        plugin.getConfigManager().loadConfig();
        sender.sendMessage(ChatColor.GREEN + "Configuration reloaded from file.");
    }

    private void handleDbCreate(CommandSender sender) {
        if (logProcessor.isRunning()) {
            sender.sendMessage(ChatColor.RED + "A process is already running.");
            return;
        }

        ConfigManager config = plugin.getConfigManager();
        final boolean multiServer = config.isMultiServer();

        if (multiServer) {
            String role = config.getMultiServerRole();
            if ("contributor".equalsIgnoreCase(role)) {
                sender.sendMessage(ChatColor.RED + "This server is set as a Contributor. You cannot create the database here.");
                sender.sendMessage(ChatColor.RED + "Please run /iptree preparemultiserver and transfer the data to the Architect server.");
                return;
            }

            if (!"architect".equalsIgnoreCase(role)) {
                sender.sendMessage(ChatColor.RED + "MultiServerRole is not set to 'architect' (or 'contributor'). Please configure it.");
                return;
            }
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Boolean empty = plugin.getDatabaseManager().isDatabaseEmpty();
            if (empty == null) {
                sender.sendMessage(ChatColor.RED + "Failed to check database state. Process aborted.");
                return;
            }
            if (!empty) {
                sender.sendMessage(ChatColor.RED + "The database is not empty. Cannot run '/iptree db create' on a non-empty database.");
                return;
            }

            if (!multiServer) {
                sender.sendMessage(ChatColor.GREEN + "Starting database creation process (Single Server Mode)...");
                logProcessor.createDatabase(sender);
            } else {
                sender.sendMessage(ChatColor.GREEN + "Starting database creation process (MultiServer Architect Mode)...");
                processMultiServerData(sender);
            }
        });
    }

    private void processMultiServerData(CommandSender sender) {
        java.io.File cacheDir = new java.io.File(new java.io.File(plugin.getDataFolder().getParentFile().getParentFile(), "logs"), "iptree_multisrvcache");
        if (!cacheDir.exists() || cacheDir.listFiles() == null) {
            sender.sendMessage(ChatColor.RED + "No 'iptree_multisrvcache' directory found or it is empty.");
            return;
        }

        // Check for local data
        String localSrvId = plugin.getConfigManager().getServerIdentifier();
        java.io.File localFile = new java.io.File(cacheDir, "iptree_logdata_" + localSrvId + ".yml");
        if (!localFile.exists()) {
            sender.sendMessage(ChatColor.YELLOW + "Local server data not found in cache. Generating it now...");
            multiServerManager.exportLogData(sender);
        }

        java.io.File[] files = cacheDir.listFiles((dir, name) ->
            name.startsWith("iptree_logdata_") && name.endsWith(".yml") && new java.io.File(dir, name).isFile()
        );
        if (files == null || files.length == 0) {
            sender.sendMessage(ChatColor.RED + "No data files found in cache.");
            return;
        }

        sender.sendMessage(ChatColor.YELLOW + "Loading data from " + files.length + " servers...");
        
        java.util.List<MultiServerManager.LogDataEntry> allEntries = new java.util.ArrayList<>();
        for (java.io.File file : files) {
            java.util.List<MultiServerManager.LogDataEntry> entries = multiServerManager.parseLogDataFile(file);
            allEntries.addAll(entries);
            sender.sendMessage(ChatColor.YELLOW + "Loaded " + entries.size() + " entries from " + file.getName());
        }

        sender.sendMessage(ChatColor.YELLOW + "Deduplicating " + allEntries.size() + " entries...");

        // Deduplication Logic
        java.util.Map<String, java.util.List<MultiServerManager.LogDataEntry>> groups = new java.util.HashMap<>();
        int dedupCount = 0;
        int totalDedup = allEntries.size();

        for (MultiServerManager.LogDataEntry entry : allEntries) {
            String key = entry.uuid + "|" + entry.joinTime + "|" + entry.ip + "|" + entry.ign;
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(entry);
            
            dedupCount++;
            if (dedupCount % 50000 == 0) {
                sender.sendMessage(ChatColor.YELLOW + "Deduplicated " + dedupCount + " out of " + totalDedup + " entries");
            }
        }

        final boolean skipDirty = plugin.getConfigManager().isSkipDirtyData();
        final java.util.EnumMap<IpUtils.IPSkipReason, Integer> skipped = new java.util.EnumMap<>(IpUtils.IPSkipReason.class);

        int sorted = 0;
        int inserted = 0;
        int totalEntries = groups.size();
        DatabaseManager db = plugin.getDatabaseManager();

        // Collect final deduplicated entries so they can be inserted in chronological order
        java.util.List<MultiServerManager.LogDataEntry> entriesToInsert = new java.util.ArrayList<>();

        sender.sendMessage(ChatColor.YELLOW + "Sorting " + totalEntries + " entries by date...");

        for (java.util.List<MultiServerManager.LogDataEntry> group : groups.values()) {
            if (group.size() == 1) {
                MultiServerManager.LogDataEntry e = group.get(0);
                entriesToInsert.add(e);

            } else {
                // Handle conflicts
                java.util.Set<Long> distinctExits = new java.util.HashSet<>();
                boolean hasNull = false;
                java.util.List<MultiServerManager.LogDataEntry> nullEntries = new java.util.ArrayList<>();

                for (MultiServerManager.LogDataEntry e : group) {
                    if (e.exitTime == null) {
                        hasNull = true;
                        nullEntries.add(e);
                    } else {
                        distinctExits.add(e.exitTime);
                    }
                }

                // Add distinct non-null exits
                for (Long exit : distinctExits) {
                    MultiServerManager.LogDataEntry rep = group.stream()
                        .filter(e -> e.exitTime != null && e.exitTime.equals(exit))
                        .findFirst().orElse(null);
                    if (rep != null) {
                        entriesToInsert.add(rep);
                    }
                }

                // Add one null exit if present (using largest file source)
                if (hasNull && !nullEntries.isEmpty()) {
                    MultiServerManager.LogDataEntry best = nullEntries.stream()
                        .max(java.util.Comparator.comparingLong(e -> e.sourceFileSize))
                        .orElse(nullEntries.get(0));

                    if (best != null) {
                        entriesToInsert.add(best);
                    }
                }
            }

            sorted++;
            if (sorted % 125000 == 0) {
                double percent = ((double) sorted / totalEntries) * 100;
                String percentStr = String.format("%.3f", percent);
                sender.sendMessage(ChatColor.YELLOW + "Sorted " + sorted + " entries by date");
            }
        }

        sender.sendMessage(ChatColor.YELLOW + "Writing " + totalEntries + " entries to the database...");

        // Ensure deterministic insert order: oldest jointime first
        entriesToInsert.sort(java.util.Comparator.comparingLong(e -> e.joinTime));

        // Insert entries in chronological order
        for (MultiServerManager.LogDataEntry e : entriesToInsert) {
            if (skipDirty) {
                IpUtils.IPSkipReason type = IpUtils.classify(e.ip);
                if (type != null) {
                    Integer cur = skipped.get(type);
                    skipped.put(type, cur == null ? 1 : (cur + 1));
                    continue;
                }
            }

            db.insertEntry(java.util.UUID.fromString(e.uuid), new java.sql.Timestamp(e.joinTime),
                e.exitTime != null ? new java.sql.Timestamp(e.exitTime) : null, e.ip, e.ign, e.srvid);
            inserted++;
            if (inserted % 5000 == 0) {
                double percent = ((double) inserted / entriesToInsert.size()) * 100;
                String percentStr = String.format("%.3f", percent);
                sender.sendMessage(ChatColor.YELLOW + "Added " + inserted + " entries to the database (" + percentStr + "%)");
            }
        }

        sender.sendMessage(ChatColor.GREEN + "Database creation complete. Inserted " + inserted + " unique entries.");

        if (skipDirty) {
            for (IpUtils.IPSkipReason type : IpUtils.IPSkipReason.values()) {
                Integer count = skipped.get(type);
                if (count != null && count > 0) {
                    sender.sendMessage(ChatColor.YELLOW + IpUtils.formatSkippedMessage(type, count));
                }
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GREEN + "================ " + ChatColor.GREEN + "IPTree Help" + ChatColor.DARK_GREEN + " ================");
        sender.sendMessage(ChatColor.GOLD + "/iptree preparemultiserver");
        sender.sendMessage(ChatColor.YELLOW + "  Exports local logs for MultiServer setup.");
        sender.sendMessage(ChatColor.GOLD + "/iptree db create");
        sender.sendMessage(ChatColor.YELLOW + "  Parses all server logs and populates the database.");
        sender.sendMessage(ChatColor.GOLD + "/iptree db stats");
        sender.sendMessage(ChatColor.YELLOW + "  Displays database statistics (entries, unique IPs, accounts).");
        sender.sendMessage(ChatColor.GOLD + "/iptree db conflicts");
        sender.sendMessage(ChatColor.YELLOW + "  Lists sessions where no exit time was found.");
        sender.sendMessage(ChatColor.GOLD + "/iptree cfg <config-option-name> [value]");
        sender.sendMessage(ChatColor.YELLOW + "  Get or set a configuration option.");
        sender.sendMessage(ChatColor.GOLD + "/iptree reload");
        sender.sendMessage(ChatColor.YELLOW + "  Reloads the configuration from file.");
        sender.sendMessage(ChatColor.GOLD + GET_USAGE);
        sender.sendMessage(ChatColor.YELLOW + "  Look up a player or IP. Set joinclusters to true to merge disjoint groups.");
        sender.sendMessage(ChatColor.GOLD + MASSGET_USAGE);
        sender.sendMessage(ChatColor.YELLOW + "  Check multiple players or IPs at once. Also avoids 'No data for...' messages.");
        sender.sendMessage(ChatColor.GOLD + RECENTGET_USAGE);
        sender.sendMessage(ChatColor.YELLOW + "  Look up the players who last played on the server.");
        sender.sendMessage(ChatColor.DARK_GREEN + "==========================================");
    }
}