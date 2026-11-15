package ac.tree.testserver.iptree;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerEventListener implements Listener {

    private final IPTree plugin;
    private final Map<UUID, ActiveSession> activeSessions = new ConcurrentHashMap<>();

    public PlayerEventListener(IPTree plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.isSleepMode()) return;

        InetSocketAddress socketAddress = event.getPlayer().getAddress();
        if (socketAddress == null || socketAddress.getAddress() == null) {
            return;
        }

        UUID uuid = event.getPlayer().getUniqueId();
        String ip = socketAddress.getAddress().getHostAddress();
        String ign = event.getPlayer().getName();
        String srvid = plugin.getConfigManager().getServerIdentifier();

        // Check for dirty IP
        if (plugin.getConfigManager().isSkipDirtyData()) {
            if (IpUtils.classify(ip) != null) {
                return;
            }
        }

        Timestamp joinTime = new Timestamp(System.currentTimeMillis());
        ActiveSession session = new ActiveSession(joinTime);

        // Store session state in memory
        activeSessions.put(uuid, session);

        // Insert initial entry with null exit time, then apply any exit that arrived before insert finished
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getDatabaseManager().insertEntry(uuid, session.joinTime, null, ip, ign, srvid);

            Timestamp deferredExit;
            synchronized (session) {
                session.insertCompleted = true;
                deferredExit = session.pendingExitTime;
            }

            if (deferredExit != null) {
                plugin.getDatabaseManager().updateExitTime(uuid, session.joinTime, deferredExit);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.isSleepMode()) return;

        UUID uuid = event.getPlayer().getUniqueId();
        ActiveSession session = activeSessions.remove(uuid);

        if (session != null) {
            Timestamp exitTime = new Timestamp(System.currentTimeMillis());
            boolean alreadyInserted;

            synchronized (session) {
                alreadyInserted = session.insertCompleted;
                if (!alreadyInserted) {
                    session.pendingExitTime = exitTime;
                }
            }

            if (alreadyInserted) {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    plugin.getDatabaseManager().updateExitTime(uuid, session.joinTime, exitTime);
                });
            }
        }
    }

    private static class ActiveSession {
        final Timestamp joinTime;
        boolean insertCompleted = false;
        Timestamp pendingExitTime = null;

        ActiveSession(Timestamp joinTime) {
            this.joinTime = joinTime;
        }
    }
}