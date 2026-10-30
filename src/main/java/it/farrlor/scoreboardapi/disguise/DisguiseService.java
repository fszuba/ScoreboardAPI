package it.farrlor.scoreboardapi.disguise;

import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import it.farrlor.scoreboardapi.api.DisguiseInfo;
import it.farrlor.scoreboardapi.api.IdentitySettings;
import it.farrlor.scoreboardapi.identity.IdentityManager;
import it.farrlor.scoreboardapi.skins.SkinService;
import it.farrlor.scoreboardapi.storage.PlayerStorage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** An atomic, client-side overlay over the ordinary identity settings. */
public final class DisguiseService {
    private record Active(DisguiseInfo info, List<TextureProperty> textures) {}

    private final JavaPlugin plugin;
    private final IdentityManager identities;
    private final SkinService skins;
    private final PlayerStorage storage;
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> generations = new HashMap<>(); // main thread only
    private volatile boolean enabled;
    private volatile boolean changeNametag;
    private volatile boolean changeChat;
    private volatile boolean changeTab;
    private volatile boolean copyTextures;
    private volatile boolean preserveAffixes;

    public DisguiseService(JavaPlugin plugin, IdentityManager identities, SkinService skins, PlayerStorage storage) {
        this.plugin = plugin;
        this.identities = identities;
        this.skins = skins;
        this.storage = storage;
        reload();
        if (plugin.getConfig().getBoolean("disguise.persist", true)) {
            storage.loadDisguises().forEach((id, saved) -> {
                List<TextureProperty> textures = null;
                boolean cape = saved.hasOfficialCape();
                if (saved.textureValue() != null && saved.textureSignature() != null) {
                    try {
                        textures = List.of(new TextureProperty("textures", saved.textureValue(), saved.textureSignature()));
                        cape = SkinService.hasOfficialCape(textures);
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("Invalid stored disguise textures for " + id + ": " + exception.getMessage());
                        textures = null;
                        cape = false;
                    }
                }
                active.put(id, new Active(new DisguiseInfo(saved.accountName(), cape), textures));
            });
        } else storage.clearDisguises();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("disguise.enabled", true);
        changeNametag = plugin.getConfig().getBoolean("disguise.change-nametag", true);
        changeChat = plugin.getConfig().getBoolean("disguise.change-chat", true);
        changeTab = plugin.getConfig().getBoolean("disguise.change-tab", true);
        copyTextures = plugin.getConfig().getBoolean("disguise.copy-target-textures", true);
        preserveAffixes = plugin.getConfig().getBoolean("disguise.preserve-prefix-suffix", true);
    }

    private void checkMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Disguise mutations must run on the server thread");
    }

    private long nextGeneration(UUID id) {
        long next = generations.getOrDefault(id, 0L) + 1;
        generations.put(id, next);
        return next;
    }

    public CompletableFuture<DisguiseInfo> apply(UUID id, String name) {
        checkMainThread();
        IdentityManager.accountName(name);
        if (!enabled) return CompletableFuture.failedFuture(new IllegalStateException("Disguises are disabled"));
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player must be online"));
        if (changeNametag) identities.ensureUniqueNick(id, name);
        long generation = nextGeneration(id);
        CompletableFuture<DisguiseInfo> result = new CompletableFuture<>();
        skins.fetch(name).whenComplete((textures, error) -> {
            if (!plugin.isEnabled()) {
                result.completeExceptionally(new CancellationException("Plugin disabled"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (generations.getOrDefault(id, 0L) != generation || Bukkit.getPlayer(id) != player || !player.isOnline()) {
                    result.completeExceptionally(new CancellationException("Disguise request was superseded or player left"));
                    return;
                }
                if (error != null) {
                    result.completeExceptionally(error);
                    return;
                }
                try {
                    if (!enabled) throw new IllegalStateException("Disguises are disabled");
                    if (changeNametag) identities.ensureUniqueNick(id, name);
                    boolean cape = SkinService.hasOfficialCape(textures);
                    DisguiseInfo info = new DisguiseInfo(name, cape);
                    active.put(id, new Active(info, List.copyOf(textures)));
                    save(id, info, textures);
                    identities.refreshVisual(id);
                    result.complete(info);
                } catch (RuntimeException exception) {
                    result.completeExceptionally(exception);
                }
            });
        });
        return result;
    }

    public boolean isDisguised(UUID id) { return active.containsKey(id); }

    public Optional<DisguiseInfo> getDisguise(UUID id) {
        Active value = active.get(id);
        return value == null ? Optional.empty() : Optional.of(value.info());
    }

    public void remove(UUID id) {
        checkMainThread();
        nextGeneration(id);
        if (active.remove(id) != null) {
            storage.setDisguise(id, null);
            identities.save();
            identities.refreshVisual(id);
        }
    }

    public void onQuit(Player player) { nextGeneration(player.getUniqueId()); }

    /** Saved signed textures are usable immediately; a fresh Mojang profile replaces them in the background. */
    public void onJoin(Player player) {
        Active current = active.get(player.getUniqueId());
        if (current == null) return;
        UUID id = player.getUniqueId();
        skins.fetch(current.info().accountName()).whenComplete((textures, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (Bukkit.getPlayer(id) != player || !player.isOnline() || active.get(id) != current) return;
                if (error != null) {
                    plugin.getLogger().warning("Could not refresh disguise for " + player.getName() + ": " + error.getMessage());
                    return;
                }
                try {
                    DisguiseInfo info = new DisguiseInfo(current.info().accountName(), SkinService.hasOfficialCape(textures));
                    active.put(id, new Active(info, List.copyOf(textures)));
                    save(id, info, textures);
                    identities.refreshVisual(id);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Invalid disguise texture data for " + player.getName() + ": " + exception.getMessage());
                }
            });
        });
    }

    private void save(UUID id, DisguiseInfo info, List<TextureProperty> textures) {
        TextureProperty texture = textures.getFirst();
        storage.setDisguise(id, new PlayerStorage.SavedDisguise(info.accountName(), texture.getValue(),
            texture.getSignature(), info.hasOfficialCape()));
        identities.save();
    }

    public IdentitySettings effectiveSettings(UUID id, IdentitySettings base) {
        Active disguise = enabled ? active.get(id) : null;
        if (disguise == null) return base;
        String name = disguise.info().accountName();
        return new IdentitySettings(changeNametag ? name : base.nick(), base.skin(),
            changeChat ? name : base.chat(), preserveAffixes ? base.prefix() : null,
            preserveAffixes ? base.suffix() : null);
    }

    public String tabName(UUID id, String baseTabName) {
        Active disguise = enabled ? active.get(id) : null;
        return disguise != null && changeTab ? disguise.info().accountName() : baseTabName;
    }

    public List<TextureProperty> textureFor(UUID id) {
        Active disguise = enabled && copyTextures ? active.get(id) : null;
        return disguise == null ? null : disguise.textures();
    }

    public boolean copiesTextures(UUID id) { return enabled && copyTextures && active.containsKey(id); }
}
