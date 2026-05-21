package bettermotd;

import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class MotdService {

    private static final int STICKY_CLEANUP_BATCH = 200;
    private static final int STICKY_EVICTION_BATCH = 200;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();
    private static final String[] PLACEHOLDER_TOKENS = {
        "%online%", "%max%", "%version%", "%preset%", "%profile%", "%motd_frame%", "%time%"
    };
    private final JavaPlugin plugin;
    private final ActiveProfileStore profileStore;
    private final IconCache iconCache;
    private final TextFormatService textFormatService;
    private final PlaceholderService placeholderService;
    private final PaperPingAdapter paperAdapter;
    private final PlayerCountService playerCountService;

    private final Map<String, StickyProfileState> stickyStates = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> rotateCounters = new ConcurrentHashMap<>();
    private final Set<String> formatWarnings = ConcurrentHashMap.newKeySet();
    private final Map<String, PresetCache> presetCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> presetWeightTotals = new ConcurrentHashMap<>();
    private final Map<String, HoverCache> hoverCache = new ConcurrentHashMap<>();
    private final AtomicBoolean warnedHoverUnsupported = new AtomicBoolean();

    private volatile ConfigModel config = ConfigModel.empty();
    private volatile String activeProfileId = "default";

    public MotdService(JavaPlugin plugin, ActiveProfileStore profileStore) {
        this.plugin = plugin;
        this.profileStore = profileStore;
        this.iconCache = new IconCache(plugin);
        this.textFormatService = new TextFormatService();
        this.placeholderService = new PlaceholderService(plugin.getLogger());
        this.paperAdapter = new PaperPingAdapter(plugin.getLogger());
        this.playerCountService = new PlayerCountService(plugin.getLogger());
    }

    public ReloadResult reload() {
        try {
            ConfigModel.LoadResult result =
                    ConfigModel.load(plugin.getConfig(), plugin.getDataFolder(), plugin.getLogger());
            this.config = result.config();
            String desiredActive = profileStore.load(config.activeProfile(), plugin.getLogger());
            this.activeProfileId = resolveActiveProfile(desiredActive, config);

            iconCache.reload(collectIconPaths(config));
            formatWarnings.clear();
            rebuildPresetCache();
            rebuildHoverCache();
            stickyStates.clear();
            rotateCounters.clear();

            logSummary(result);
            if (config.debugSelfTest()) {
                runFormatSelfTest();
            }
            return new ReloadResult(true, result.warnings());
        } catch (Exception e) {
            logException(Level.SEVERE, "Failed to reload BetterMOTD.", e);
            return new ReloadResult(false, 1);
        }
    }

    public void shutdown() {
        stickyStates.clear();
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

    public void apply(ServerListPingEvent event) {
        if (event == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            RequestContext ctx = new RequestContext(requestInfo(event), now);
            Profile profile = resolveProfile(resolvePingProfileId());
            applySelection(event, ctx, profile);
        } catch (Exception e) {
            logException(
                    Level.WARNING,
                    "BetterMOTD ping handling failed (profile=" + activeProfileId + ", ip=" + ctxString(event) + ").",
                    e);
        }
    }

    public PreviewResult preview(String idOrPreset, InetAddress address) {
        if (idOrPreset == null || idOrPreset.isBlank()) {
            return null;
        }
        String id = idOrPreset.trim();
        long now = System.currentTimeMillis();
        RequestContext ctx = new RequestContext(
                RequestInfo.preview(asIp(address), Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers()), now);

        Profile profile = config.profiles().get(id);
        boolean fromProfile = true;
        SelectionResult selection;
        String reason;

        if (profile != null) {
            selection = selectPreset(profile, ctx, true);
            reason = selection.reason();
        } else {
            profile = resolveProfile(activeProfileId);
            Preset preset = findPreset(profile, id);
            if (preset == null) {
                return null;
            }
            selection = new SelectionResult(preset, "manual preset selection");
            reason = "manual preset selection";
            fromProfile = false;
        }

        PlayerCountService.PlayerCountResult counts =
                playerCountService.compute(profile, Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers());
        MotdRenderResult render = renderMotd(profile, selection, counts, ctx);
        String motdRaw = render.raw();
        TextFormatService.ParseResult parsed = render.parsed();
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

    @SuppressWarnings("deprecation")
    private static void setLegacyMotd(ServerListPingEvent event, String motd) {
        if (event == null) return;
        event.setMotd(motd);
    }

    private void applySelection(ServerListPingEvent event, RequestContext ctx, Profile profile) {
        SelectionResult selection = selectPreset(profile, ctx, false);
        PlayerCountService.PlayerCountResult counts =
                playerCountService.compute(profile, event.getNumPlayers(), event.getMaxPlayers());
        MotdRenderResult render = renderMotd(profile, selection, counts, ctx);
        TextFormatService.ParseResult parsed = render.parsed();
        warnIfFallback(profile, selection.preset(), parsed);

        boolean usedPaper = paperAdapter.applyMotd(event, parsed.component());
        if (!usedPaper) {
            setLegacyMotd(event, textFormatService.serializeToLegacy(parsed.component()));
        }

        playerCountService.apply(event, counts, paperAdapter);
        applyHover(event, profile, counts, ctx);

        try {
            event.setServerIcon(iconCache.pickIcon(selection.preset()));
        } catch (Exception e) {
            logException(
                    Level.WARNING,
                    "Failed to set server icon for profile '" + profile.id() + "', preset '"
                            + selection.preset().id() + "', icon '"
                            + selection.preset().icon() + "'.",
                    e);
        }
    }

    private SelectionResult selectPreset(Profile profile, RequestContext ctx, boolean includeReason) {
        List<Preset> presets = profile.presets();
        if (presets == null || presets.isEmpty()) {
            presets = List.of(Preset.fallback(config.fallbackIconPath()));
        }
        List<Preset> profilePresets = presets;
        presets = filterByConditions(presets, ctx.request());
        int totalWeight = presets == profilePresets ? presetWeightTotal(profile.id(), presets) : totalWeight(presets);

        ConfigModel.SelectionMode mode = profile.selectionMode();
        long now = ctx.nowMs();
        long ttlMs = Math.max(1, profile.stickyTtlSeconds()) * 1000L;
        String ip = ctx.request().ip();

        if (ip != null) {
            runStickyMaintenance(profile, now, ttlMs);
        }

        StickyEntry entry = getStickyEntry(profile.id(), ip, now, ttlMs);
        Preset chosen;
        String reason;

        if (mode == ConfigModel.SelectionMode.STICKY_PER_IP && ip != null) {
            if (entry != null) {
                chosen = entry.preset();
                reason = includeReason ? "STICKY_PER_IP (sticky hit)" : null;
            } else {
                chosen = weightedRandom(presets, totalWeight, Objects.hash(ip, now));
                entry = createStickyEntry(profile.id(), ip, chosen, now);
                reason = includeReason ? "STICKY_PER_IP (new sticky, weighted random)" : null;
            }
        } else if (mode == ConfigModel.SelectionMode.HASHED_PER_IP) {
            chosen = hashedPreset(presets, ip);
            reason = includeReason ? "HASHED_PER_IP (ip hash)" : null;
        } else if (mode == ConfigModel.SelectionMode.ROTATE) {
            chosen = rotatePreset(profile.id(), presets);
            reason = includeReason ? "ROTATE (counter)" : null;
        } else {
            chosen = weightedRandom(
                    presets, totalWeight, ThreadLocalRandom.current().nextLong());
            reason = includeReason ? "RANDOM (weighted total=" + totalWeight + ")" : null;
        }

        return new SelectionResult(chosen, reason);
    }

    private int presetWeightTotal(String profileId, List<Preset> presets) {
        return presetWeightTotals.computeIfAbsent(profileId, ignored -> totalWeight(presets));
    }

    private int totalWeight(List<Preset> presets) {
        int total = 0;
        for (Preset preset : presets) {
            total += Math.max(1, preset.weight());
        }
        return total;
    }

    private StickyEntry createStickyEntry(String profileId, String ip, Preset preset, long now) {
        if (ip == null) {
            return null;
        }
        StickyEntry fresh = new StickyEntry(preset, now);
        StickyProfileState state = stickyState(profileId);
        StickyEntry previous = state.entries().put(ip, fresh);
        if (previous == null) {
            state.order().addLast(ip);
        }
        return fresh;
    }

    private StickyEntry getStickyEntry(String profileId, String ip, long now, long ttlMs) {
        if (ip == null) {
            return null;
        }
        StickyEntry existing = stickyState(profileId).entries().get(ip);
        if (existing == null) {
            return null;
        }
        if (!isStickyValid(existing, now, ttlMs)) {
            stickyState(profileId).entries().remove(ip, existing);
            stickyState(profileId).order().removeIf(ip::equals);
            return null;
        }
        return existing;
    }

    private boolean isStickyValid(StickyEntry entry, long now, long ttlMs) {
        return entry != null && (now - entry.createdAtMs()) <= ttlMs;
    }

    private StickyProfileState stickyState(String profileId) {
        return stickyStates.computeIfAbsent(
                profileId,
                key -> new StickyProfileState(
                        new ConcurrentHashMap<>(), new ConcurrentLinkedDeque<>(), new AtomicInteger()));
    }

    private Preset hashedPreset(List<Preset> presets, String ip) {
        if (presets.isEmpty()) {
            return Preset.fallback(config.fallbackIconPath());
        }
        if (ip == null) {
            int idx = Math.floorMod(System.nanoTime(), presets.size());
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

    private Preset weightedRandom(List<Preset> presets, int totalWeight, long seed) {
        // Use int modulo explicitly to avoid long/int ambiguity
        int r = Math.floorMod((int) seed, totalWeight);
        int acc = 0;
        for (Preset p : presets) {
            acc += Math.max(1, p.weight());
            if (r < acc) {
                return p;
            }
        }
        return presets.get(0);
    }

    private MotdRenderResult renderMotd(
            Profile profile,
            SelectionResult selection,
            PlayerCountService.PlayerCountResult counts,
            RequestContext ctx) {
        FrameSelection frameSelection = selectFrame(profile, selection, ctx);
        return renderMotd(
                profile.id(), selection.preset(), frameSelection.frame(), counts, ctx, frameSelection.index());
    }

    private MotdRenderResult renderMotd(
            String profileId,
            Preset preset,
            CachedFrame frame,
            PlayerCountService.PlayerCountResult counts,
            RequestContext ctx,
            int frameIndex) {
        String raw = frame.raw();
        TextFormatService.ParseResult parsed;

        if (frame.hasPlaceholders() && config.placeholdersEnabled()) {
            // Dynamic path: substitute placeholders then parse fresh
            PlaceholderValues values = buildPlaceholderValues(preset.id(), profileId, counts, frameIndex, ctx);
            parsed = textFormatService.parseToComponentDetailed(applyPlaceholders(raw, values), config.colorFormat());
        } else if (frame.cachedComponent() != null) {
            // Fast path: use pre-parsed cached component
            parsed = new TextFormatService.ParseResult(
                    frame.cachedComponent(), frame.usedFormat(), frame.fallbackUsed());
        } else {
            // Fallback: parse raw (e.g. placeholders present but disabled, or cache miss)
            parsed = textFormatService.parseToComponentDetailed(raw, config.colorFormat());
        }

        return new MotdRenderResult(raw, parsed, frameIndex);
    }

    private FrameSelection selectFrame(Profile profile, SelectionResult selection, RequestContext ctx) {
        Preset preset = selection.preset();
        PresetCache cache = presetCache(profile.id(), preset);

        return new FrameSelection(cache.staticFrame(), 0);
    }

    private String applyPlaceholders(String input, PlaceholderValues values) {
        if (input == null || input.indexOf('%') < 0) {
            return input;
        }
        String[] replacements = {
            values.online(),
            values.max(),
            values.version(),
            values.preset(),
            values.profile(),
            values.motdFrame(),
            values.time()
        };

        StringBuilder out = new StringBuilder(input.length() + 32);
        int len = input.length();
        outer:
        for (int i = 0; i < len; i++) {
            if (input.charAt(i) == '%') {
                for (int t = 0; t < PLACEHOLDER_TOKENS.length; t++) {
                    String token = PLACEHOLDER_TOKENS[t];
                    if (input.regionMatches(i, token, 0, token.length())) {
                        out.append(replacements[t]);
                        i += token.length() - 1;
                        continue outer;
                    }
                }
            }
            out.append(input.charAt(i));
        }
        return placeholderService.apply(out.toString(), config.placeholderApi().enabled());
    }

    private void applyHover(
            ServerListPingEvent event,
            Profile profile,
            PlayerCountService.PlayerCountResult counts,
            RequestContext ctx) {
        List<String> lines = profile.playerCount().hoverLines();
        if (lines == null || lines.isEmpty() || counts.disableHover()) {
            return;
        }
        HoverCache cache = hoverCache(profile);
        List<String> rendered = cache.staticLines();
        if (rendered == null) {
            PlaceholderValues values = buildPlaceholderValues("hover", profile.id(), counts, 0, ctx);
            rendered = new ArrayList<>(lines.size());
            for (String line : lines) {
                String raw = applyPlaceholders(line, values);
                TextFormatService.ParseResult parsed =
                        textFormatService.parseToComponentDetailed(raw, config.colorFormat());
                rendered.add(textFormatService.serializeToLegacy(parsed.component()));
            }
        }
        if (!paperAdapter.applyHoverLines(event, rendered)) {
            if (warnedHoverUnsupported.compareAndSet(false, true)) {
                plugin.getLogger().warning("Custom hoverLines require Paper ping API.");
            }
        }
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
                10000,
                500,
                new Profile.PlayerCountSettings(
                        false,
                        false,
                        List.of(),
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
        List<String> lines = new ArrayList<>(2);
        if (parts.length >= 1) {
            lines.add(parts[0]);
        }
        if (parts.length >= 2) {
            lines.add(parts[1]);
        } else {
            lines.add("");
        }
        return lines;
    }

    private void rebuildPresetCache() {
        presetCache.clear();
        presetWeightTotals.clear();
        for (Profile profile : config.profiles().values()) {
            presetWeightTotals.put(profile.id(), totalWeight(profile.presets()));
            for (Preset preset : profile.presets()) {
                presetCache.put(presetCacheKey(profile.id(), preset.id()), buildPresetCache(profile, preset));
            }
        }
    }

    private PresetCache presetCache(String profileId, Preset preset) {
        String key = presetCacheKey(profileId, preset.id());
        return presetCache.computeIfAbsent(key, ignored -> buildPresetCache(resolveProfile(profileId), preset));
    }

    private String presetCacheKey(String profileId, String presetId) {
        return profileId + ":" + presetId;
    }

    private PresetCache buildPresetCache(Profile profile, Preset preset) {
        List<String> lines = preset.motd();
        if (lines == null || lines.isEmpty()) {
            lines = ConfigModel.FALLBACK_MOTD_LINES;
        }
        String raw = lines.size() > 1 ? lines.get(0) + "\n" + lines.get(1) : lines.get(0) + "\n";
        CachedFrame staticFrame = buildCachedFrame(raw, profile, preset);

        return new PresetCache(staticFrame);
    }

    private void rebuildHoverCache() {
        hoverCache.clear();
        for (Profile profile : config.profiles().values()) {
            hoverCache.put(profile.id(), buildHoverCache(profile));
        }
    }

    private HoverCache hoverCache(Profile profile) {
        return hoverCache.computeIfAbsent(profile.id(), ignored -> buildHoverCache(profile));
    }

    private HoverCache buildHoverCache(Profile profile) {
        List<String> lines = profile.playerCount().hoverLines();
        if (lines == null || lines.isEmpty()) {
            return new HoverCache(List.of());
        }
        for (String line : lines) {
            if (hasPlaceholders(line)) {
                return new HoverCache(null);
            }
        }
        List<String> rendered = new ArrayList<>(lines.size());
        for (String line : lines) {
            TextFormatService.ParseResult parsed =
                    textFormatService.parseToComponentDetailed(line, config.colorFormat());
            rendered.add(textFormatService.serializeToLegacy(parsed.component()));
        }
        return new HoverCache(List.copyOf(rendered));
    }

    private CachedFrame buildCachedFrame(String raw, Profile profile, Preset preset) {
        boolean hasPlaceholders = hasPlaceholders(raw);
        if (!hasPlaceholders) {
            TextFormatService.ParseResult parsed =
                    textFormatService.parseToComponentDetailed(raw, config.colorFormat());
            warnIfFallback(profile, preset, parsed);
            return new CachedFrame(raw, false, parsed.component(), parsed.usedFormat(), parsed.fallbackUsed());
        }
        return new CachedFrame(raw, true, null, config.colorFormat(), false);
    }

    private boolean hasPlaceholders(String input) {
        if (input == null || input.indexOf('%') < 0) {
            return false;
        }
        if (config.placeholderApi().enabled()) {
            return true;
        }
        for (String token : PLACEHOLDER_TOKENS) {
            if (input.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private PlaceholderValues buildPlaceholderValues(
            String presetId,
            String profileId,
            PlayerCountService.PlayerCountResult counts,
            int frameIndex,
            RequestContext ctx) {
        String online = counts.hidePlayerCount() ? "???" : String.valueOf(counts.displayOnline());
        String max = counts.hidePlayerCount() ? "???" : String.valueOf(counts.displayMax());
        String version = Bukkit.getMinecraftVersion();
        String time = LocalTime.ofInstant(Instant.ofEpochMilli(ctx.nowMs()), SYSTEM_ZONE)
                .format(TIME_FORMAT);
        return new PlaceholderValues(online, max, version, presetId, profileId, String.valueOf(frameIndex), time);
    }

    private Collection<String> collectIconPaths(ConfigModel config) {
        Set<String> paths = new java.util.HashSet<>();
        paths.add("icons/default.png");
        if (config.fallbackIconPath() != null) {
            paths.add(config.fallbackIconPath());
        }
        for (Profile profile : config.profiles().values()) {
            for (Preset preset : profile.presets()) {
                if (preset.icon() != null && !preset.icon().isBlank()) {
                    paths.add(preset.icon());
                }
                paths.addAll(preset.icons());
            }
        }
        return paths;
    }

    private List<Preset> filterByConditions(List<Preset> presets, RequestInfo request) {
        List<Preset> matching = new ArrayList<>();
        for (Preset preset : presets) {
            if (preset.conditions().matches(request)) {
                matching.add(preset);
            }
        }
        if (matching.size() == presets.size()) {
            return presets;
        }
        return matching.isEmpty() ? presets : matching;
    }

    private String resolvePingProfileId() {
        if (config.maintenance().enabled()
                && config.maintenance().profile() != null
                && config.profiles().containsKey(config.maintenance().profile())) {
            return config.maintenance().profile();
        }
        return activeProfileId;
    }

    public boolean maintenanceEnabled() {
        return config.maintenance().enabled();
    }

    public String maintenanceBypassPermission() {
        return config.maintenance().bypassPermission();
    }

    public String maintenanceKickMessage() {
        return config.maintenance().kickMessage();
    }

    private RequestInfo requestInfo(ServerListPingEvent event) {
        return new RequestInfo(
                asIp(event.getAddress()),
                event.getHostname(),
                paperAdapter.protocolVersion(event),
                event.getNumPlayers(),
                event.getMaxPlayers());
    }

    private void runStickyMaintenance(Profile profile, long nowMs, long ttlMs) {
        StickyProfileState state = stickyState(profile.id());
        int interval = profile.stickyCleanupEveryNPings();
        if (interval <= 0) {
            return;
        }
        int count = state.pingCounter().incrementAndGet();
        if (count % interval != 0) {
            return;
        }

        StickyStateSupport.cleanupExpired(
                state.entries(),
                nowMs,
                ttlMs,
                STICKY_CLEANUP_BATCH,
                (entry, threshold) -> entry != null && entry.createdAtMs() >= threshold);
        state.order().removeIf(ip -> !state.entries().containsKey(ip));

        enforceStickyLimit(profile, state);
    }

    private void enforceStickyLimit(Profile profile, StickyProfileState state) {
        int maxEntries = profile.stickyMaxEntriesPerProfile();
        StickyStateSupport.enforceLimit(state.entries(), state.order(), maxEntries, STICKY_EVICTION_BATCH);
    }

    public Diagnostics diagnostics() {
        Map<String, Integer> stickyByProfile = new ConcurrentHashMap<>();
        for (Map.Entry<String, StickyProfileState> entry : stickyStates.entrySet()) {
            stickyByProfile.put(entry.getKey(), entry.getValue().entries().size());
        }
        return new Diagnostics(
                activeProfileId, stickyByProfile, rotateCounters.size(), presetCache.size(), formatWarnings.size());
    }

    private String ctxString(ServerListPingEvent event) {
        InetAddress address = event != null ? event.getAddress() : null;
        return address != null ? address.getHostAddress() : "unknown";
    }

    private void logException(Level level, String message, Exception e) {
        if (config.debugVerbose()) {
            plugin.getLogger().log(level, message, e);
        } else {
            String suffix = e.getClass().getSimpleName();
            String detail = e.getMessage();
            plugin.getLogger().log(level, message + " (" + suffix + (detail == null ? "" : ": " + detail) + ")");
        }
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
            plugin.getLogger()
                    .warning("Formatting failed for profile '" + profile.id() + "', preset '" + preset.id() + "' using "
                            + result.usedFormat() + ". Using plain text fallback.");
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
                TextFormatService.ParseResult parsed =
                        textFormatService.parseToComponentDetailed(sample, ColorFormat.AUTO);
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

    private record StickyEntry(Preset preset, long createdAtMs) {}

    private record StickyProfileState(
            Map<String, StickyEntry> entries, Deque<String> order, AtomicInteger pingCounter) {}

    private record SelectionResult(Preset preset, String reason) {}

    public record ReloadResult(boolean success, int warnings) {}

    private record CachedFrame(
            String raw,
            boolean hasPlaceholders,
            Component cachedComponent,
            ColorFormat usedFormat,
            boolean fallbackUsed) {}

    private record PresetCache(CachedFrame staticFrame) {}

    private record HoverCache(List<String> staticLines) {}

    private record FrameSelection(CachedFrame frame, int index) {}

    private record MotdRenderResult(String raw, TextFormatService.ParseResult parsed, int frameIndex) {}

    private record PlaceholderValues(
            String online, String max, String version, String preset, String profile, String motdFrame, String time) {}

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
            PlayerCountService.PlayerCountResult playerCounts) {}

    public record Diagnostics(
            String activeProfile,
            Map<String, Integer> stickyEntriesByProfile,
            int rotateCounterProfiles,
            int presetCacheSize,
            int formatWarnings) {}

    private record RequestContext(RequestInfo request, long nowMs) {}
}
