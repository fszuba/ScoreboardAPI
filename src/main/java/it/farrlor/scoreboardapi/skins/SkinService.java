package it.farrlor.scoreboardapi.skins;

import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mojang profile lookup, always off the server thread. Failed requests are not cached. */
public final class SkinService implements AutoCloseable {
    private record CacheEntry(List<TextureProperty> textures, long expiresAt) {}
    private final JavaPlugin plugin;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "ScoreboardAPI-skins");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<List<TextureProperty>>> pending = new ConcurrentHashMap<>();
    private volatile long cacheMinutes;
    private volatile int timeoutSeconds;
    private volatile long rateLimitedUntil;

    public SkinService(JavaPlugin plugin) { this.plugin = plugin; reload(); }

    public void reload() {
        cacheMinutes = Math.max(1, plugin.getConfig().getLong("skins.cache-duration-minutes", 60));
        timeoutSeconds = Math.max(1, plugin.getConfig().getInt("skins.request-timeout-seconds", 5));
    }

    public CompletableFuture<List<TextureProperty>> fetch(String accountName) {
        if (!accountName.matches("[A-Za-z0-9_]{3,16}"))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid Minecraft account name"));
        String key = accountName.toLowerCase(Locale.ROOT);
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.expiresAt > System.currentTimeMillis())
            return CompletableFuture.completedFuture(entry.textures);
        if (System.currentTimeMillis() < rateLimitedUntil)
            return CompletableFuture.failedFuture(new IOException("Mojang rate limit cooldown is active"));
        CompletableFuture<List<TextureProperty>> future = pending.computeIfAbsent(key,
            unused -> CompletableFuture.supplyAsync(() -> lookup(accountName), executor));
        future.whenComplete((value, error) -> pending.remove(key, future));
        return future;
    }

    private List<TextureProperty> lookup(String name) {
        try {
            JsonObject account = request("https://api.mojang.com/users/profiles/minecraft/" + name);
            String id = account.get("id").getAsString();
            JsonObject profile = request("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
            if (!id.equalsIgnoreCase(profile.get("id").getAsString()))
                throw new IOException("Mojang profile UUID does not match the account lookup");
            for (var item : profile.getAsJsonArray("properties")) {
                JsonObject property = item.getAsJsonObject();
                if ("textures".equals(property.get("name").getAsString()) && property.has("signature")
                    && !property.get("signature").getAsString().isBlank()) {
                    List<TextureProperty> textures = List.of(new TextureProperty("textures",
                        property.get("value").getAsString(), property.get("signature").getAsString()));
                    hasOfficialCape(textures); // Validate the signed payload's structure without modifying it.
                    cache.put(name.toLowerCase(Locale.ROOT), new CacheEntry(textures,
                        System.currentTimeMillis() + Duration.ofMinutes(cacheMinutes).toMillis()));
                    return textures;
                }
            }
            throw new IOException("Mojang returned no signed textures");
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    /** Reads the payload only. The exact original value and signature are passed to clients. */
    public static boolean hasOfficialCape(List<TextureProperty> properties) {
        if (properties == null || properties.size() != 1 || !"textures".equals(properties.getFirst().getName())
            || properties.getFirst().getSignature() == null || properties.getFirst().getSignature().isBlank())
            throw new IllegalArgumentException("A complete signed textures property is required");
        JsonObject payload = JsonParser.parseString(new String(
            Base64.getDecoder().decode(properties.getFirst().getValue()), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject textures = payload.getAsJsonObject("textures");
        if (textures == null || !textures.has("SKIN"))
            throw new IllegalArgumentException("Target profile has no signed SKIN texture");
        JsonObject skin = textures.getAsJsonObject("SKIN");
        if (skin == null || !skin.has("url") || skin.get("url").getAsString().isBlank())
            throw new IllegalArgumentException("Target profile has an invalid SKIN texture");
        JsonObject cape = textures.has("CAPE") ? textures.getAsJsonObject("CAPE") : null;
        return cape != null && cape.has("url") && !cape.get("url").getAsString().isBlank();
    }

    private JsonObject request(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds)).header("Accept", "application/json").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404 || response.statusCode() == 204)
            throw new IllegalArgumentException("Minecraft account not found");
        if (response.statusCode() == 429) {
            rateLimitedUntil = System.currentTimeMillis() + Duration.ofMinutes(1).toMillis();
            throw new IOException("Mojang rate limit reached");
        }
        if (response.statusCode() != 200) throw new IOException("Mojang HTTP " + response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    @Override public void close() { executor.shutdownNow(); }
}
