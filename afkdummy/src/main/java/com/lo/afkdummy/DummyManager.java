package com.lo.afkdummy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Owns the live registry of {@link FakePlayer} instances and all
 * persistence to/from {@code dummies.yml}.
 */
public final class DummyManager {

    private static final String FILE_NAME = "dummies.yml";

    private final AFKDummyPlugin               plugin;
    private final Logger                        log;
    private final File                          storageFile;
    private final Map<UUID, FakePlayer>         byUUID = new ConcurrentHashMap<>();
    private final Map<String, FakePlayer>       byName = new ConcurrentHashMap<>();

    public DummyManager(AFKDummyPlugin plugin) {
        this.plugin      = plugin;
        this.log         = plugin.getLogger();
        this.storageFile = new File(plugin.getDataFolder(), FILE_NAME);
    }

    // -----------------------------------------------------------------------
    // Plugin lifecycle
    // -----------------------------------------------------------------------

    /** Reads {@code dummies.yml} and re-spawns every saved dummy. */
    public void loadAndSpawn() {
        if (!storageFile.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection root = cfg.getConfigurationSection("dummies");
        if (root == null) return;

        String texture   = plugin.getConfig().getString("default-skin.texture",   "");
        String signature = plugin.getConfig().getString("default-skin.signature", "");

        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;

            try {
                UUID   uuid  = UUID.fromString(Objects.requireNonNull(sec.getString("uuid")));
                String name  = Objects.requireNonNull(sec.getString("name"));
                String world = Objects.requireNonNull(sec.getString("world"));
                double x     = sec.getDouble("x");
                double y     = sec.getDouble("y");
                double z     = sec.getDouble("z");
                float  yaw   = (float) sec.getDouble("yaw");
                float  pitch = (float) sec.getDouble("pitch");

                World bWorld = Bukkit.getWorld(world);
                if (bWorld == null) {
                    log.warning("World '" + world + "' not found — skipping dummy '" + name + "'");
                    continue;
                }

                Location loc = new Location(bWorld, x, y, z, yaw, pitch);
                FakePlayer fp = new FakePlayer(uuid, name, loc);
                fp.spawn(texture, signature);

                byUUID.put(uuid, fp);
                byName.put(name.toLowerCase(Locale.ROOT), fp);
                log.info("Restored dummy '" + name + "' in world '" + world + "'");
            } catch (Exception e) {
                log.warning("Failed to restore dummy '" + key + "': " + e.getMessage());
            }
        }
    }

    /** Removes every live dummy and clears the registry (plugin disable). */
    public void removeAll() {
        for (FakePlayer fp : byUUID.values()) {
            try { fp.remove(); } catch (Exception e) {
                log.warning("Error removing dummy '" + fp.getName() + "': " + e.getMessage());
            }
        }
        byUUID.clear();
        byName.clear();
    }

    // -----------------------------------------------------------------------
    // Spawn / remove API
    // -----------------------------------------------------------------------

    /**
     * Spawns a new dummy at the given location and persists it.
     *
     * @return the created {@link FakePlayer}, or {@code null} if the server
     *         limit has been reached
     * @throws IllegalArgumentException if the name is already in use
     */
    public FakePlayer spawn(String name, Location location) {
        int max = plugin.getConfig().getInt("max-dummies", 0);
        if (max > 0 && byUUID.size() >= max)
            return null;

        String lc = name.toLowerCase(Locale.ROOT);
        if (byName.containsKey(lc))
            throw new IllegalArgumentException("A dummy named '" + name + "' already exists.");

        String texture   = plugin.getConfig().getString("default-skin.texture",   "");
        String signature = plugin.getConfig().getString("default-skin.signature", "");

        FakePlayer fp = new FakePlayer(UUID.randomUUID(), name, location);
        fp.spawn(texture, signature);

        byUUID.put(fp.getUniqueId(), fp);
        byName.put(lc, fp);
        save();
        return fp;
    }

    /**
     * Removes the dummy identified by name or UUID string.
     *
     * @return {@code true} if a dummy was found and removed
     */
    public boolean remove(String nameOrUUID) {
        FakePlayer fp = resolve(nameOrUUID);
        if (fp == null) return false;

        fp.remove();
        byUUID.remove(fp.getUniqueId());
        byName.remove(fp.getName().toLowerCase(Locale.ROOT));
        save();
        return true;
    }

    /** Removes all dummies and overwrites persistent storage with an empty file. */
    public int removeAll(boolean persist) {
        int count = byUUID.size();
        removeAll();
        if (persist) save();
        return count;
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    public FakePlayer resolve(String nameOrUUID) {
        // Try UUID first
        try {
            UUID id = UUID.fromString(nameOrUUID);
            return byUUID.get(id);
        } catch (IllegalArgumentException ignored) {}
        // Fall back to name
        return byName.get(nameOrUUID.toLowerCase(Locale.ROOT));
    }

    public Collection<FakePlayer> getAll() {
        return Collections.unmodifiableCollection(byUUID.values());
    }

    public Set<String> getNames() {
        return Collections.unmodifiableSet(byName.keySet());
    }

    // -----------------------------------------------------------------------
    // KeepAlive hook
    // -----------------------------------------------------------------------

    /** Invoked by {@link KeepAliveTask} to re-inject any evicted dummies. */
    public void keepAlive() {
        for (FakePlayer fp : byUUID.values()) {
            fp.reAddIfEvicted();
        }
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        ConfigurationSection root = cfg.createSection("dummies");

        for (FakePlayer fp : byUUID.values()) {
            ConfigurationSection sec = root.createSection(fp.getUniqueId().toString());
            Location loc = fp.getSpawnLocation();

            sec.set("uuid",  fp.getUniqueId().toString());
            sec.set("name",  fp.getName());
            sec.set("world", loc.getWorld().getName());
            sec.set("x",     loc.getX());
            sec.set("y",     loc.getY());
            sec.set("z",     loc.getZ());
            sec.set("yaw",   (double) loc.getYaw());
            sec.set("pitch", (double) loc.getPitch());
        }

        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(storageFile);
        } catch (IOException e) {
            log.severe("Could not save " + FILE_NAME + ": " + e.getMessage());
        }
    }
}
