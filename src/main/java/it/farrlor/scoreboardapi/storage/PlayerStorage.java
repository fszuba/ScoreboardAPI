package it.farrlor.scoreboardapi.storage;

import it.farrlor.scoreboardapi.api.IdentitySettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerStorage {
    public record SavedDisguise(String accountName, String textureValue, String textureSignature,
                                boolean hasOfficialCape) {}
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, SavedDisguise> disguises = new HashMap<>();

    public PlayerStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
        disguises.putAll(readDisguises());
    }

    public Map<UUID, SavedDisguise> loadDisguises() { return Map.copyOf(disguises); }

    public void clearDisguises() { disguises.clear(); }

    public void setDisguise(UUID id, SavedDisguise value) {
        if (value == null) disguises.remove(id); else disguises.put(id, value);
    }

    private Map<UUID, SavedDisguise> readDisguises() {
        Map<UUID, SavedDisguise> result = new HashMap<>();
        if (!file.exists()) return result;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection section = yaml.getConfigurationSection(key + ".disguise");
                if (section == null) continue;
                String name = section.getString("account");
                if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) continue;
                result.put(id, new SavedDisguise(name, section.getString("texture-value"),
                    section.getString("texture-signature"), section.getBoolean("cape", false)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid disguise UUID in players.yml: " + key);
            }
        }
        return result;
    }

    public Map<UUID, IdentitySettings> load() {
        Map<UUID, IdentitySettings> result = new HashMap<>();
        if (!file.exists()) return result;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection section = yaml.getConfigurationSection(key);
                if (section == null) continue;
                result.put(id, new IdentitySettings(section.getString("nick"), section.getString("skin"),
                    section.getString("chat"), section.getString("prefix"), section.getString("suffix")));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid UUID in players.yml: " + key);
            }
        }
        return result;
    }

    public void save(Map<UUID, IdentitySettings> players) {
        boolean persistSettings = plugin.getConfig().getBoolean("settings.persist-player-settings", true);
        boolean persistDisguises = plugin.getConfig().getBoolean("disguise.persist", true);
        if (!persistSettings && !persistDisguises) return;
        YamlConfiguration yaml = new YamlConfiguration();
        if (persistSettings) {
            players.forEach((id, settings) -> {
                if (settings.isEmpty()) return;
                String path = id.toString() + ".";
                yaml.set(path + "nick", settings.nick());
                yaml.set(path + "skin", settings.skin());
                yaml.set(path + "chat", settings.chat());
                yaml.set(path + "prefix", settings.prefix());
                yaml.set(path + "suffix", settings.suffix());
            });
        }
        if (persistDisguises) {
            disguises.forEach((id, disguise) -> {
                String path = id.toString() + ".disguise.";
                yaml.set(path + "account", disguise.accountName());
                yaml.set(path + "texture-value", disguise.textureValue());
                yaml.set(path + "texture-signature", disguise.textureSignature());
                yaml.set(path + "cape", disguise.hasOfficialCape());
            });
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Cannot save players.yml: " + exception.getMessage());
        }
    }
}
