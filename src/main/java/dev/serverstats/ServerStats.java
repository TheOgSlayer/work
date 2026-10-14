package dev.serverstats;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Shows player statistics using Minecraft's Dialog screens (1.21.6+),
 * the same blurred-background screen with centered text and buttons.
 */
public class ServerStats extends JavaPlugin implements TabExecutor {

    private static final long CACHE_MS = 60_000L;
    private static final int TOP_AMOUNT = 10;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    private Economy economy;
    private List<PlayerStats> cache = List.of();
    private long cacheTime = 0L;

    private record PlayerStats(OfflinePlayer player, double money, int kills, int deaths, long playTicks) {
        String name() {
            return player.getName() == null ? "Unknown" : player.getName();
        }
    }

    /** The four leaderboard tabs, in display order. */
    private enum Category {
        MONEY("Money", NamedTextColor.GREEN),
        KILLS("Kills", NamedTextColor.RED),
        PLAYTIME("Playtime", NamedTextColor.AQUA),
        DEATHS("Deaths", NamedTextColor.GRAY);

        final String label;
        final NamedTextColor color;

        Category(String label, NamedTextColor color) {
            this.label = label;
            this.color = color;
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle / commands
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
        } else {
            getLogger().warning("Vault/economy plugin not found - money will show as 0.");
        }
        Objects.requireNonNull(getCommand("stats")).setExecutor(this);
        Objects.requireNonNull(getCommand("leaderboard")).setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("leaderboard")) {
            openLeaderboard(viewer, Category.MONEY);
            return true;
        }

