package com.lo.afkdummy;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketFlow;
import net.minecraft.network.protocol.game.ClientInformation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Wraps an NMS {@link ServerPlayer} that has no real network connection.
 *
 * <p>The fake channel discards all outbound packets; the player is added to
 * the server's {@link PlayerList} so proximity and mob-targeting logic fires
 * against it as though it were a real player.
 */
public final class FakePlayer {

    // Reflection handles — resolved once at class load against Mojang-mapped names.
    private static final Field F_CHANNEL;
    private static final Field F_ADDRESS;
    private static final Field F_PLAYERS_BY_UUID;
    private static final Field F_PLAYERS_BY_NAME;

    static {
        try {
            F_CHANNEL  = findField(Connection.class, Channel.class);
            F_ADDRESS  = findField(Connection.class, SocketAddress.class);
            F_PLAYERS_BY_UUID = findMapField(PlayerList.class, UUID.class);
            F_PLAYERS_BY_NAME = findMapField(PlayerList.class, String.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // -----------------------------------------------------------------------

    private final UUID         uuid;
    private final String       name;
    private final Location     spawnLocation;
    private       ServerPlayer handle;
    private       boolean      spawned;

    public FakePlayer(UUID uuid, String name, Location spawnLocation) {
        this.uuid          = uuid;
        this.name          = name;
        this.spawnLocation = spawnLocation.clone();
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Creates the underlying {@link ServerPlayer}, registers it with the
     * server's {@link PlayerList}, and places it in the world.
     */
    public void spawn(String skinTexture, String skinSignature) {
        if (spawned) return;

        CraftServer      craftServer = (CraftServer) Bukkit.getServer();
        MinecraftServer  nmsServer   = craftServer.getServer();
        CraftWorld       craftWorld  = (CraftWorld) spawnLocation.getWorld();
        ServerLevel      nmsLevel    = craftWorld.getHandle();

        GameProfile profile = new GameProfile(uuid, name);
        if (skinTexture != null && !skinTexture.isBlank()) {
            profile.getProperties().put(
                "textures", new Property("textures", skinTexture, skinSignature));
        }

        ClientInformation clientInfo = ClientInformation.createDefault();
        handle = new ServerPlayer(nmsServer, nmsLevel, profile, clientInfo);
        handle.setPos(spawnLocation.getX(), spawnLocation.getY(), spawnLocation.getZ());
        handle.setYRot(spawnLocation.getYaw());
        handle.setXRot(spawnLocation.getPitch());

        try {
            Connection fakeConn = buildDummyConnection();
            CommonListenerCookie cookie =
                new CommonListenerCookie(profile, 0, clientInfo, false);
            nmsServer.getPlayerList().placeNewPlayer(fakeConn, handle, cookie);
            spawned = true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to spawn fake player '" + name + "'", e);
        }
    }

    /**
     * Removes this fake player from the world and the server's PlayerList.
     */
    public void remove() {
        if (!spawned || handle == null) return;
        try {
            CraftServer     craftServer = (CraftServer) Bukkit.getServer();
            MinecraftServer nmsServer   = craftServer.getServer();
            PlayerList      playerList  = nmsServer.getPlayerList();

            playerList.remove(handle);
            handle.serverLevel().removePlayerImmediately(
                handle, Entity.RemovalReason.DISCARDED);
            spawned = false;
        } catch (Exception e) {
            AFKDummyPlugin.getInstance().getLogger()
                .warning("Error removing dummy '" + name + "': " + e.getMessage());
        }
    }

    /**
     * Called by {@link KeepAliveTask} — re-injects the dummy into the
     * PlayerList if the server has somehow evicted it.
     */
    @SuppressWarnings("unchecked")
    public void reAddIfEvicted() {
        if (!spawned || handle == null) return;
        try {
            CraftServer     craftServer = (CraftServer) Bukkit.getServer();
            MinecraftServer nmsServer   = craftServer.getServer();
            PlayerList      playerList  = nmsServer.getPlayerList();

            if (playerList.getPlayers().contains(handle)) return;

            playerList.getPlayers().add(handle);

            Map<UUID,   ServerPlayer> byUuid = (Map<UUID,   ServerPlayer>) F_PLAYERS_BY_UUID.get(playerList);
            Map<String, ServerPlayer> byName = (Map<String, ServerPlayer>) F_PLAYERS_BY_NAME.get(playerList);
            byUuid.put(uuid, handle);
            byName.put(name.toLowerCase(Locale.ROOT), handle);
        } catch (Exception e) {
            AFKDummyPlugin.getInstance().getLogger()
                .warning("KeepAlive re-add failed for '" + name + "': " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public UUID         getUniqueId()     { return uuid; }
    public String       getName()         { return name; }
    public Location     getSpawnLocation(){ return spawnLocation.clone(); }
    public ServerPlayer getHandle()       { return handle; }
    public boolean      isSpawned()       { return spawned; }

    /** Bukkit-facing location from the live NMS entity position. */
    public Location getLiveLocation() {
        if (handle == null) return spawnLocation.clone();
        return new Location(
            spawnLocation.getWorld(),
            handle.getX(), handle.getY(), handle.getZ(),
            handle.getYRot(), handle.getXRot()
        );
    }

    // -----------------------------------------------------------------------
    // Fake connection
    // -----------------------------------------------------------------------

    private static Connection buildDummyConnection() throws Exception {
        Connection conn = new Connection(PacketFlow.SERVERBOUND);

        // EmbeddedChannel provides its own EventLoop and a real ChannelPipeline.
        // We override write/writeAndFlush so outbound MC packets are silently dropped.
        EmbeddedChannel ch = new EmbeddedChannel() {
            @Override public boolean isActive() { return true; }
            @Override public boolean isOpen()   { return true; }

            @Override
            public ChannelFuture write(Object msg) {
                ReferenceCountUtil.release(msg);
                return newSucceededFuture();
            }
            @Override
            public ChannelFuture write(Object msg, ChannelPromise p) {
                ReferenceCountUtil.release(msg);
                p.setSuccess();
                return p;
            }
            @Override
            public ChannelFuture writeAndFlush(Object msg) {
                ReferenceCountUtil.release(msg);
                return newSucceededFuture();
            }
            @Override
            public ChannelFuture writeAndFlush(Object msg, ChannelPromise p) {
                ReferenceCountUtil.release(msg);
                p.setSuccess();
                return p;
            }
            @Override
            public Channel flush() { return this; }
        };

        F_CHANNEL.set(conn, ch);
        F_ADDRESS.set(conn, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return conn;
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------

    /** Finds the first field in {@code cls} or its superclasses assignable to {@code type}. */
    private static Field findField(Class<?> cls, Class<?> type) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new NoSuchFieldException(
            "No field of type " + type.getName() + " in hierarchy of " + cls.getName());
    }

    /**
     * Finds the first {@link Map} field in {@code cls} whose generic key type is {@code keyType}.
     * Requires the field's generic signature to be available at runtime (standard for compiled Java).
     */
    private static Field findMapField(Class<?> cls, Class<?> keyType) throws Exception {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                java.lang.reflect.ParameterizedType pt =
                    (java.lang.reflect.ParameterizedType) f.getGenericType();
                java.lang.reflect.Type k = pt.getActualTypeArguments()[0];
                if (k instanceof Class<?> kc && kc.equals(keyType)) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new NoSuchFieldException(
            "No Map<" + keyType.getName() + ", ?> field in " + cls.getName());
    }
}
