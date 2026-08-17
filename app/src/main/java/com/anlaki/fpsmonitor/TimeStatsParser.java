package com.anlaki.fpsmonitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class TimeStatsParser {
    private static final String[] FOCUS_MARKERS = {
            "mCurrentFocus", "mFocusedWindow", "mFocusedApp", "topResumedActivity",
            "mResumedActivity", "ResumedActivity", "topActivity"
    };

    static String foregroundPackage(String dump) {
        if (dump == null) return null;
        for (String marker : FOCUS_MARKERS) {
            int searchFrom = 0;
            while (searchFrom < dump.length()) {
                int markerAt = dump.indexOf(marker, searchFrom);
                if (markerAt < 0) break;
                int lineEnd = dump.indexOf('\n', markerAt);
                if (lineEnd < 0) lineEnd = dump.length();
                String packageName = componentPackage(dump, markerAt + marker.length(), lineEnd);
                if (packageName != null) return packageName;
                searchFrom = lineEnd + 1;
            }
        }
        return null;
    }

    static double displayFps(double measuredFps, double displayRefreshRate) {
        if (!(measuredFps > 0.0) || !(displayRefreshRate > 0.0)
                || measuredFps <= displayRefreshRate) {
            return measuredFps;
        }
        double refreshIntervalMs = 1000.0 / displayRefreshRate;
        double integerIntervalMs = Math.floor(refreshIntervalMs);
        if (integerIntervalMs < 1.0) return measuredFps;

        // TimeStats bins present-to-present intervals in whole milliseconds. For
        // example, 8 ms becomes 125 FPS even when the display is actually 120 Hz.
        double quantizedCeiling = 1000.0 / integerIntervalMs;
        return measuredFps <= quantizedCeiling * 1.01
                ? displayRefreshRate : measuredFps;
    }

    private static String componentPackage(String text, int start, int end) {
        int slash = text.indexOf('/', start);
        while (slash >= 0 && slash < end) {
            int packageEnd = slash;
            while (packageEnd > start && Character.isWhitespace(text.charAt(packageEnd - 1))) {
                packageEnd--;
            }
            int packageStart = packageEnd;
            while (packageStart > start && isPackageCharacter(text.charAt(packageStart - 1))) {
                packageStart--;
            }
            if (isPackageName(text, packageStart, packageEnd)) {
                return text.substring(packageStart, packageEnd);
            }
            slash = text.indexOf('/', slash + 1);
        }

        // Some OEM dumps omit the activity after the package. Scan tokens without
        // regex backtracking so even unusually long WindowManager lines stay bounded.
        int position = start;
        String candidate = null;
        while (position < end) {
            while (position < end && !isPackageCharacter(text.charAt(position))) position++;
            int tokenStart = position;
            while (position < end && isPackageCharacter(text.charAt(position))) position++;
            if (isPackageName(text, tokenStart, position)) {
                candidate = text.substring(tokenStart, position);
            }
        }
        return candidate;
    }

    private static boolean isPackageCharacter(char value) {
        return value == '.' || value == '_' || Character.isLetterOrDigit(value);
    }

    private static boolean isPackageName(String text, int start, int end) {
        if (start >= end || !Character.isLetter(text.charAt(start))
                || text.charAt(end - 1) == '.') return false;
        boolean dot = false;
        for (int index = start; index < end; index++) {
            char value = text.charAt(index);
            if (value == '.') {
                if (index == start || text.charAt(index - 1) == '.') return false;
                dot = true;
            } else if (value != '_' && !Character.isLetterOrDigit(value)) {
                return false;
            }
        }
        return dot;
    }

    static String diagnosticLines(String dump, int limit) {
        if (dump == null || dump.isEmpty()) return "<empty output>";
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (String raw : dump.split("\\R")) {
            String lower = raw.toLowerCase();
            if (lower.contains("focus") || lower.contains("resum")
                    || lower.contains("topactivity")) {
                result.append(raw.trim()).append('\n');
                if (++count >= limit) break;
            }
        }
        return count == 0 ? "<no focus/resumed lines found>" : result.toString().trim();
    }

    static int layerBlockCount(String dump) {
        if (dump == null) return 0;
        int count = 0;
        for (String line : dump.split("\\R")) {
            if (line.trim().startsWith("layerName =")) count++;
        }
        return count;
    }

    static String layerNames(String dump, int limit) {
        if (dump == null || dump.isEmpty()) return "<empty output>";
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (String raw : dump.split("\\R")) {
            String line = raw.trim();
            if (!line.startsWith("layerName =")) continue;
            result.append(line.substring("layerName =".length()).trim()).append('\n');
            if (++count >= limit) break;
        }
        return count == 0 ? "<no layerName fields found>" : result.toString().trim();
    }

    static List<LayerStat> layers(String dump, String packageName) {
        List<LayerStat> result = new ArrayList<>();
        String name = null;
        long frames = -1;
        double fps = Double.NaN;
        boolean waitingForName = false;

        for (String raw : dump.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("layerName =")) {
                add(result, packageName, name, frames, fps);
                name = line.substring("layerName =".length()).trim();
                waitingForName = name.isEmpty();
                frames = -1;
                fps = Double.NaN;
            } else if (waitingForName && !line.isEmpty()) {
                name = line;
                waitingForName = false;
            } else if (name != null && line.startsWith("totalFrames =")) {
                try { frames = Long.parseLong(line.substring(line.indexOf('=') + 1).trim()); }
                catch (NumberFormatException ignored) {}
            } else if (name != null && line.startsWith("averageFPS =")) {
                try { fps = Double.parseDouble(line.substring(line.indexOf('=') + 1).trim()); }
                catch (NumberFormatException ignored) {}
            }
        }
        add(result, packageName, name, frames, fps);
        result.sort(Comparator
                .comparing(LayerStat::preferredSurface).reversed()
                .thenComparing(Comparator.comparingLong((LayerStat item) -> item.frames).reversed()));
        return result;
    }

    private static void add(List<LayerStat> result, String packageName,
                            String name, long frames, double fps) {
        if (name != null && packageName != null && name.contains(packageName)
                && frames >= 2 && !Double.isNaN(fps)) {
            result.add(new LayerStat(name, frames, fps));
        }
    }
}
