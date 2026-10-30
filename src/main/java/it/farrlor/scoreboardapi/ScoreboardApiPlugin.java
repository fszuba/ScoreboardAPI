package it.farrlor.scoreboardapi;

import com.github.retrooper.packetevents.PacketEvents;
import it.farrlor.scoreboardapi.api.ScoreboardApi;
import it.farrlor.scoreboardapi.chat.ChatFormatting;
import it.farrlor.scoreboardapi.commands.SbaCommand;
import it.farrlor.scoreboardapi.disguise.DisguiseService;
import it.farrlor.scoreboardapi.identity.IdentityManager;
import it.farrlor.scoreboardapi.packets.ProfilePackets;
import it.farrlor.scoreboardapi.scoreboard.NametagTeams;
import it.farrlor.scoreboardapi.skins.SkinService;
import it.farrlor.scoreboardapi.storage.PlayerStorage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class ScoreboardApiPlugin extends JavaPlugin implements Listener {
    private SkinService skins;
    private IdentityManager identities;
    private ProfilePackets packets;
    private NametagTeams teams;
    private DisguiseService disguises;

    @Override public void onEnable() {
        saveDefaultConfig();
        skins = new SkinService(this);
        PlayerStorage storage = new PlayerStorage(this);
        identities = new IdentityManager(this, storage, skins);
        disguises = new DisguiseService(this, identities, skins, storage);
        identities.attachDisguises(disguises);
        packets = new ProfilePackets(this, identities);
        teams = new NametagTeams(this, identities);
        identities.attach(packets, teams);
        PacketEvents.getAPI().getEventManager().registerListener(packets);
        ChatFormatting chat = new ChatFormatting(this, identities);
        Bukkit.getPluginManager().registerEvents(chat, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        SbaCommand command = new SbaCommand(this, identities, teams, chat, packets, skins, disguises);
        Objects.requireNonNull(getCommand("sba")).setExecutor(command);
        Objects.requireNonNull(getCommand("sba")).setTabCompleter(command);
        Bukkit.getServicesManager().register(ScoreboardApi.class, identities, this, ServicePriority.Normal);
        for (Player player : Bukkit.getOnlinePlayers()) {
            identities.onJoin(player);
            disguises.onJoin(player);
        }
    }

    @Override public void onDisable() {
        if (packets != null) {
            packets.restoreVisibility();
            PacketEvents.getAPI().getEventManager().unregisterListener(packets);
        }
        if (teams != null) teams.clear();
        if (identities != null) identities.save();
        if (skins != null) skins.close();
        Bukkit.getServicesManager().unregisterAll(this);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        identities.onJoin(player);
        disguises.onJoin(player);
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            teams.syncViewer(player);
            teams.update(player);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        disguises.onQuit(event.getPlayer());
        teams.removeTarget(event.getPlayer());
        teams.removeViewer(event.getPlayer());
        packets.forgetViewer(event.getPlayer());
    }
}
