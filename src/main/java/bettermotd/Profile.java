package bettermotd;

import java.util.List;

public record Profile(
                String id,
                ConfigModel.SelectionMode selectionMode,
                int stickyTtlSeconds,
                int stickyMaxEntriesPerProfile,
                int stickyCleanupEveryNPings,
                AnimationSettings animation,
                PlayerCountSettings playerCount,
                List<Preset> presets) {

        public record AnimationSettings(boolean enabled, long frameIntervalMillis, ConfigModel.AnimationMode mode) {
        }

        public record PlayerCountSettings(
                        boolean disableHover,
                        boolean hidePlayerCount,
                        FakePlayersSettings fakePlayers,
                        JustXMoreSettings justXMore,
                        MaxPlayersSettings maxPlayers) {
        }

        public record FakePlayersSettings(
                        boolean enabled,
                        FakePlayersMode mode,
                        int min,
                        int max,
                        double percent) {
        }

        public enum FakePlayersMode {
                STATIC,
                RANDOM,
                PERCENT
        }

        public record JustXMoreSettings(boolean enabled, int x) {
        }

        public record MaxPlayersSettings(boolean enabled, int value) {
        }
}
