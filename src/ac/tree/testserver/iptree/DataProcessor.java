package ac.tree.testserver.iptree;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DataProcessor {

    private static final long NULL_EXIT_ASSUMED_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private final IPTree plugin;

    public DataProcessor(IPTree plugin) {
        this.plugin = plugin;
    }

    public List<GroupData> getIPGroups(String ip) {
        List<GroupData> groups = new ArrayList<>();
        List<DatabaseManager.Session> sessions = plugin.getDatabaseManager().getSessions(ip);
        if (sessions.isEmpty()) return groups;

        int limitHours = plugin.getConfigManager().getAccountIPRelationHours();
        long limitMillis = TimeUnit.HOURS.toMillis(limitHours);

        List<Set<String>> clusters = new ArrayList<>();
        Set<String> currentCluster = new HashSet<>();
        Timestamp lastTime = null;

        for (DatabaseManager.Session session : sessions) {
            Timestamp effectiveTime = session.exitTime != null
                ? session.exitTime
                : new Timestamp(session.joinTime.getTime() + NULL_EXIT_ASSUMED_MILLIS);
            
            if (lastTime == null) {
                currentCluster.add(session.uuid);
                lastTime = effectiveTime;
            } else {
                long diff = session.joinTime.getTime() - lastTime.getTime();
                if (diff <= limitMillis) {
                    currentCluster.add(session.uuid);
                    // Update lastTime to the end of this session if it extends the cluster
                    if (effectiveTime.after(lastTime)) {
                        lastTime = effectiveTime;
                    }
                } else {
                    clusters.add(new HashSet<>(currentCluster));
                    currentCluster.clear();
                    currentCluster.add(session.uuid);
                    lastTime = effectiveTime;
                }
            }
        }
        if (!currentCluster.isEmpty()) {
            clusters.add(currentCluster);
        }

        for (Set<String> cluster : clusters) {
            groups.add(getGroup(cluster));
        }
        return groups;
    }

    public List<GroupData> getGroupsByName(String name) {
        return getGroupsByName(name, false);
    }

    public List<GroupData> getGroupsByName(String name, boolean joinClusters) {
        List<GroupData> groups = new ArrayList<>();
        List<String> uuids = plugin.getDatabaseManager().getUuidsByName(name);
        for (String uuid : uuids) {
            groups.add(getGroup(uuid, joinClusters));
        }
        return groups;
    }

    public GroupData getGroup(String uuid) {
        return getGroup(Collections.singleton(uuid));
    }

    public GroupData getGroup(String uuid, boolean joinClusters) {
        GroupData base = getGroup(uuid);
        return joinClusters ? expandGroupOneIpHop(base) : base;
    }

    /**
     * Expands a time-valid group by 1 unfiltered IP hop from the queried seed account(s)' direct IPs:
     * finds all accounts that ever used any IP in baseGroup.directIps (ignoring AccountIPRelationHours
     * on that single hop), then expands each newly discovered account's normal time-valid group
     * without chaining further unfiltered IP hops.
     */
    public GroupData expandGroupOneIpHop(GroupData baseGroup) {
        if (baseGroup.directIps.isEmpty()) {
            return baseGroup;
        }

        Set<String> allAccounts = new HashSet<>(baseGroup.accounts);
        Set<String> allIps = new HashSet<>(baseGroup.ips);

        Set<String> ipAccounts = plugin.getDatabaseManager().getAccountsByIPs(baseGroup.directIps);
        Queue<String> queue = new LinkedList<>();
        for (String uuid : ipAccounts) {
            if (allAccounts.add(uuid)) {
                queue.add(uuid);
            }
        }

        int limit = plugin.getConfigManager().getAccountIPRelationHours();

        while (!queue.isEmpty()) {
            String currentUuid = queue.poll();

            List<String> userIps = plugin.getDatabaseManager().getRelatedIPs(currentUuid);
            allIps.addAll(userIps);

            Set<String> related = plugin.getDatabaseManager().getRelatedAccounts(currentUuid, limit);
            for (String neighbor : related) {
                if (allAccounts.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        return new GroupData(allAccounts, allIps, baseGroup.directIps);
    }

    public GroupData getGroup(Set<String> seedUUIDs) {
        Set<String> accounts = new HashSet<>(seedUUIDs);
        Set<String> ips = new HashSet<>();
        Set<String> directIps = new HashSet<>();
        Queue<String> queue = new LinkedList<>(seedUUIDs);

        int limit = plugin.getConfigManager().getAccountIPRelationHours();

        while (!queue.isEmpty()) {
            String currentUuid = queue.poll();

            // Get all IPs for this user to populate the IP list
            List<String> userIps = plugin.getDatabaseManager().getRelatedIPs(currentUuid);
            ips.addAll(userIps);
            if (seedUUIDs.contains(currentUuid)) {
                directIps.addAll(userIps);
            }

            // Find related accounts via shared IPs with time constraint
            Set<String> related = plugin.getDatabaseManager().getRelatedAccounts(currentUuid, limit);
            for (String neighbor : related) {
                if (!accounts.contains(neighbor)) {
                    accounts.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        return new GroupData(accounts, ips, directIps);
    }

    public static class GroupData {
        public final Set<String> accounts;
        public final Set<String> ips;
        public final Set<String> directIps;

        public GroupData(Set<String> accounts, Set<String> ips) {
            this(accounts, ips, Collections.emptySet());
        }

        public GroupData(Set<String> accounts, Set<String> ips, Set<String> directIps) {
            this.accounts = accounts;
            this.ips = ips;
            this.directIps = directIps;
        }
    }
}