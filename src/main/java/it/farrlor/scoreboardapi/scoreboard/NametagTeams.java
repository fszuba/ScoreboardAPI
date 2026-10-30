package it.farrlor.scoreboardapi.scoreboard;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import it.farrlor.scoreboardapi.api.IdentitySettings;
import it.farrlor.scoreboardapi.identity.IdentityManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-viewer client teams. No observer scoreboard is replaced. */
public final class NametagTeams {
    private final JavaPlugin plugin;
    private final IdentityManager identities;
    private final Map<UUID, Map<UUID, String>> sent = new HashMap<>();

    public NametagTeams(JavaPlugin plugin, IdentityManager identities) {
        this.plugin = plugin;
        this.identities = identities;
    }

    private String teamName(UUID id) { return "sba" + id.toString().replace("-", "").substring(0, 13); }

    public void update(Player target) {
        for (Player viewer : Bukkit.getOnlinePlayers()) update(viewer, target);
    }

    public void syncViewer(Player viewer) {
        for (Player target : Bukkit.getOnlinePlayers()) update(viewer, target);
    }

    private void update(Player viewer, Player target) {
        UUID viewerId = viewer.getUniqueId();
        UUID targetId = target.getUniqueId();
        Map<UUID, String> teams = sent.computeIfAbsent(viewerId, ignored -> new HashMap<>());
        String old = teams.remove(targetId);
        String team = teamName(targetId);
        if (old != null) send(viewer, new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.REMOVE,
            (WrapperPlayServerTeams.ScoreBoardTeamInfo) null));

        IdentitySettings settings = identities.effectiveSettings(targetId);
        if (!plugin.getConfig().getBoolean("nametag.enabled", true)
            || (settings.prefix() == null && settings.suffix() == null)) return;

        String entry = settings.nick() == null ? target.getName() : settings.nick();
        Component prefix = settings.prefix() == null ? Component.empty() : Component.text(settings.prefix() + " ");
        Component suffix = settings.suffix() == null ? Component.empty() : Component.text(" " + settings.suffix());
        var info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(Component.text(team), prefix, suffix,
            WrapperPlayServerTeams.NameTagVisibility.ALWAYS, WrapperPlayServerTeams.CollisionRule.ALWAYS,
            NamedTextColor.WHITE, WrapperPlayServerTeams.OptionData.ALL);
        send(viewer, new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.CREATE, info, entry));
        teams.put(targetId, entry);
    }

    public void removeViewer(Player viewer) { sent.remove(viewer.getUniqueId()); }

    public void removeTarget(Player target) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Map<UUID, String> teams = sent.get(viewer.getUniqueId());
            if (teams != null && teams.remove(target.getUniqueId()) != null)
                send(viewer, new WrapperPlayServerTeams(teamName(target.getUniqueId()), WrapperPlayServerTeams.TeamMode.REMOVE,
                    (WrapperPlayServerTeams.ScoreBoardTeamInfo) null));
        }
    }

    public void clear() {
        for (Player target : Bukkit.getOnlinePlayers()) removeTarget(target);
        sent.clear();
    }

    private void send(Player viewer, WrapperPlayServerTeams packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }
}
