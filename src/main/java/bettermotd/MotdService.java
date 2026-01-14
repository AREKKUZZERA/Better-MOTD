package bettermotd;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class MotdService {

    private final JavaPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final IconCache iconCache;

    private final Map<String, StickyEntry> sticky = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> picks = new ConcurrentHashMap<>();
    private final AtomicInteger rotateCounter = new AtomicInteger();

    private volatile ConfigModel config = ConfigModel.empty();
    private volatile List<Preset> fallbackPresets = List.of(Preset.fallback(null));

    public MotdService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.iconCache = new IconCache(plugin);
    }

    public ReloadResult reload() {
        ConfigModel.LoadResult result = ConfigModel.load(plugin.getConfig(), plugin.getDataFolder(),
                plugin.getLogger());
        this.config = result.config();
        this.fallbackPresets = List.of(Preset.fallback(result.config().fallbackIconPath()));
        iconCache.reload(config);
        sticky.clear();
        rotateCounter.set(0);
        return new ReloadResult(result.presetsLoaded(), result.warnings());
    }

    public void shutdown() {
        sticky.clear();
        iconCache.clear();
    }

    public void apply(PaperServerListPingEvent event) {
        RequestContext ctx = new RequestContext(
                asIp(event.getAddress()));
        SelectionResult selection = selectPreset(ctx, true);
        long now = System.currentTimeMillis();

        event.motd(buildMotd(selection, ctx, now));
        event.setServerIcon(iconCache.pickIcon(selection.preset()));
    }

    @SuppressWarnings("deprecation")
    public void apply(ServerListPingEvent event) {
        RequestContext ctx = new RequestContext(
                asIp(event.getAddress()));
        SelectionResult selection = selectPreset(ctx, true);
        long now = System.currentTimeMillis();

        event.setMotd(buildPlainMotd(selection, ctx, now));
    }

    public TestResult test(InetAddress address) {
        RequestContext ctx = new RequestContext(asIp(address));
        SelectionResult selection = selectPreset(ctx, false);
        long now = System.currentTimeMillis();
        String motd = buildPlainMotd(selection, ctx, now);
        return new TestResult(selection.preset().id(), motd);
    }

    public List<StatEntry> getStats() {
        if (picks.isEmpty()) {
            return Collections.emptyList();
        }

        List<StatEntry> stats = new ArrayList<>(picks.size());
        for (Map.Entry<String, LongAdder> entry : picks.entrySet()) {
            stats.add(new StatEntry(entry.getKey(), entry.getValue().sum()));
        }
        stats.sort((a, b) -> Long.compare(b.count(), a.count()));
        return stats;
    }

    private SelectionResult selectPreset(RequestContext ctx, boolean count) {
        List<Preset> presets = resolvePresets(ctx);
        if (presets.isEmpty()) {
            presets = fallbackPresets;
        }

        String mode = config.selectionMode();
        long now = System.currentTimeMillis();
        long ttlMs = Math.max(1, config.stickyTtlSeconds()) * 1000L;
        String ip = ctx.ip();

        StickyEntry entry = getStickyEntry(ip, now, ttlMs);
        boolean perIpFrames = ConfigModel.AnimationMode.PER_IP_STICKY.name()
                .equalsIgnoreCase(config.motdAnimationMode());

        Preset chosen;
        if (ConfigModel.SelectionMode.STICKY_PER_IP.name().equalsIgnoreCase(mode) && ip != null) {
            if (entry != null) {
                chosen = entry.preset();
            } else {
                chosen = weightedRandom(presets, (ip.hashCode() * 31L) ^ now);
                entry = createStickyEntry(ip, chosen, now, perIpFrames);
            }
        } else if (ConfigModel.SelectionMode.HASHED_PER_IP.name().equalsIgnoreCase(mode)) {
            chosen = hashedPreset(presets, ip);
            if (perIpFrames && ip != null) {
                entry = updateStickyEntry(ip, entry, chosen, now, ttlMs, true);
            }
        } else if (ConfigModel.SelectionMode.ROTATE.name().equalsIgnoreCase(mode)) {
            chosen = rotatePreset(presets);
            if (perIpFrames && ip != null) {
                entry = updateStickyEntry(ip, entry, chosen, now, ttlMs, true);
            }
        } else {
            chosen = weightedRandom(presets, System.nanoTime());
            if (perIpFrames && ip != null) {
                entry = updateStickyEntry(ip, entry, chosen, now, ttlMs, true);
            }
        }

        if (count) {
            picks.computeIfAbsent(chosen.id(), key -> new LongAdder()).increment();
        }

        return new SelectionResult(chosen, entry);
    }

    private StickyEntry updateStickyEntry(String ip, StickyEntry existing, Preset preset, long now, long ttlMs,
            boolean ensureFrameSeed) {
        if (ip == null) {
            return null;
        }
        if (existing != null && isStickyValid(existing, now, ttlMs)) {
            int frameSeed = existing.frameSeed();
            StickyEntry updated = new StickyEntry(preset, existing.createdAtMs(), frameSeed);
            sticky.put(ip, updated);
            return updated;
        }

        return createStickyEntry(ip, preset, now, ensureFrameSeed);
    }

    private StickyEntry createStickyEntry(String ip, Preset preset, long now, boolean ensureFrameSeed) {
        if (ip == null) {
            return null;
        }
        int frameSeed = ensureFrameSeed ? computeFrameSeed(now) : 0;
        StickyEntry fresh = new StickyEntry(preset, now, frameSeed);
        sticky.put(ip, fresh);
        return fresh;
    }

    private StickyEntry getStickyEntry(String ip, long now, long ttlMs) {
        if (ip == null) {
            return null;
        }
        StickyEntry existing = sticky.get(ip);
        if (existing == null) {
            return null;
        }
        if (!isStickyValid(existing, now, ttlMs)) {
            sticky.remove(ip, existing);
            return null;
        }
        return existing;
    }

    private boolean isStickyValid(StickyEntry entry, long now, long ttlMs) {
        return entry != null && (now - entry.createdAtMs()) <= ttlMs;
    }

    private Preset hashedPreset(List<Preset> presets, String ip) {
        if (presets.isEmpty()) {
            return fallbackPresets.get(0);
        }
        if (ip == null) {
            int idx = (int) Math.floorMod(System.nanoTime(), presets.size());
            return presets.get(idx);
        }
        int idx = Math.floorMod(ip.hashCode(), presets.size());
        return presets.get(idx);
    }

    private Preset rotatePreset(List<Preset> presets) {
        if (presets.isEmpty()) {
            return fallbackPresets.get(0);
        }
        int idx = Math.floorMod(rotateCounter.getAndIncrement(), presets.size());
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

    private List<Preset> resolvePresets(RequestContext ctx) {
        List<Preset> presets = config.presets();
        if (presets == null || presets.isEmpty()) {
            return fallbackPresets;
        }
        return presets;
    }

    private Component buildMotd(SelectionResult selection, RequestContext ctx, long nowMs) {
        String frame = pickMotdFrame(selection, ctx, nowMs);
        frame = applyPlaceholders(frame, selection.preset());
        return mini.deserialize(frame);
    }

    private String buildPlainMotd(SelectionResult selection, RequestContext ctx, long nowMs) {
        String frame = pickMotdFrame(selection, ctx, nowMs);
        frame = applyPlaceholders(frame, selection.preset());
        return stripMiniMessageTags(frame);
    }

    private String pickMotdFrame(SelectionResult selection, RequestContext ctx, long nowMs) {
        Preset preset = selection.preset();
        boolean anim = config.animationEnabled();

        List<String> frames = preset.motdFrames();
        if (anim && frames != null && !frames.isEmpty()) {
            int idx = resolveFrameIndex(selection, ctx, nowMs, frames.size());
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

    private int resolveFrameIndex(SelectionResult selection, RequestContext ctx, long nowMs, int size) {
        if (size <= 0) {
            return 0;
        }
        if (ConfigModel.AnimationMode.PER_IP_STICKY.name().equalsIgnoreCase(config.motdAnimationMode())
                && ctx.ip() != null) {
            StickyEntry entry = selection.stickyEntry();
            if (entry != null) {
                return Math.floorMod(entry.frameSeed(), size);
            }
        }
        long interval = config.frameIntervalMillis();
        return (int) ((nowMs / interval) % size);
    }

    private int computeFrameSeed(long nowMs) {
        long interval = config.frameIntervalMillis();
        return (int) (nowMs / interval);
    }

    private String applyPlaceholders(String input, Preset preset) {
        if (!config.placeholdersEnabled() || input == null || input.indexOf('%') < 0) {
            return input;
        }

        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        String version = Bukkit.getMinecraftVersion();

        String output = input;
        output = output.replace("%online%", String.valueOf(online));
        output = output.replace("%max%", String.valueOf(max));
        output = output.replace("%version%", version);
        output = output.replace("%preset%", preset.id());
        return output;
    }

    private String stripMiniMessageTags(String input) {
        if (input == null || input.indexOf('<') < 0) {
            return input;
        }
        StringBuilder out = new StringBuilder(input.length());
        boolean inTag = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '<') {
                inTag = true;
                continue;
            }
            if (c == '>') {
                inTag = false;
                continue;
            }
            if (!inTag) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private String asIp(InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }

    private record StickyEntry(Preset preset, long createdAtMs, int frameSeed) {
    }

    private record SelectionResult(Preset preset, StickyEntry stickyEntry) {
    }

    public record ReloadResult(int presetsLoaded, int warnings) {
    }

    public record TestResult(String presetId, String motdPlain) {
    }

    public record StatEntry(String presetId, long count) {
    }

    private record RequestContext(String ip) {
    }
}
