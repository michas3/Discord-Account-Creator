package com.lo.afkdummy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles all {@code /afkdummy} subcommands and tab-completion.
 */
public final class DummyCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_USE   = "afkdummy.use";
    private static final String PERM_ADMIN = "afkdummy.admin";

    private final AFKDummyPlugin plugin;

    public DummyCommand(AFKDummyPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Execution
    // -----------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn"     -> handleSpawn(sender, label, args);
            case "remove"    -> handleRemove(sender, args);
            case "removeall" -> handleRemoveAll(sender);
            case "list"      -> handleList(sender);
            case "tp"        -> handleTp(sender, args);
            default          -> usage(sender, label);
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Subcommand handlers
    // -----------------------------------------------------------------------

    private void handleSpawn(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(PERM_USE)) { noPerms(sender); return; }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can spawn dummies.", NamedTextColor.RED));
            return;
        }

        String name = args.length >= 2
            ? args[1]
            : "AFK_" + Integer.toHexString(new Random().nextInt(0xFFFF)).toUpperCase();

        Location loc  = player.getLocation();
        int      max  = plugin.getConfig().getInt("max-dummies", 0);
        int      cur  = plugin.getDummyManager().getAll().size();

        if (max > 0 && cur >= max) {
            player.sendMessage(Component.text(
                "Server dummy limit reached (" + max + ").", NamedTextColor.RED));
            return;
        }

        try {
            FakePlayer fp = plugin.getDummyManager().spawn(name, loc);
            if (fp == null) {
                player.sendMessage(Component.text("Dummy limit reached.", NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text(
                "Spawned dummy '" + fp.getName() + "'.", NamedTextColor.GREEN));

            if (plugin.getConfig().getBoolean("announce", true)) {
                plugin.getServer().broadcast(
                    Component.text("[AFKDummy] " + sender.getName()
                        + " spawned dummy '" + fp.getName() + "'.", NamedTextColor.GRAY),
                    "minecraft.command.op");
            }
        } catch (IllegalArgumentException e) {
            player.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_USE)) { noPerms(sender); return; }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /afkdummy remove <name|UUID>", NamedTextColor.YELLOW));
            return;
        }

        String target = args[1];
        boolean removed = plugin.getDummyManager().remove(target);
        if (!removed) {
            sender.sendMessage(Component.text("No dummy found: '" + target + "'.", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("Removed dummy '" + target + "'.", NamedTextColor.GREEN));
        if (plugin.getConfig().getBoolean("announce", true)) {
            plugin.getServer().broadcast(
                Component.text("[AFKDummy] " + sender.getName()
                    + " removed dummy '" + target + "'.", NamedTextColor.GRAY),
                "minecraft.command.op");
        }
    }

    private void handleRemoveAll(CommandSender sender) {
        if (!sender.hasPermission(PERM_ADMIN)) { noPerms(sender); return; }
        int count = plugin.getDummyManager().removeAll(true);
        sender.sendMessage(Component.text(
            "Removed " + count + " dummy" + (count == 1 ? "" : "s") + ".", NamedTextColor.GREEN));
    }

    private void handleList(CommandSender sender) {
        if (!sender.hasPermission(PERM_USE)) { noPerms(sender); return; }
        Collection<FakePlayer> all = plugin.getDummyManager().getAll();

        if (all.isEmpty()) {
            sender.sendMessage(Component.text("No active dummies.", NamedTextColor.YELLOW));
            return;
        }

        sender.sendMessage(Component.text(
            "─── Active Dummies (" + all.size() + ") ───", NamedTextColor.GOLD));
        for (FakePlayer fp : all) {
            Location loc  = fp.getLiveLocation();
            String   line = String.format("  %s  [%s]  %.1f, %.1f, %.1f  (%s)",
                fp.getName(),
                fp.getUniqueId().toString().substring(0, 8),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getWorld().getName());
            sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
        }
    }

    private void handleTp(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_USE)) { noPerms(sender); return; }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can teleport.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /afkdummy tp <name>", NamedTextColor.YELLOW));
            return;
        }

        FakePlayer fp = plugin.getDummyManager().resolve(args[1]);
        if (fp == null) {
            player.sendMessage(Component.text(
                "No dummy found: '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }

        player.teleport(fp.getLiveLocation());
        player.sendMessage(Component.text(
            "Teleported to dummy '" + fp.getName() + "'.", NamedTextColor.GREEN));
    }

    // -----------------------------------------------------------------------
    // Tab-completion
    // -----------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("spawn", "remove", "list", "tp"));
            if (sender.hasPermission(PERM_ADMIN)) subs.add("removeall");
            return filterPrefix(subs, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("remove") || sub.equals("tp")) {
                List<String> names = plugin.getDummyManager().getAll().stream()
                    .map(FakePlayer::getName)
                    .collect(Collectors.toList());
                return filterPrefix(names, args[1]);
            }
        }

        return List.of();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static List<String> filterPrefix(List<String> options, String prefix) {
        String lc = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
            .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lc))
            .collect(Collectors.toList());
    }

    private static void usage(CommandSender s, String label) {
        s.sendMessage(Component.text(
            "Usage: /" + label + " <spawn|remove|removeall|list|tp>",
            NamedTextColor.YELLOW));
    }

    private static void noPerms(CommandSender s) {
        s.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
    }
}
