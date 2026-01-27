package bettermotd;

import org.bukkit.Bukkit;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class MotdService {

    private static final int STICKY_CLEANUP_BATCH = 200;
    private static final int STICKY_EVICTION_BATCH = 200;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();
    private static final String[] SUPPORTED_PLACEHOLDERS = new String[] {
            "%online%",
            "%max%",
            "%version%",
            "%preset%",
            "%profile%",
            "%motd_frame%",
            "%time%"
    };

    private final JavaPlugin plugin;
    private final ActiveProfileStore profileStore;
    private final IconCache iconCache;
    private final TextFormatService textFormatService;
    private final PaperPingAdapter paperAdapter;
    private final PlayerCountService playerCountService;

    private final Map<String, StickyProfileState> stickyStates = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> rotateCounters = new ConcurrentHashMap<>();
    private final Set<String> formatWarnings = ConcurrentHashMap.newKeySet();
    private final Map<String, PresetCache> presetCache = new ConcurrentHashMap<>();
    private final AtomicBoolean routingWarned = new AtomicBoolean();
    private final AtomicBoolean whitelistProfileWarned = new AtomicBoolean();

    private volatile ConfigModel config = ConfigModel.empty();
    private volatile String activeProfileId = "default";
    private volatile CachedFrame whitelistFrame;

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
            formatWarnings.clear();
            whitelistProfileWarned.set(false);
            rebuildPresetCache();
            stickyStates.clear();
            rotateCounters.clear();
            whitelistFrame = buildWhitelistFrame(config.whitelist());

            logSummary(result);
            warnIfWhitelistProfileMissing();
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

    public ConfigModel.WhitelistSettings getWhitelistSettings() {
        return config.whitelist();
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
            RequestContext ctx = new RequestContext(asIp(event.getAddress()), now);
            ConfigModel.WhitelistSettings whitelistSettings = config.whitelist();
            if (whitelistSettings.enabled() && Bukkit.hasWhitelist()) {
                if (!whitelistSettings.whitelistMotdProfile().isBlank()) {
                    Profile forced = config.profiles().get(whitelistSettings.whitelistMotdProfile());
                    if (forced != null) {
                        applySelection(event, ctx, forced);
                        return;
                    }
                }
                if (whitelistSettings.mode() == ConfigModel.WhitelistMode.OFFLINE_FOR_NON_WHITELISTED) {
                    applyWhitelistOffline(event, ctx, whitelistSettings);
                    return;
                }
            }

            Profile profile = resolveProfile(resolveProfileIdForRouting(event));
            applySelection(event, ctx, profile);
        } catch (Exception e) {
            logException(Level.WARNING,
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
        if (event == null)
            return;
        event.setMotd(motd);
    }

    private void applySelection(ServerListPingEvent event, RequestContext ctx, Profile profile) {
        SelectionResult selection = selectPreset(profile, ctx, true);
        PlayerCountService.PlayerCountResult counts = playerCountService.compute(profile, ctx.ip(),
                event.getNumPlayers(), event.getMaxPlayers(), ctx.nowMs());
        MotdRenderResult render = renderMotd(profile, selection, counts, ctx);
        TextFormatService.ParseResult parsed = render.parsed();
        warnIfFallback(profile, selection.preset(), parsed);

        boolean usedPaper = paperAdapter.applyMotd(event, parsed.component());
        if (!usedPaper) {
            setLegacyMotd(event, textFormatService.serializeToLegacy(parsed.component()));
        }

        playerCountService.apply(event, counts, paperAdapter);

        try {
            event.setServerIcon(iconCache.pickIcon(selection.preset()));
        } catch (Exception e) {
            logException(Level.WARNING,
                    "Failed to set server icon for profile '" + profile.id() + "', preset '"
                            + selection.preset().id() + "', icon '" + selection.preset().icon() + "'.",
                    e);
        }
    }

    private void applyWhitelistOffline(ServerListPingEvent event, RequestContext ctx,
            ConfigModel.WhitelistSettings whitelistSettings) {
        CachedFrame frame = whitelistFrame != null ? whitelistFrame : buildWhitelistFrame(whitelistSettings);
        Preset preset = new Preset("whitelist", 1, whitelistSettings.iconPath(),
                whitelistSettings.motdLines(), List.of());
        PlayerCountService.PlayerCountResult counts = new PlayerCountService.PlayerCountResult(0, 0, 0, 0, 0,
                false, false);

        MotdRenderResult render = renderMotd("whitelist", preset, frame, counts, ctx, 0);
        TextFormatService.ParseResult parsed = render.parsed();

        boolean usedPaper = paperAdapter.applyMotd(event, parsed.component());
        if (!usedPaper) {
            setLegacyMotd(event, textFormatService.serializeToLegacy(parsed.component()));
        }

        try {
            paperAdapter.applyOnlinePlayers(event, 0);
            event.setMaxPlayers(0);
        } catch (Exception e) {
            logException(Level.WARNING, "Failed to apply whitelist player counts.", e);
        }

        try {
            event.setServerIcon(iconCache.pickIcon(preset));
        } catch (Exception e) {
            logException(Level.WARNING, "Failed to set whitelist server icon.", e);
        }
    }

    private String resolveProfileIdForRouting(ServerListPingEvent event) {
        if (!config.routing().enabled()) {
            return activeProfileId;
        }
        String host = resolveVirtualHost(event);
        if (host == null || host.isBlank()) {
            return activeProfileId;
        }
        String mapped = config.routing().hostMap().get(host.toLowerCase(Locale.ROOT));
        if (mapped == null || mapped.isBlank()) {
            return activeProfileId;
        }
        return config.profiles().containsKey(mapped) ? mapped : activeProfileId;
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

        if (ip != null) {
            runStickyMaintenance(profile, now, ttlMs);
        }

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
            stickyState(profileId).entries().put(ip, updated);
            return updated;
        }

        return createStickyEntry(profileId, ip, preset, now, ensureFrameSeed);
    }

    private StickyEntry createStickyEntry(String profileId, String ip, Preset preset, long now,
            boolean ensureFrameSeed) {
        if (ip == null) {
            return null;
        }
        int frameSeed = ensureFrameSeed ? computeFrameSeed(profileId, now) : 0;
        StickyEntry fresh = new StickyEntry(preset, now, frameSeed);
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
            return null;
        }
        return existing;
    }

    private boolean isStickyValid(StickyEntry entry, long now, long ttlMs) {
        return entry != null && (now - entry.createdAtMs()) <= ttlMs;
    }

    private StickyProfileState stickyState(String profileId) {
        return stickyStates.computeIfAbsent(profileId,
                key -> new StickyProfileState(new ConcurrentHashMap<>(), new ArrayDeque<>(), new AtomicInteger()));
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

    private MotdRenderResult renderMotd(Profile profile, SelectionResult selection,
            PlayerCountService.PlayerCountResult counts, RequestContext ctx) {
        FrameSelection frameSelection = selectFrame(profile, selection, ctx);
        return renderMotd(profile.id(), selection.preset(), frameSelection.frame(), counts, ctx,
                frameSelection.index());
    }

    private MotdRenderResult renderMotd(String profileId, Preset preset, CachedFrame frame,
            PlayerCountService.PlayerCountResult counts, RequestContext ctx, int frameIndex) {
        String raw = frame.raw();
        TextFormatService.ParseResult parsed;

        if (frame.hasPlaceholders() && config.placeholdersEnabled()) {
            PlaceholderValues values = buildPlaceholderValues(preset.id(), profileId, counts, frameIndex, ctx);
            String replaced = applyPlaceholders(raw, values);
            parsed = textFormatService.parseToComponentDetailed(replaced, config.colorFormat());
        } else if (frame.hasPlaceholders() && !config.placeholdersEnabled()) {
            parsed = textFormatService.parseToComponentDetailed(raw, config.colorFormat());
        } else if (frame.cachedComponent() != null) {
            parsed = new TextFormatService.ParseResult(frame.cachedComponent(), frame.usedFormat(),
                    frame.fallbackUsed());
        } else {
            parsed = textFormatService.parseToComponentDetailed(raw, config.colorFormat());
        }

        return new MotdRenderResult(raw, parsed, frameIndex);
    }

    private FrameSelection selectFrame(Profile profile, SelectionResult selection, RequestContext ctx) {
        Preset preset = selection.preset();
        PresetCache cache = presetCache(profile.id(), preset);
        boolean anim = profile.animation().enabled();

        List<CachedFrame> frames = cache.animatedFrames();
        if (anim && frames != null && !frames.isEmpty()) {
            int idx = resolveFrameIndex(profile, selection, ctx, frames.size());
            return new FrameSelection(frames.get(idx), idx);
        }

        return new FrameSelection(cache.staticFrame(), 0);
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

    private String applyPlaceholders(String input, PlaceholderValues values) {
        if (input == null || input.indexOf('%') < 0) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length() + 16);
        int len = input.length();
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            if (c != '%') {
                out.append(c);
                continue;
            }
            String replacement = null;
            if (matches(input, i, "%online%")) {
                replacement = values.online();
                i += "%online%".length() - 1;
            } else if (matches(input, i, "%max%")) {
                replacement = values.max();
                i += "%max%".length() - 1;
            } else if (matches(input, i, "%version%")) {
                replacement = values.version();
                i += "%version%".length() - 1;
            } else if (matches(input, i, "%preset%")) {
                replacement = values.preset();
                i += "%preset%".length() - 1;
            } else if (matches(input, i, "%profile%")) {
                replacement = values.profile();
                i += "%profile%".length() - 1;
            } else if (matches(input, i, "%motd_frame%")) {
                replacement = values.motdFrame();
                i += "%motd_frame%".length() - 1;
            } else if (matches(input, i, "%time%")) {
                replacement = values.time();
                i += "%time%".length() - 1;
            }

            if (replacement != null) {
                out.append(replacement);
            } else {
                out.append('%');
            }
        }
        return out.toString();
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
        for (Profile profile : config.profiles().values()) {
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

        List<String> rawFrames = preset.motdFrames();
        List<CachedFrame> frames = new ArrayList<>();
        if (rawFrames != null && !rawFrames.isEmpty()) {
            for (String frame : rawFrames) {
                frames.add(buildCachedFrame(frame, profile, preset));
            }
        }

        return new PresetCache(staticFrame, frames);
    }

    private CachedFrame buildCachedFrame(String raw, Profile profile, Preset preset) {
        boolean hasPlaceholders = hasPlaceholders(raw);
        if (!hasPlaceholders) {
            TextFormatService.ParseResult parsed = textFormatService.parseToComponentDetailed(raw,
                    config.colorFormat());
            warnIfFallback(profile, preset, parsed);
            return new CachedFrame(raw, false, parsed.component(), parsed.usedFormat(), parsed.fallbackUsed());
        }
        return new CachedFrame(raw, true, null, config.colorFormat(), false);
    }

    private CachedFrame buildWhitelistFrame(ConfigModel.WhitelistSettings whitelistSettings) {
        List<String> lines = whitelistSettings.motdLines();
        if (lines == null || lines.isEmpty()) {
            lines = List.of("Whitelisted", "");
        }
        String raw = lines.get(0) + "\n" + lines.get(1);
        boolean hasPlaceholders = hasPlaceholders(raw);
        if (!hasPlaceholders) {
            TextFormatService.ParseResult parsed = textFormatService.parseToComponentDetailed(raw,
                    config.colorFormat());
            return new CachedFrame(raw, false, parsed.component(), parsed.usedFormat(), parsed.fallbackUsed());
        }
        return new CachedFrame(raw, true, null, config.colorFormat(), false);
    }

    private boolean hasPlaceholders(String input) {
        if (input == null || input.indexOf('%') < 0) {
            return false;
        }
        for (String token : SUPPORTED_PLACEHOLDERS) {
            if (input.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private PlaceholderValues buildPlaceholderValues(String presetId, String profileId,
            PlayerCountService.PlayerCountResult counts, int frameIndex, RequestContext ctx) {
        String online = counts.hidePlayerCount() ? "???" : String.valueOf(counts.displayOnline());
        String max = counts.hidePlayerCount() ? "???" : String.valueOf(counts.displayMax());
        String version = Bukkit.getMinecraftVersion();
        String time = LocalTime.ofInstant(Instant.ofEpochMilli(ctx.nowMs()), SYSTEM_ZONE).format(TIME_FORMAT);
        return new PlaceholderValues(online, max, version, presetId, profileId, String.valueOf(frameIndex), time);
    }

    private boolean matches(String input, int index, String token) {
        int end = index + token.length();
        if (end > input.length()) {
            return false;
        }
        return input.regionMatches(index, token, 0, token.length());
    }

    private Collection<String> collectIconPaths(ConfigModel config) {
        Set<String> paths = ConcurrentHashMap.newKeySet();
        paths.add("icons/default.png");
        if (config.fallbackIconPath() != null) {
            paths.add(config.fallbackIconPath());
        }
        if (config.whitelist().iconPath() != null) {
            paths.add(config.whitelist().iconPath());
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

        int checked = 0;
        for (var iterator = state.entries().entrySet().iterator(); iterator.hasNext()
                && checked < STICKY_CLEANUP_BATCH;) {
            Map.Entry<String, StickyEntry> entry = iterator.next();
            checked++;
            if (!isStickyValid(entry.getValue(), nowMs, ttlMs)) {
                iterator.remove();
            }
        }

        enforceStickyLimit(profile, state);
    }

    private void enforceStickyLimit(Profile profile, StickyProfileState state) {
        int maxEntries = profile.stickyMaxEntriesPerProfile();
        if (maxEntries <= 0) {
            return;
        }
        if (state.entries().size() <= maxEntries) {
            return;
        }

        int evicted = 0;
        while (state.entries().size() > maxEntries && evicted < STICKY_EVICTION_BATCH) {
            String key = state.order().pollFirst();
            if (key == null) {
                break;
            }
            if (state.entries().remove(key) != null) {
                evicted++;
            }
        }

        if (state.entries().size() > maxEntries) {
            List<String> candidates = new ArrayList<>(STICKY_EVICTION_BATCH);
            for (String key : state.entries().keySet()) {
                candidates.add(key);
                if (candidates.size() >= STICKY_EVICTION_BATCH) {
                    break;
                }
            }
            Collections.sort(candidates);
            for (String key : candidates) {
                if (state.entries().size() <= maxEntries) {
                    break;
                }
                state.entries().remove(key);
            }
        }
    }

    private String resolveVirtualHost(ServerListPingEvent event) {
        if (event == null) {
            return null;
        }
        Method method = VirtualHostResolver.resolveMethod(event.getClass());
        if (method == null) {
            if (config.debugVerbose() && routingWarned.compareAndSet(false, true)) {
                plugin.getLogger().info("Virtual host routing not available: no compatible ping method found.");
            }
            return null;
        }
        try {
            Object value = method.invoke(event);
            if (value == null) {
                return null;
            }
            if (value instanceof String host) {
                return host;
            }
            if (value instanceof InetSocketAddress address) {
                String host = address.getHostString();
                if (host == null && address.getAddress() != null) {
                    host = address.getAddress().getHostName();
                }
                return host;
            }
            return String.valueOf(value);
        } catch (Exception e) {
            if (config.debugVerbose() && routingWarned.compareAndSet(false, true)) {
                logException(Level.WARNING, "Failed to resolve virtual host for routing.", e);
            }
            return null;
        }
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
            plugin.getLogger().warning(
                    "Formatting failed for profile '" + profile.id() + "', preset '" + preset.id()
                            + "' using " + result.usedFormat() + ". Using plain text fallback.");
        }
    }

    private void warnIfWhitelistProfileMissing() {
        String profileId = config.whitelist().whitelistMotdProfile();
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        if (!config.profiles().containsKey(profileId) && whitelistProfileWarned.compareAndSet(false, true)) {
            plugin.getLogger().warning(
                    "whitelist.whitelistMotdProfile is set to '" + profileId
                            + "' but no such profile exists. Falling back to whitelist mode.");
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

    private record StickyProfileState(Map<String, StickyEntry> entries, Deque<String> order,
            AtomicInteger pingCounter) {
    }

    private record SelectionResult(Preset preset, StickyEntry stickyEntry, String reason) {
    }

    public record ReloadResult(boolean success, int warnings) {
    }

    private record CachedFrame(String raw, boolean hasPlaceholders, Component cachedComponent, ColorFormat usedFormat,
            boolean fallbackUsed) {
    }

    private record PresetCache(CachedFrame staticFrame, List<CachedFrame> animatedFrames) {
    }

    private record FrameSelection(CachedFrame frame, int index) {
    }

    private record MotdRenderResult(String raw, TextFormatService.ParseResult parsed, int frameIndex) {
    }

    private record PlaceholderValues(String online, String max, String version, String preset, String profile,
            String motdFrame, String time) {
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

    private static final class VirtualHostResolver {
        private static volatile Method cachedMethod;
        private static volatile boolean resolved;

        private VirtualHostResolver() {
        }

        private static Method resolveMethod(Class<?> eventClass) {
            if (resolved) {
                return cachedMethod;
            }
            resolved = true;
            cachedMethod = findMethod(eventClass, "getVirtualHost");
            if (cachedMethod != null) {
                return cachedMethod;
            }
            cachedMethod = findMethod(eventClass, "getHostname");
            return cachedMethod;
        }

        private static Method findMethod(Class<?> eventClass, String name) {
            if (eventClass == null) {
                return null;
            }
            try {
                return eventClass.getMethod(name);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    }
}
