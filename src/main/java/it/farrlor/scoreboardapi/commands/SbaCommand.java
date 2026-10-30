package it.farrlor.scoreboardapi.commands;

import it.farrlor.scoreboardapi.api.Setting;
import it.farrlor.scoreboardapi.identity.IdentityManager;
import it.farrlor.scoreboardapi.scoreboard.NametagTeams;
import it.farrlor.scoreboardapi.chat.ChatFormatting;
import it.farrlor.scoreboardapi.packets.ProfilePackets;
import it.farrlor.scoreboardapi.skins.SkinService;
import it.farrlor.scoreboardapi.disguise.DisguiseService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class SbaCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final IdentityManager identities;
    private final NametagTeams teams;
    private final ChatFormatting chat;
    private final ProfilePackets packets;
    private final SkinService skins;
    private final DisguiseService disguises;

    public SbaCommand(JavaPlugin plugin, IdentityManager identities, NametagTeams teams, ChatFormatting chat,
                      ProfilePackets packets, SkinService skins, DisguiseService disguises) {
        this.plugin = plugin;
        this.identities = identities;
        this.teams = teams;
        this.chat = chat;
        this.packets = packets;
        this.skins = skins;
        this.disguises = disguises;
    }

    private void reply(CommandSender sender, String text) { sender.sendMessage(Component.text(text)); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { usage(sender); return true; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("reload")) {
            if (!sender.hasPermission("scoreboardapi.reload")) return denied(sender);
            plugin.reloadConfig();
            chat.reload();
            identities.reload();
            skins.reload();
            disguises.reload();
            teams.clear();
            for (Player player : Bukkit.getOnlinePlayers()) {
                teams.syncViewer(player);
                identities.onJoin(player);
                disguises.onJoin(player);
                packets.refresh(player);
            }
            reply(sender, "ScoreboardAPI configuration reloaded.");
            return true;
        }
        if (!List.of("nick", "skin", "chat", "prefix", "suffix", "reset", "disguise", "undisguise").contains(action)) {
            usage(sender); return true;
        }
        if (!sender.hasPermission("scoreboardapi." + action)) return denied(sender);
        if (action.equals("undisguise")) {
            if (args.length > 2) { usage(sender); return true; }
            try {
                Player target = target(sender, args.length == 2 ? args[1] : null);
                identities.undisguise(target.getUniqueId());
                reply(sender, "Removed disguise for " + target.getName() + ".");
            } catch (IllegalArgumentException exception) {
                reply(sender, exception.getMessage());
            }
            return true;
        }
        if (args.length < 2) { usage(sender); return true; }

        try {
            Player target;
            String value;
            if (action.equals("prefix") || action.equals("suffix")) {
                int separator = Arrays.asList(args).indexOf("--player");
                if (separator >= 0) {
                    if (separator != args.length - 2 || separator < 2) throw new IllegalArgumentException("Use --player <online player> at the end");
                    target = target(sender, args[separator + 1]);
                    value = String.join(" ", Arrays.copyOfRange(args, 1, separator));
                } else {
                    target = target(sender, null);
                    value = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                }
            } else {
                if (args.length > 3) { usage(sender); return true; }
                target = target(sender, args.length == 3 ? args[2] : null);
                value = args[1];
            }

            if (action.equals("reset")) {
                Setting setting = Setting.valueOf(value.toUpperCase(Locale.ROOT));
                identities.reset(target.getUniqueId(), setting);
                reply(sender, "Reset " + setting.name().toLowerCase(Locale.ROOT) + " for " + target.getName() + ".");
                return true;
            }
            CompletableFuture<Void> future = switch (action) {
                case "nick" -> identities.setVisualNick(target.getUniqueId(), value);
                case "skin" -> identities.setSkin(target.getUniqueId(), value);
                case "disguise" -> identities.disguise(target.getUniqueId(), value).thenAccept(info -> {});
                case "chat" -> { identities.setChatName(target.getUniqueId(), value); yield CompletableFuture.completedFuture(null); }
                case "prefix" -> { identities.setPrefix(target.getUniqueId(), value); yield CompletableFuture.completedFuture(null); }
                case "suffix" -> { identities.setSuffix(target.getUniqueId(), value); yield CompletableFuture.completedFuture(null); }
                default -> throw new IllegalArgumentException("Unknown action");
            };
            future.whenComplete((ignored, error) -> {
                if (error == null) reply(sender, "Updated " + action + " for " + target.getName() + ".");
                else reply(sender, "Could not update " + action + ": " + rootMessage(error));
            });
        } catch (IllegalArgumentException exception) {
            reply(sender, exception.getMessage());
        }
        return true;
    }

    private Player target(CommandSender sender, String name) {
        if (name == null) {
            if (sender instanceof Player player) return player;
            throw new IllegalArgumentException("Console must specify an online player");
        }
        if (!sender.hasPermission("scoreboardapi.others")) throw new IllegalArgumentException("No permission to edit other players");
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) throw new IllegalArgumentException("Player is not online: " + name);
        return player;
    }

    private String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private boolean denied(CommandSender sender) { reply(sender, "No permission."); return true; }
    private void usage(CommandSender sender) {
        reply(sender, "/sba <nick|skin|chat> <name> [online player]");
        reply(sender, "/sba disguise <account> [online player] | /sba undisguise [online player]");
        reply(sender, "/sba <prefix|suffix> <text> [--player online player]");
        reply(sender, "/sba reset <nick|skin|chat|prefix|suffix|all> [online player]");
        reply(sender, "/sba reload");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("nick", "skin", "chat", "prefix", "suffix", "disguise", "undisguise", "reset", "reload").stream()
            .filter(option -> sender.hasPermission("scoreboardapi." + option))
            .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("reset"))
            return List.of("nick", "skin", "chat", "prefix", "suffix", "all").stream()
                .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("undisguise") && sender.hasPermission("scoreboardapi.others"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && sender.hasPermission("scoreboardapi.others"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
