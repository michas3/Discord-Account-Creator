package com.lo.afkdummy;

import org.bukkit.scheduler.BukkitRunnable;

/**
 * Periodically asks {@link DummyManager} to re-inject any dummies that have
 * been silently evicted from the server's PlayerList.
 *
 * <p>Interval is driven by {@code keepalive-interval} in {@code config.yml}.
 */
public final class KeepAliveTask extends BukkitRunnable {

    private final DummyManager manager;

    public KeepAliveTask(DummyManager manager) {
        this.manager = manager;
    }

    @Override
    public void run() {
        manager.keepAlive();
    }

    /** Schedules (or re-schedules) the task at the configured tick interval. */
    public static KeepAliveTask schedule(AFKDummyPlugin plugin) {
        int interval = Math.max(40, plugin.getConfig().getInt("keepalive-interval", 200));
        KeepAliveTask task = new KeepAliveTask(plugin.getDummyManager());
        task.runTaskTimer(plugin, interval, interval);
        return task;
    }
}
