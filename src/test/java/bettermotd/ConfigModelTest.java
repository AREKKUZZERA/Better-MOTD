package bettermotd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigModelTest {

    @Test
    void loadsMaintenancePlaceholderApiHoverIconsAndConditions() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("activeProfile", "default");
        cfg.set("placeholderAPI.enabled", true);
        cfg.set("maintenance.enabled", true);
        cfg.set("maintenance.profile", "maintenance");
        cfg.set("profiles.default.playerCount.hoverLines", java.util.List.of("A", "B"));
        cfg.set(
                "profiles.default.presets",
                java.util.List.of(java.util.Map.of(
                        "id",
                        "main",
                        "icons",
                        java.util.List.of("one.png", "two.png"),
                        "conditions",
                        java.util.Map.of("hostnameContains", java.util.List.of("play"), "minOnline", 5),
                        "motd",
                        java.util.List.of("line1", "line2"))));
        cfg.set(
                "profiles.maintenance.presets",
                java.util.List.of(java.util.Map.of("id", "down", "motd", java.util.List.of("down", "later"))));

        ConfigModel model =
                ConfigModel.load(cfg, null, Logger.getLogger("test")).config();
        Profile profile = model.profiles().get("default");
        Preset preset = profile.presets().getFirst();

        assertTrue(model.placeholderApi().enabled());
        assertTrue(model.maintenance().enabled());
        assertEquals("maintenance", model.maintenance().profile());
        assertEquals(java.util.List.of("A", "B"), profile.playerCount().hoverLines());
        assertEquals(java.util.List.of("icons/one.png", "icons/two.png"), preset.icons());
        assertTrue(preset.conditions().matches(new RequestInfo("127.0.0.1", "play.example.net", 767, 10, 100)));
        assertFalse(preset.conditions().matches(new RequestInfo("127.0.0.1", "build.example.net", 767, 10, 100)));
        assertFalse(preset.conditions().matches(new RequestInfo("127.0.0.1", "play.example.net", 767, 1, 100)));
    }
}
