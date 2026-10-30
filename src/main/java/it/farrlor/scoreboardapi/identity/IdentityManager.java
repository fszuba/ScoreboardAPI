package it.farrlor.scoreboardapi.identity;

import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import it.farrlor.scoreboardapi.api.IdentitySettings;
import it.farrlor.scoreboardapi.api.DisguiseInfo;
import it.farrlor.scoreboardapi.api.ScoreboardApi;
import it.farrlor.scoreboardapi.api.Setting;
import it.farrlor.scoreboardapi.packets.ProfilePackets;
import it.farrlor.scoreboardapi.scoreboard.NametagTeams;
import it.farrlor.scoreboardapi.skins.SkinService;
import it.farrlor.scoreboardapi.storage.PlayerStorage;
import it.farrlor.scoreboardapi.disguise.DisguiseService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public final class IdentityManager implements ScoreboardApi {
    private final JavaPlugin plugin;
    private final PlayerStorage storage;
    private final SkinService skins;
    private final Map<UUID, IdentitySettings> settings = new ConcurrentHashMap<>();
    private final Map<String, List<TextureProperty>> resolved = new ConcurrentHashMap<>();
    private ProfilePackets packets;
    private NametagTeams teams;
    private volatile DisguiseService disguises;
    private volatile boolean skinWithNick;
    private volatile boolean updateTab;

    public IdentityManager(JavaPlugin plugin, PlayerStorage storage, SkinService skins) {
        this.plugin = plugin;
        this.storage = storage;
        this.skins = skins;
        reload();
        if (plugin.getConfig().getBoolean("settings.persist-player-settings", true)) settings.putAll(storage.load());
    }

    public void attach(ProfilePackets packets, NametagTeams teams) {
        this.packets = packets;
        this.teams = teams;
    }

    public void attachDisguises(DisguiseService disguises) { this.disguises = disguises; }

    public void reload() {
        skinWithNick = plugin.getConfig().getBoolean("settings.change-skin-with-nickname", true);
        updateTab = plugin.getConfig().getBoolean("settings.update-tab-list", true);
    }

    @Override public IdentitySettings getSettings(UUID realUuid) {
        return settings.getOrDefault(realUuid, IdentitySettings.EMPTY);
    }

    public IdentitySettings effectiveSettings(UUID id) {
        DisguiseService service = disguises;
        return service == null ? getSettings(id) : service.effectiveSettings(id, getSettings(id));
    }

    public String tabName(UUID id, String realName) {
        String nick = getSettings(id).nick();
        String base = nick != null && updateTab ? nick : realName;
        DisguiseService service = disguises;
        return service == null ? base : service.tabName(id, base);
    }

    public List<TextureProperty> textureFor(UUID id) {
        DisguiseService service = disguises;
        if (service != null && service.copiesTextures(id)) return service.textureFor(id);
        String source = desiredSkin(getSettings(id));
        return source == null ? null : resolved.get(source.toLowerCase(java.util.Locale.ROOT));
    }

    private String desiredSkin(IdentitySettings value) {
        if (value.skin() != null) return value.skin();
        return skinWithNick ? value.nick() : null;
    }

    private void checkMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("ScoreboardApi mutation must run on the server thread");
    }

    public static String accountName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{3,16}"))
            throw new IllegalArgumentException("Name must be 3-16 characters: letters, digits or underscore");
        return value;
    }

    public void ensureUniqueNick(UUID id, String name) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(id)) continue;
            String current = effectiveSettings(player.getUniqueId()).nick();
            if (name.equalsIgnoreCase(current == null ? player.getName() : current))
                throw new IllegalArgumentException("That visible name is already used by an online player");
        }
    }

    private String text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Text cannot be empty");
        int max = Math.max(1, plugin.getConfig().getInt("settings.max-affix-length", 32));
        if (value.codePointCount(0, value.length()) > max || value.chars().anyMatch(c -> Character.isISOControl(c)))
            throw new IllegalArgumentException("Text exceeds " + max + " characters or contains control characters");
        return value;
    }

    @Override public CompletableFuture<Void> setVisualNick(UUID id, String name) {
        checkMainThread(); accountName(name); ensureUniqueNick(id, name);
        return applyWithSkin(id, current -> current.withNick(name), true);
    }

    @Override public CompletableFuture<Void> setSkin(UUID id, String name) {
        checkMainThread(); accountName(name);
        return applyWithSkin(id, current -> current.withSkin(name), true);
    }

    private CompletableFuture<Void> applyWithSkin(UUID id, UnaryOperator<IdentitySettings> change, boolean refresh) {
        IdentitySettings next = change.apply(getSettings(id));
        String source = desiredSkin(next);
        if (source == null || resolved.containsKey(source.toLowerCase(java.util.Locale.ROOT))) {
            commit(id, next, refresh);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        skins.fetch(source).whenComplete((properties, error) -> {
            if (!plugin.isEnabled()) {
                result.completeExceptionally(new IllegalStateException("Plugin is disabled"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    result.completeExceptionally(error);
                    return;
                }
                resolved.put(source.toLowerCase(java.util.Locale.ROOT), properties);
                commit(id, change.apply(getSettings(id)), refresh);
                result.complete(null);
            });
        });
        return result;
    }

    @Override public void setChatName(UUID id, String name) {
        checkMainThread(); commit(id, getSettings(id).withChat(accountName(name)), false);
    }

    @Override public void setPrefix(UUID id, String prefix) {
        checkMainThread(); commit(id, getSettings(id).withPrefix(text(prefix)), false);
    }

    @Override public void setSuffix(UUID id, String suffix) {
        checkMainThread(); commit(id, getSettings(id).withSuffix(text(suffix)), false);
    }

    @Override public void reset(UUID id, Setting setting) {
        checkMainThread();
        UnaryOperator<IdentitySettings> change = old -> switch (setting) {
            case NICK -> old.withNick(null);
            case SKIN -> old.withSkin(null);
            case CHAT -> old.withChat(null);
            case PREFIX -> old.withPrefix(null);
            case SUFFIX -> old.withSuffix(null);
            case ALL -> IdentitySettings.EMPTY;
        };
        IdentitySettings next = change.apply(getSettings(id));
        String source = desiredSkin(next);
        commit(id, next, setting == Setting.NICK || setting == Setting.SKIN || setting == Setting.ALL);
        if (source != null && !resolved.containsKey(source.toLowerCase(java.util.Locale.ROOT))) {
            skins.fetch(source).whenComplete((properties, error) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().warning("Cannot restore skin for " + id + ": " + error.getMessage());
                        return;
                    }
                    resolved.put(source.toLowerCase(java.util.Locale.ROOT), properties);
                    Player player = Bukkit.getPlayer(id);
                    if (player != null && source.equalsIgnoreCase(desiredSkin(getSettings(id)))) packets.refresh(player);
                });
            });
        }
    }

    private void commit(UUID id, IdentitySettings next, boolean refreshProfile) {
        if (next.isEmpty()) settings.remove(id); else settings.put(id, next);
        storage.save(settings);
        refreshVisual(id, refreshProfile);
    }

    public void refreshVisual(UUID id) { refreshVisual(id, true); }

    private void refreshVisual(UUID id, boolean refreshProfile) {
        Player target = Bukkit.getPlayer(id);
        if (target == null) return;
        if (refreshProfile) packets.refresh(target);
        teams.update(target);
    }

    @Override public CompletableFuture<DisguiseInfo> disguise(UUID realUuid, String accountName) {
        checkMainThread();
        return disguises.apply(realUuid, accountName);
    }

    @Override public boolean isDisguised(UUID realUuid) { return disguises.isDisguised(realUuid); }

    @Override public Optional<DisguiseInfo> getDisguise(UUID realUuid) { return disguises.getDisguise(realUuid); }

    @Override public void undisguise(UUID realUuid) { checkMainThread(); disguises.remove(realUuid); }

    public void onJoin(Player target) {
        IdentitySettings value = getSettings(target.getUniqueId());
        String source = desiredSkin(value);
        if (source == null) return;
        skins.fetch(source).whenComplete((properties, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().warning("Could not load skin " + source + " for " + target.getName() + ": " + error.getMessage());
                    return;
                }
                if (!source.equalsIgnoreCase(desiredSkin(getSettings(target.getUniqueId())))) return;
                resolved.put(source.toLowerCase(java.util.Locale.ROOT), properties);
                if (target.isOnline()) packets.refresh(target);
            });
        });
    }

    public void save() { storage.save(settings); }
}
