package it.farrlor.scoreboardapi.api;

import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Obtain via Bukkit.getServicesManager().load(ScoreboardApi.class).
 * UUIDs are always real player UUIDs. Mutations must be called on the server thread.
 */
public interface ScoreboardApi {
    IdentitySettings getSettings(UUID realUuid);
    CompletableFuture<Void> setVisualNick(UUID realUuid, String accountName);
    CompletableFuture<Void> setSkin(UUID realUuid, String accountName);
    void setChatName(UUID realUuid, String name);
    void setPrefix(UUID realUuid, String text);
    void setSuffix(UUID realUuid, String text);
    void reset(UUID realUuid, Setting setting);
    CompletableFuture<DisguiseInfo> disguise(UUID realUuid, String accountName);
    boolean isDisguised(UUID realUuid);
    Optional<DisguiseInfo> getDisguise(UUID realUuid);
    void undisguise(UUID realUuid);
}