        OfflinePlayer target;
        if (args.length == 0) {
            target = viewer;
        } else {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
                viewer.sendMessage(Component.text("Player '" + args[0] + "' was not found.", NamedTextColor.RED));
                return true;
            }
        }
        openStats(viewer, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("stats") && args.length == 1) {
            String start = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(start))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private int stat(OfflinePlayer p, Statistic s) {
        try {
            return p.getStatistic(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private PlayerStats load(OfflinePlayer p) {
        double money = 0;
        if (economy != null) {
            try {
                money = economy.getBalance(p);
            } catch (Exception ignored) { }
        }
        return new PlayerStats(p, money,
                stat(p, Statistic.PLAYER_KILLS),
                stat(p, Statistic.DEATHS),
                stat(p, Statistic.PLAY_ONE_MINUTE));
    }

    /** Every player who ever joined; cached so /leaderboard stays cheap. */
    private List<PlayerStats> getAll() {
        long now = System.currentTimeMillis();
        if (now - cacheTime > CACHE_MS) {
            List<PlayerStats> list = new ArrayList<>();
            for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
                if (p.hasPlayedBefore() || p.isOnline()) list.add(load(p));
            }
            cache = list;
            cacheTime = now;
        }
        return cache;
    }

    private double value(PlayerStats p, Category c) {
        return switch (c) {
            case MONEY -> p.money();
            case KILLS -> p.kills();
            case PLAYTIME -> p.playTicks();
            case DEATHS -> p.deaths();
        };
    }

    private String display(PlayerStats p, Category c) {
        return switch (c) {
            case MONEY -> "$ " + compact(p.money());
            case KILLS -> compact(p.kills());
            case PLAYTIME -> playtime(p.playTicks());
            case DEATHS -> compact(p.deaths());
        };
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private static String compact(double v) {
        double abs = Math.abs(v);
        if (abs >= 1e12) return String.format(Locale.US, "%.1fT", v / 1e12);
        if (abs >= 1e9) return String.format(Locale.US, "%.1fB", v / 1e9);
        if (abs >= 1e6) return String.format(Locale.US, "%.1fM", v / 1e6);
        if (abs >= 1e3) return String.format(Locale.US, "%.1fK", v / 1e3);
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.1f", v);
    }

    private static String playtime(long ticks) {
        long totalMin = ticks / 20 / 60;
        long d = totalMin / (60 * 24);
        long h = (totalMin / 60) % 24;
        long m = totalMin % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    // ------------------------------------------------------------------
    // Dialog helpers
    // ------------------------------------------------------------------

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(this, r);
    }

    /** A centered line of text, like the lines in the screenshot. */
    private DialogBody text(Component c) {
        return DialogBody.plainMessage(c, 250);
    }

    private Component line(String label, String value, NamedTextColor color) {
        return Component.text(label + ": ", NamedTextColor.WHITE)
                .append(Component.text(value, color));
    }

    /** A button that runs something when clicked. */
    private ActionButton button(Component label, int width, Runnable onClick) {
        return ActionButton.builder(label)
                .width(width)
                .action(DialogAction.customClick(
                        (view, audience) -> later(onClick),
                        ClickCallback.Options.builder()
                                .uses(ClickCallback.UNLIMITED_USES)
                                .lifetime(Duration.ofMinutes(30))
                                .build()))
                .build();
    }

    /** A button that only closes the dialog. */
    private ActionButton closeButton(String label, int width) {
        return ActionButton.builder(Component.text(label)).width(width).build();
    }

    // ------------------------------------------------------------------
    // /stats screen
    // ------------------------------------------------------------------

    private void openStats(Player viewer, OfflinePlayer target) {
        PlayerStats s = load(target);

        List<DialogBody> body = List.of(
                text(Component.text("Money: ", NamedTextColor.WHITE)
                        .append(Component.text("$ ", NamedTextColor.GREEN))
                        .append(Component.text(compact(s.money()), NamedTextColor.WHITE))),
                text(line("Kills", compact(s.kills()), NamedTextColor.WHITE)),
                text(line("Deaths", compact(s.deaths()), NamedTextColor.WHITE)),
                text(line("Playtime", playtime(s.playTicks()), NamedTextColor.WHITE))
        );

        ActionButton profile = button(Component.text("View Full Profile"), 200,
                () -> openProfile(viewer, target));
        ActionButton back = closeButton("Back", 200);

        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(s.name() + "'s Stats", NamedTextColor.YELLOW))
                        .canCloseWithEscape(true)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(List.of(profile), back, 1)));

        viewer.showDialog(dialog);
    }

    // ------------------------------------------------------------------
    // Full profile screen
    // ------------------------------------------------------------------

    private void openProfile(Player viewer, OfflinePlayer target) {
        PlayerStats s = load(target);

        double kd = s.deaths() == 0 ? s.kills() : (double) s.kills() / s.deaths();
        long firstPlayed = target.getFirstPlayed();
        long lastSeen = target.isOnline() ? System.currentTimeMillis() : target.getLastSeen();

        List<DialogBody> body = List.of(
                text(Component.text("Money: ", NamedTextColor.WHITE)
                        .append(Component.text("$ ", NamedTextColor.GREEN))
                        .append(Component.text(compact(s.money()), NamedTextColor.WHITE))),
                text(line("Kills", compact(s.kills()), NamedTextColor.WHITE)),
                text(line("Deaths", compact(s.deaths()), NamedTextColor.WHITE)),
                text(line("K/D Ratio", String.format(Locale.US, "%.2f", kd), NamedTextColor.GOLD)),
                text(line("Playtime", playtime(s.playTicks()), NamedTextColor.WHITE)),
                text(line("Mobs Killed", compact(stat(target, Statistic.MOB_KILLS)), NamedTextColor.WHITE)),
                text(line("Damage Dealt", compact(stat(target, Statistic.DAMAGE_DEALT) / 10.0), NamedTextColor.WHITE)),
                text(line("Damage Taken", compact(stat(target, Statistic.DAMAGE_TAKEN) / 10.0), NamedTextColor.WHITE)),
                text(line("Distance Walked", compact(stat(target, Statistic.WALK_ONE_CM) / 100000.0) + " km", NamedTextColor.WHITE)),
                text(line("Jumps", compact(stat(target, Statistic.JUMP)), NamedTextColor.WHITE)),
                text(line("First Joined", firstPlayed > 0 ? DATE_FMT.format(Instant.ofEpochMilli(firstPlayed)) : "Unknown", NamedTextColor.WHITE)),
                text(line("Last Seen", target.isOnline() ? "Online now" :
                        (lastSeen > 0 ? DATE_FMT.format(Instant.ofEpochMilli(lastSeen)) : "Unknown"), NamedTextColor.WHITE))
        );

        // Back returns to the stats screen
        ActionButton back = button(Component.text("Back"), 200, () -> openStats(viewer, target));

        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(s.name() + "'s Profile", NamedTextColor.YELLOW))
                        .canCloseWithEscape(true)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(List.of(back), null, 1)));

        viewer.showDialog(dialog);
    }

    // ------------------------------------------------------------------
    // /leaderboard screen (4 tabs: Money, Kills, Playtime, Deaths)
    // ------------------------------------------------------------------

    private void openLeaderboard(Player viewer, Category current) {
        List<PlayerStats> top = getAll().stream()
                .sorted(Comparator.comparingDouble((PlayerStats p) -> value(p, current)).reversed())
                .limit(TOP_AMOUNT)
                .toList();

        List<DialogBody> body = new ArrayList<>();
        if (top.isEmpty()) {
            body.add(text(Component.text("No data yet.", NamedTextColor.GRAY)));
        }
        for (int i = 0; i < top.size(); i++) {
            PlayerStats p = top.get(i);
            NamedTextColor rankColor = switch (i) {
                case 0 -> NamedTextColor.GOLD;
                case 1 -> NamedTextColor.GRAY;
                case 2 -> NamedTextColor.DARK_RED;
                default -> NamedTextColor.DARK_GRAY;
            };
            body.add(text(Component.text("#" + (i + 1) + " ", rankColor)
                    .append(Component.text(p.name() + "  -  ", NamedTextColor.WHITE))
                    .append(Component.text(display(p, current), current.color))));
        }
        body.add(text(Component.text("Use /stats <player> for full details", NamedTextColor.DARK_GRAY)));

        // The four tabs, shown side by side in one row
        List<ActionButton> tabs = new ArrayList<>();
        for (Category c : Category.values()) {
            Component label = (c == current)
                    ? Component.text("> " + c.label + " <", NamedTextColor.YELLOW, TextDecoration.BOLD)
                    : Component.text(c.label, c.color);
            tabs.add(button(label, 100, () -> openLeaderboard(viewer, c)));
        }

        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text("Leaderboard - " + current.label, NamedTextColor.YELLOW))
                        .canCloseWithEscape(true)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(tabs, closeButton("Close", 200), 4)));

        viewer.showDialog(dialog);
    }
}
