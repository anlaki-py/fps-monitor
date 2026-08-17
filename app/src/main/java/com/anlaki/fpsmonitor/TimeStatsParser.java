package com.anlaki.fpsmonitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TimeStatsParser {
    private static final Pattern PACKAGE = Pattern.compile(
            "(?:^|[\\s{])([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)(?:/|(?=\\s|}|$))");
    private static final String[] FOCUS_MARKERS = {
            "mCurrentFocus", "mFocusedWindow", "mFocusedApp", "topResumedActivity",
            "mResumedActivity", "ResumedActivity", "topActivity"
    };

    static String foregroundPackage(String dump) {
        if (dump == null) return null;
        for (String marker : FOCUS_MARKERS) {
            for (String line : dump.split("\\R")) {
                if (!line.contains(marker)) continue;
                Matcher match = PACKAGE.matcher(line);
                if (match.find()) return match.group(1);
            }
        }
        return null;
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
