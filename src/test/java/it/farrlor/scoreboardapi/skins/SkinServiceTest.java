package it.farrlor.scoreboardapi.skins;

import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkinServiceTest {
    private TextureProperty property(String json) {
        return new TextureProperty("textures", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)),
            "test-signature");
    }

    @Test void detectsCapeWithoutChangingSignedPayload() {
        TextureProperty property = property("""
            {"textures":{"SKIN":{"url":"https://textures.minecraft.net/skin"},
                          "CAPE":{"url":"https://textures.minecraft.net/cape"}}}
            """);
        String value = property.getValue();
        String signature = property.getSignature();
        assertTrue(SkinService.hasOfficialCape(List.of(property)));
        assertEquals(value, property.getValue());
        assertEquals(signature, property.getSignature());
    }

    @Test void acceptsAccountWithoutCape() {
        TextureProperty property = property("{" +
            "\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/skin\"}}}");
        assertFalse(SkinService.hasOfficialCape(List.of(property)));
    }

    @Test void rejectsIncompleteOrUnsignedPayload() {
        assertThrows(IllegalArgumentException.class, () -> SkinService.hasOfficialCape(
            List.of(new TextureProperty("textures", "e30=", ""))));
        assertThrows(IllegalArgumentException.class, () -> SkinService.hasOfficialCape(
            List.of(property("{\"textures\":{\"CAPE\":{\"url\":\"cape\"}}}"))));
    }
}
