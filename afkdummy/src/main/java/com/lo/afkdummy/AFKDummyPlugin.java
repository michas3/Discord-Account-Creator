package com.lo.afkdummy;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for the AFK Dummy plugin.
 *
 * <p>Boots {@link DummyManager}, restores saved dummies from disk,
 * starts the {@link KeepAliveTask}, and wires up the command handler.
 * On disable, all live dummies are removed cleanly so the server's
 * PlayerList and world entity storage are left in a consistent state.
 */
public final class AFKDummyPlugin extends JavaPlugin {

    private static AFKDummyPlugin INSTANCE;

    private DummyManager  dummyManager;
    private KeepAliveTask keepAliveTask;

    @Override
    public void onEnable() {
        INSTANCE = this;

        saveDefaultConfig();

        dummyManager = new DummyManager(this);
        dummyManager.loadAndSpawn();

        keepAliveTask = KeepAliveTask.schedule(this);

        DummyCommand handler = new DummyCommand(this);
        var cmd = getCommand("afkdummy");
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        getLogger().info("AFK Dummy enabled — " + dummyManager.getAll().size() + " dummy(s) active.");
    }

    @Override
    public void onDisable() {
        if (keepAliveTask != null) keepAliveTask.cancel();
        if (dummyManager  != null) dummyManager.removeAll();
        getLogger().info("AFK Dummy disabled — all dummies removed.");
    }

    // -----------------------------------------------------------------------

    public DummyManager getDummyManager() { return dummyManager; }

    public static AFKDummyPlugin getInstance() { return INSTANCE; }
}
