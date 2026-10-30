package it.farrlor.scoreboardapi.api;

/** Null means no override. All names and the skin are independent settings. */
public record IdentitySettings(String nick, String skin, String chat, String prefix, String suffix) {
    public static final IdentitySettings EMPTY = new IdentitySettings(null, null, null, null, null);

    public boolean isEmpty() {
        return nick == null && skin == null && chat == null && prefix == null && suffix == null;
    }

    public IdentitySettings withNick(String value) { return new IdentitySettings(value, skin, chat, prefix, suffix); }
    public IdentitySettings withSkin(String value) { return new IdentitySettings(nick, value, chat, prefix, suffix); }
    public IdentitySettings withChat(String value) { return new IdentitySettings(nick, skin, value, prefix, suffix); }
    public IdentitySettings withPrefix(String value) { return new IdentitySettings(nick, skin, chat, value, suffix); }
    public IdentitySettings withSuffix(String value) { return new IdentitySettings(nick, skin, chat, prefix, value); }
}
