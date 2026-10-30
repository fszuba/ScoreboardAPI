package it.farrlor.scoreboardapi.packets;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import it.farrlor.scoreboardapi.api.IdentitySettings;
import it.farrlor.scoreboardapi.identity.IdentityManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;

/** Rewrites only outgoing ADD_PLAYER data; UUID, chat session and all other fields stay intact. */
public final class ProfilePackets extends PacketListenerAbstract {
    private final JavaPlugin plugin;
    private final IdentityManager identities;
    private record OriginalInfo(WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry,
                                EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions) {}
    private final Map<UUID, Map<UUID, OriginalInfo>> listed = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    public ProfilePackets(JavaPlugin plugin, IdentityManager identities) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.identities = identities;
    }

    @Override public void onPacketSend(PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) return;
        if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            var removed = new WrapperPlayServerPlayerInfoRemove(event);
            Map<UUID, OriginalInfo> entries = listed.get(viewer.getUniqueId());
            if (entries != null) removed.getProfileIds().forEach(entries::remove);
            return;
        }
        if (event.getPacketType() != PacketType.Play.Server.PLAYER_INFO_UPDATE) return;
        WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(event);
        if (!packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)) {
            if (packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME))
                updateDisplayNames(event, viewer, packet);
            return;
        }
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = new ArrayList<>(packet.getEntries());
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            var original = entries.get(i);
            UserProfile source = original.getGameProfile();
            if (source == null) continue;
            UUID id = source.getUUID();
            listed.computeIfAbsent(viewer.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .put(id, new OriginalInfo(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(original),
                    EnumSet.copyOf(packet.getActions())));
            IdentitySettings settings = identities.effectiveSettings(id);
            if (settings.nick() != null || identities.textureFor(id) != null
                || !identities.tabName(id, source.getName()).equals(source.getName())) {
                entries.set(i, visual(original, id));
                changed = true;
            }
        }
        if (changed) {
            packet.setEntries(entries);
            event.markForReEncode(true);
        }
    }

    private void updateDisplayNames(PacketSendEvent event, Player viewer, WrapperPlayServerPlayerInfoUpdate packet) {
        Map<UUID, OriginalInfo> known = listed.get(viewer.getUniqueId());
        if (known == null) return;
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = new ArrayList<>(packet.getEntries());
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            var incoming = entries.get(i);
            OriginalInfo saved = known.get(incoming.getProfileId());
            if (saved == null) continue;
            var original = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(saved.entry());
            original.setDisplayName(incoming.getDisplayName());
            known.put(incoming.getProfileId(), new OriginalInfo(original, saved.actions()));
            String realName = original.getGameProfile().getName();
            String tab = identities.tabName(incoming.getProfileId(), realName);
            if (identities.effectiveSettings(incoming.getProfileId()).nick() != null || !tab.equals(realName)) {
                var replacement = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(incoming);
                replacement.setDisplayName(Component.text(tab));
                entries.set(i, replacement);
                changed = true;
            }
        }
        if (changed) {
            packet.setEntries(entries);
            event.markForReEncode(true);
        }
    }

    private WrapperPlayServerPlayerInfoUpdate.PlayerInfo visual(
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo original, UUID id) {
        UserProfile source = original.getGameProfile();
        IdentitySettings settings = identities.effectiveSettings(id);
        List<TextureProperty> skin = identities.textureFor(id);
        var replacement = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(original);
        replacement.setGameProfile(new UserProfile(id,
            settings.nick() == null ? source.getName() : settings.nick(),
            skin == null ? source.getTextureProperties() : skin));
        String tab = identities.tabName(id, source.getName());
        if (settings.nick() != null || !tab.equals(source.getName())) replacement.setDisplayName(Component.text(tab));
        return replacement;
    }

    /** The server's own tracker performs the destroy/spawn and retains interaction metadata. */
    public void refresh(Player target) {
        UUID id = target.getUniqueId();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target) || !viewer.canSee(target)) continue;
            Map<UUID, OriginalInfo> entries = listed.get(viewer.getUniqueId());
            OriginalInfo original = entries == null ? null : entries.get(id);
            if (original == null) continue;
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, new WrapperPlayServerPlayerInfoRemove(id));
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer,
                new WrapperPlayServerPlayerInfoUpdate(original.actions(), visual(original.entry(), id)));
        }
        for (Player viewer : List.copyOf(target.getTrackedBy())) {
            if (viewer.equals(target) || !viewer.canSee(target)) continue;
            String key = viewer.getUniqueId() + ":" + target.getUniqueId();
            if (!refreshing.add(key)) continue;
            viewer.hideEntity(plugin, target);
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (viewer.isOnline() && target.isOnline()) viewer.showEntity(plugin, target);
                } finally {
                    refreshing.remove(key);
                }
            });
        }
    }

    public void forgetViewer(Player viewer) { listed.remove(viewer.getUniqueId()); }

    public void restoreVisibility() {
        for (String key : refreshing) {
            String[] ids = key.split(":");
            Player viewer = Bukkit.getPlayer(UUID.fromString(ids[0]));
            Player target = Bukkit.getPlayer(UUID.fromString(ids[1]));
            if (viewer != null && target != null) viewer.showEntity(plugin, target);
        }
        refreshing.clear();
    }
}
