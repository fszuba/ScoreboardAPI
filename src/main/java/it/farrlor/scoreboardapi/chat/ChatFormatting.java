package it.farrlor.scoreboardapi.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.chat.ChatRenderer;
import it.farrlor.scoreboardapi.api.IdentitySettings;
import it.farrlor.scoreboardapi.identity.IdentityManager;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChatFormatting implements Listener, ChatRenderer {
    private final JavaPlugin plugin;
    private final IdentityManager identities;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private volatile String format;
    private volatile boolean enabled;

    public ChatFormatting(JavaPlugin plugin, IdentityManager identities) {
        this.plugin = plugin;
        this.identities = identities;
        reload();
    }

    public void reload() {
        format = plugin.getConfig().getString("chat.format", "<gray>{name}: {message}</gray>");
        enabled = plugin.getConfig().getBoolean("chat.enabled", true);
    }

    @EventHandler public void onChat(AsyncChatEvent event) {
        if (enabled) event.renderer(this);
    }

    @Override public Component render(Player source, Component sourceDisplayName, Component message, Audience viewer) {
        IdentitySettings settings = identities.effectiveSettings(source.getUniqueId());
        String name = settings.chat() == null ? source.getName() : settings.chat();
        String template = format.replace("{prefix}", "<sba_prefix>")
            .replace("{suffix}", "<sba_suffix>").replace("{name}", "<sba_name>")
            .replace("{real_name}", "<sba_real_name>").replace("{message}", "<sba_message>");
        return miniMessage.deserialize(template,
            Placeholder.component("sba_prefix", Component.text(settings.prefix() == null ? "" : settings.prefix() + " ")),
            Placeholder.component("sba_suffix", Component.text(settings.suffix() == null ? "" : " " + settings.suffix())),
            Placeholder.component("sba_name", Component.text(name)),
            Placeholder.component("sba_real_name", Component.text(source.getName())),
            Placeholder.component("sba_message", message));
    }
}
