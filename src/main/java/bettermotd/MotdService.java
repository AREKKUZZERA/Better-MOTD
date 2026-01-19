package bettermotd;

import org.bukkit.Bukkit;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public final class MotdService {

    private final JavaPlugin plugin;
    private final ActiveProfileStore profileStore;
    private final IconCache iconCache;
    private final TextFormatService textFormatService;
    private final PaperPingAdapter paperAdapter;
    private final PlayerCountService playerCountService;

    private final Map<String, Map<String, StickyEntry>> stickyPerProfile = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> rotateCounters = new ConcurrentHashMap<>();
    private final Set<String> formatWarnings = ConcurrentHashMap.newKeySet();

    private volatile ConfigModel config = ConfigModel.empty();
    private volatile String activeProfileId = "default";

    public MotdService(JavaPlugin plugin, ActiveProfileStore profileStore) {
        this.plugin = plugin;
        this.profileStore = profileStore;
        this.iconCache = new IconCache(plugin);
        this.textFormatService = new TextFormatService();
        this.paperAdapter = new PaperPingAdapter(plugin.getLogger());
        this.playerCountService = new PlayerCountService(plugin.getLogger());
    }

    public ReloadResult reload() {
        try {
            ConfigModel.LoadResult result = ConfigModel.load(plugin.getConfig(), plugin.getDataFolder(),
                    plugin.getLogger());
            this.config = result.config();
            String desiredActive = profileStore.load(config.activeProfile(), plugin.getLogger());
            this.activeProfileId = resolveActiveProfile(desiredActive, config);

            iconCache.reload(collectIconPaths(config));
            stickyPerProfile.clear();
            rotateCounters.clear();
            formatWarnings.clear();

            logSummary(result);
            if (config.debugSelfTest()) {
                runFormatSelfTest();
            }
            return new ReloadResult(true, result.warnings());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to reload BetterMOTD: " + e.getMessage());
            return new ReloadResult(false, 1);
        }
    }

    public void shutdown() {
        stickyPerProfile.clear();
        iconCache.clear();
    }

    public boolean setActiveProfile(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return false;
        }
        if (!config.profiles().containsKey(profileId)) {
            return false;
        }
        activeProfileId = profileId;
        profileStore.save(profileId, plugin.getLogger());
        return true;
    }

    public String getActiveProfileId() {
        return activeProfileId;
    }

    public Set<String> getProfileIds() {
        return config.profiles().keySet();
    }

    public List<String> getPresetIds(String profileId) {
        Profile profile = resolveProfile(profileId);
        if (profile == null || profile.presets() == null) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (Preset preset : profile.presets()) {
            ids.add(preset.id());
        }
        return ids;
    }

    @SuppressWarnings("deprecation")
    public void apply(ServerListPingEvent event) {
        if (event == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            RequestContext ctx = new RequestContext(asIp(event.getAddress()), now);
            Profile profile = resolveProfile(activeProfileId);
            SelectionResult selection = selectPreset(profile, ctx, true);

            PlayerCountService.PlayerCountResult counts = playerCountService.compute(profile, ctx.ip(),
                    event.getNumPlayers(), event.getMaxPlayers(), now);
            String motdRaw = buildMotd(profile, selection, counts, ctx);
            TextFormatService.ParseResult parsed = textFormatService.parseToComponentDetailed(motdRaw,
                    config.colorFormat());
            warnIfFallback(profile, selection.preset(), parsed);

            boolean usedPaper = paperAdapter.applyMotd(event, parsed.component());
            if (!usedPaper) {
                event.setMotd(textFormatService.serializeToLegacy(parsed.component()));
            }

            playerCountService.apply(event, counts, paperAdapter);

            try {
                event.setServerIcon(iconCache.pickIcon(selection.preset()));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to set server icon: " + e.getMessage());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("BetterMOTD ping handling failed: " + e.getMessage());
        }
    }

    public PreviewResult preview(String idOrPreset, InetAddress address) {
        if (idOrPreset == null || idOrPreset.isBlank()) {
            return null;
        }
        String id = idOrPreset.trim();
        long now = System.currentTimeMillis();
        RequestContext ctx = new RequestContext(asIp(address), now);

        Profile profile = config.profiles().get(id);
        boolean fromProfile = true;
        SelectionResult selection;
        String reason;

        if (profile != null) {
            selection = selectPreset(profile, ctx, false);
            reason = selection.reason();
        } else {
            profile = resolveProfile(activeProfileId);
            Preset preset = findPreset(profile, id);
            if (preset == null) {
                return null;
            }
            selection = new SelectionResult(preset, null, "manual preset selection");
            reason = "manual preset selection";
            fromProfile = false;
        }

        PlayerCountService.PlayerCountResult counts = playerCountService.compute(profile, ctx.ip(),
                Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(), now);
        String motdRaw = buildMotd(profile, selection, counts, ctx);
        TextFormatService.ParseResult parsed = textFormatService.parseToComponentDetailed(motdRaw,
                config.colorFormat());
        warnIfFallback(profile, selection.preset(), parsed);
        List<String> lines = splitMotd(motdRaw);
        List<String> legacyLines = splitMotd(textFormatService.serializeToLegacy(parsed.component()));
        String icon = selection.preset().icon();
        String resolvedIcon = icon == null || icon.isBlank() ? "(none)" : icon;

        return new PreviewResult(
                profile.id(),
                selection.preset().id(),
                fromProfile,
                reason,
                lines,
                legacyLines,
                config.colorFormat(),
                parsed.usedFormat(),
                resolvedIcon,
                counts);
    }

    private SelectionResult selectPreset(Profile profile, RequestContext ctx, boolean count) {
        List<Preset> presets = profile.presets();
        if (presets == null || presets.isEmpty()) {
            presets = List.of(Preset.fallback(config.fallbackIconPath()));
        }

        ConfigModel.SelectionMode mode = profile.selectionMode();
        long now = ctx.nowMs();
        long ttlMs = Math.max(1, profile.stickyTtlSeconds()) * 1000L;
        String ip = ctx.ip();
        boolean perIpFrames = profile.animation().mode() == ConfigModel.AnimationMode.PER_IP_STICKY;

        StickyEntry entry = getStickyEntry(profile.id(), ip, now, ttlMs);
        Preset chosen;
        String reason;

        if (mode == ConfigModel.SelectionMode.STICKY_PER_IP && ip != null) {
            if (entry != null) {
                chosen = entry.preset();
                reason = "STICKY_PER_IP (sticky hit)";
            } else {
                chosen = weightedRandom(presets, Objects.hash(ip, now));
                entry = createStickyEntry(profile.id(), ip, chosen, now, perIpFrames);
                reason = "STICKY_PER_IP (new sticky, weighted random)";
            }
        } else if (mode == ConfigModel.SelectionMode.HASHED_PER_IP) {
            chosen = hashedPreset(presets, ip);
            reason = "HASHED_PER_IP (ip hash)";
            if (perIpFrames && ip != null) {
                entry = updateStickyEntry(profile.id(), ip, entry, chosen, now, ttlMs, true);
            }
        } else if (mode == ConfigModel.SelectionMode.ROTATE) {
            chosen = rotatePreset(profile.id(), presets);
            reason = "ROTATE (counter)";
            if (perIpFrames && ip != null) {
                entry = updateStickyEntry(profile.id(), ip, entry, chosen, now, ttlMs, true);
            }
        } else {
            int totalWeight = presets.stream().mapToInt(p -> Math.max(1, p.weight())).sum();
            chosen = weightedRandom(presets, ThreadLocalRandom.current().nextLong());
            reason = "RANDOM (weighted total=" + totalWeight + ")";
            if (perIpFrames && ip != null) {
                entry = updateStickyEntry(profile.id(), ip, entry, chosen, now, ttlMs, true);
            }
        }

        return new SelectionResult(chosen, entry, reason);
    }

    private StickyEntry updateStickyEntry(String profileId, String ip, StickyEntry existing, Preset preset, long now,
            long ttlMs, boolean ensureFrameSeed) {
        if (ip == null) {
            return null;
        }
        if (existing != null && isStickyValid(existing, now, ttlMs)) {
            int frameSeed = existing.frameSeed();
            StickyEntry updated = new StickyEntry(preset, existing.createdAtMs(), frameSeed);
            stickyMap(profileId).put(ip, updated);
            return updated;
        }

        return createStickyEntry(profileId, ip, preset, now, ensureFrameSeed);
    }

    private StickyEntry createStickyEntry(String profileId, String ip, Preset preset, long now, boolean ensureFrameSeed) {
        if (ip == null) {
            return null;
        }
        int frameSeed = ensureFrameSeed ? computeFrameSeed(profileId, now) : 0;
        StickyEntry fresh = new StickyEntry(preset, now, frameSeed);
        stickyMap(profileId).put(ip, fresh);
        return fresh;
    }

    private StickyEntry getStickyEntry(String profileId, String ip, long now, long ttlMs) {
        if (ip == null) {
            return null;
        }
        StickyEntry existing = stickyMap(profileId).get(ip);
        if (existing == null) {
            return null;
        }
        if (!isStickyValid(existing, now, ttlMs)) {
            stickyMap(profileId).remove(ip, existing);
            return null;
        }
        return existing;
    }

    private boolean isStickyValid(StickyEntry entry, long now, long ttlMs) {
        return entry != null && (now - entry.createdAtMs()) <= ttlMs;
    }

    private Map<String, StickyEntry> stickyMap(String profileId) {
        return stickyPerProfile.computeIfAbsent(profileId, key -> new ConcurrentHashMap<>());
    }

    private Preset hashedPreset(List<Preset> presets, String ip) {
        if (presets.isEmpty()) {
            return Preset.fallback(config.fallbackIconPath());
        }
        if (ip == null) {
            int idx = (int) Math.floorMod(System.nanoTime(), presets.size());
            return presets.get(idx);
        }
        int idx = Math.floorMod(ip.hashCode(), presets.size());
        return presets.get(idx);
    }

    private Preset rotatePreset(String profileId, List<Preset> presets) {
        if (presets.isEmpty()) {
            return Preset.fallback(config.fallbackIconPath());
        }
        AtomicInteger counter = rotateCounters.computeIfAbsent(profileId, key -> new AtomicInteger());
        int idx = Math.floorMod(counter.getAndIncrement(), presets.size());
        return presets.get(idx);
    }

    private Preset weightedRandom(List<Preset> presets, long seed) {
        int total = 0;
        for (Preset p : presets) {
            total += Math.max(1, p.weight());
        }

        int r = (int) Math.floorMod(seed, total);

        int acc = 0;
        for (Preset p : presets) {
            acc += Math.max(1, p.weight());
            if (r < acc) {
                return p;
            }
        }
        return presets.get(0);
    }

    private String buildMotd(Profile profile, SelectionResult selection, PlayerCountService.PlayerCountResult counts,
            RequestContext ctx) {
        String frame = pickMotdFrame(profile, selection, ctx);
        return applyPlaceholders(frame, selection.preset(), counts, profile);
    }

    private String pickMotdFrame(Profile profile, SelectionResult selection, RequestContext ctx) {
        Preset preset = selection.preset();
        boolean anim = profile.animation().enabled();

        List<String> frames = preset.motdFrames();
        if (anim && frames != null && !frames.isEmpty()) {
            int idx = resolveFrameIndex(profile, selection, ctx, frames.size());
            return frames.get(idx);
        }

        List<String> lines = preset.motd();
        if (lines == null || lines.isEmpty()) {
            lines = ConfigModel.FALLBACK_MOTD_LINES;
        }

        if (lines.size() == 1) {
            return lines.get(0);
        }
        return lines.get(0) + "\n" + lines.get(1);
    }

    private int resolveFrameIndex(Profile profile, SelectionResult selection, RequestContext ctx, int size) {
        if (size <= 0) {
            return 0;
        }
        if (profile.animation().mode() == ConfigModel.AnimationMode.PER_IP_STICKY && ctx.ip() != null) {
            StickyEntry entry = selection.stickyEntry();
            if (entry != null) {
                return Math.floorMod(entry.frameSeed(), size);
            }
        }
        long interval = profile.animation().frameIntervalMillis();
        return (int) ((ctx.nowMs() / interval) % size);
    }

    private int computeFrameSeed(String profileId, long nowMs) {
        Profile profile = resolveProfile(profileId);
        long interval = profile.animation().frameIntervalMillis();
        return (int) (nowMs / interval);
    }

    private String applyPlaceholders(String input, Preset preset, PlayerCountService.PlayerCountResult counts,
            Profile profile) {
        if (!config.placeholdersEnabled() || input == null || input.indexOf('%') < 0) {
            return input;
        }

        String online = counts.hidePlayerCount() ? "???" : String.valueOf(counts.displayOnline());
        String max = counts.hidePlayerCount() ? "???" : String.valueOf(counts.displayMax());
        String version = Bukkit.getMinecraftVersion();

        String output = input;
        output = output.replace("%online%", online);
        output = output.replace("%max%", max);
        output = output.replace("%version%", version);
        output = output.replace("%preset%", preset.id());
        output = output.replace("%profile%", profile.id());
        return output;
    }

    private String asIp(InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }

    private Profile resolveProfile(String profileId) {
        Profile profile = config.profiles().get(profileId);
        if (profile != null) {
            return profile;
        }
        if (!config.profiles().isEmpty()) {
            return config.profiles().values().iterator().next();
        }
        return new Profile(
                "default",
                ConfigModel.SelectionMode.STICKY_PER_IP,
                10,
                new Profile.AnimationSettings(true, ConfigModel.DEFAULT_FRAME_INTERVAL_MILLIS,
                        ConfigModel.AnimationMode.GLOBAL),
                new Profile.PlayerCountSettings(false, false,
                        new Profile.FakePlayersSettings(false, Profile.FakePlayersMode.STATIC, 0, 0, 0.0),
                        new Profile.JustXMoreSettings(false, 0),
                        new Profile.MaxPlayersSettings(false, 0)),
                List.of(Preset.fallback(config.fallbackIconPath())));
    }

    private String resolveActiveProfile(String desired, ConfigModel config) {
        if (desired != null && config.profiles().containsKey(desired)) {
            return desired;
        }
        return config.activeProfile();
    }

    private Preset findPreset(Profile profile, String presetId) {
        for (Preset preset : profile.presets()) {
            if (preset.id().equalsIgnoreCase(presetId)) {
                return preset;
            }
        }
        return null;
    }

    private List<String> splitMotd(String motd) {
        if (motd == null) {
            return Collections.emptyList();
        }
        String[] parts = motd.split("\n", 2);
        List<String> lines = new ArrayList<>();
        Collections.addAll(lines, parts);
        return lines;
    }

    private Collection<String> collectIconPaths(ConfigModel config) {
        Set<String> paths = ConcurrentHashMap.newKeySet();
        paths.add("icons/default.png");
        if (config.fallbackIconPath() != null) {
            paths.add(config.fallbackIconPath());
        }
        for (Profile profile : config.profiles().values()) {
            for (Preset preset : profile.presets()) {
                if (preset.icon() != null && !preset.icon().isBlank()) {
                    paths.add(preset.icon());
                }
            }
        }
        return paths;
    }

    private void logSummary(ConfigModel.LoadResult result) {
        StringBuilder summary = new StringBuilder("Validation summary: activeProfile=");
        summary.append(activeProfileId);
        summary.append(", profiles=").append(result.config().profiles().size());
        summary.append(", presets=");
        summary.append(result.presetCounts());
        summary.append(", fallbackProfiles=").append(result.fallbackProfiles());
        plugin.getLogger().info(summary.toString());
    }

    private void warnIfFallback(Profile profile, Preset preset, TextFormatService.ParseResult result) {
        if (result == null || !result.fallbackUsed()) {
            return;
        }
        String key = profile.id() + ":" + preset.id() + ":" + result.usedFormat();
        if (formatWarnings.add(key)) {
            plugin.getLogger().warning(
                    "Formatting failed for profile '" + profile.id() + "', preset '" + preset.id()
                            + "' using " + result.usedFormat() + ". Using plain text fallback.");
        }
    }

    private void runFormatSelfTest() {
        List<String> samples = List.of(
                "<gradient:#00D431:#00BF4B>TEXT</gradient>",
                "&#00D431M&#00D332O&#00D233T&#00CF34D",
                "{\"text\":\"\",\"extra\":[{\"text\":\"M\",\"color\":\"#00D431\"}]}",
                "§x§0§0§D§4§3§1MOTD",
                "&x&0&0&D&4&3&1MOTD");
        for (String sample : samples) {
            try {
                TextFormatService.ParseResult parsed = textFormatService.parseToComponentDetailed(sample,
                        ColorFormat.AUTO);
                if (parsed.fallbackUsed()) {
                    plugin.getLogger().warning("Self-test fallback used for sample: " + sample);
                }
                if (parsed.component().equals(Component.empty())) {
                    plugin.getLogger().warning("Self-test produced empty component for sample: " + sample);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Self-test failed for sample: " + sample + " (" + e.getMessage() + ")");
            }
        }
    }

    private record StickyEntry(Preset preset, long createdAtMs, int frameSeed) {
    }

    private record SelectionResult(Preset preset, StickyEntry stickyEntry, String reason) {
    }

    public record ReloadResult(boolean success, int warnings) {
    }

    public record PreviewResult(
            String profileId,
            String presetId,
            boolean fromProfile,
            String reason,
            List<String> motdLines,
            List<String> legacyLines,
            ColorFormat configuredFormat,
            ColorFormat usedFormat,
            String iconPath,
            PlayerCountService.PlayerCountResult playerCounts) {
    }

    private record RequestContext(String ip, long nowMs) {
    }
}
