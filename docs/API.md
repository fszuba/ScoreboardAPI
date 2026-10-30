# ScoreboardAPI Public API

`ScoreboardApi` is registered with Bukkit's `ServicesManager`. In your plugin's `plugin.yml`, declare `depend: [ScoreboardAPI]` or `softdepend: [ScoreboardAPI]`, and add the ScoreboardAPI JAR as a `compileOnly` dependency.

```java
import it.farrlor.scoreboardapi.api.ScoreboardApi;
import it.farrlor.scoreboardapi.api.IdentitySettings;
import it.farrlor.scoreboardapi.api.Setting;
import it.farrlor.scoreboardapi.api.DisguiseInfo;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

ScoreboardApi api = Bukkit.getServicesManager().load(ScoreboardApi.class);
if (api == null) throw new IllegalStateException("ScoreboardAPI is not installed");
Player player = Bukkit.getPlayerExact("Farrlor");
if (player != null) {
    var id = player.getUniqueId(); // Always the real UUID
    api.setPrefix(id, "[HASA]");
    api.setVisualNick(id, "Notch").whenComplete((unused, error) -> {
        if (error != null) getLogger().warning("Skin/nick failed: " + error.getMessage());
    });
    IdentitySettings current = api.getSettings(id);
    api.reset(id, Setting.PREFIX);
    api.disguise(id, "Notch").whenComplete((info, error) -> {
        if (error != null) getLogger().warning("Disguise failed: " + error.getMessage());
    });
    boolean active = api.isDisguised(id);
    api.getDisguise(id).ifPresent(info -> getLogger().info(info.accountName()));
}
```

Later, on the server's main thread, call `api.undisguise(id)` to remove the disguise. Calling it immediately after `disguise` invalidates the pending lookup.

Invoke all state-changing methods on the Paper main thread. `setVisualNick` and `setSkin` return `CompletableFuture<Void>` because texture data is fetched asynchronously; successful completion means the setting has been saved. Other state-changing methods are synchronous. `getSettings` returns an immutable record, where a `null` field indicates that no override is set. The key is always the player's real UUID. Depending on the configuration, `setVisualNick` may also apply the target account's skin; an explicitly configured `setSkin` takes precedence. `reset(id, Setting.ALL)` clears all settings. None of these methods modify the player's internal Paper profile.

If a lookup fails (unknown account, network error, or rate limit), the future completes exceptionally and the previous setting is preserved. Settings stored for offline players take effect when they next join the server.

## Disguise API (1.1.0)

`CompletableFuture<DisguiseInfo> disguise(UUID realUuid, String accountName)` requires the player to be online. It retrieves the signed `textures` property off the main thread and, once the lookup succeeds, applies the visual disguise as a single operation. `DisguiseInfo` exposes `accountName()` and `hasOfficialCape()`. The latter is determined by inspecting the signed texture value without modifying it. If the lookup fails, the player disconnects, or a newer request supersedes the operation, the future completes exceptionally and the existing state is preserved.

`boolean isDisguised(UUID realUuid)` and `Optional<DisguiseInfo> getDisguise(UUID realUuid)` read the active disguise layer. `void undisguise(UUID realUuid)` removes it synchronously on the main thread and invalidates any pending lookups. Stored `IdentitySettings` remain separate and become visible again when the disguise is removed. When `disguise.enabled: false`, a saved disguise can still be retrieved through the API, but it is not rendered.

Do not use `thenAccept` to call the Bukkit API without scheduling work on the main thread if the completion is handled outside ScoreboardAPI. The current implementation completes futures on the main thread, but consumers should explicitly enforce their own threading requirements.
