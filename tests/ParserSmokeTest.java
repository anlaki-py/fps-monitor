package com.anlaki.fpsmonitor;

import java.util.List;

public final class ParserSmokeTest {
    public static void main(String[] args) {
        String windows = "mCurrentFocus=Window{abcd u0 com.game.test/com.game.MainActivity}";
        if (!"com.game.test".equals(TimeStatsParser.foregroundPackage(windows))) {
            throw new AssertionError("foreground package parsing failed");
        }
        String activity = "mResumedActivity: ActivityRecord{123 u0 com.fallback.game/.MainActivity t22}";
        if (!"com.fallback.game".equals(TimeStatsParser.foregroundPackage(activity))) {
            throw new AssertionError("activity fallback parsing failed");
        }

        String stats = "layerName = com.game.test/com.game.MainActivity#20\n"
                + "totalFrames = 10\naverageFPS = 20.0\n"
                + "layerName = SurfaceView[com.game.test/com.game.MainActivity](BLAST)#42\n"
                + "totalFrames = 30\naverageFPS = 59.8\n"
                + "layerName = com.other.app/com.other.Main#1\n"
                + "totalFrames = 50\naverageFPS = 60.0\n";
        List<LayerStat> layers = TimeStatsParser.layers(stats, "com.game.test");
        if (layers.size() != 2 || !layers.get(0).preferredSurface()
                || Math.abs(layers.get(0).fps - 59.8) > 0.001) {
            throw new AssertionError("layer filtering or sorting failed");
        }
        if (!layers.get(0).stableName.endsWith("#")) {
            throw new AssertionError("temporary layer id was not normalized");
        }
        System.out.println("Parser smoke test passed");
    }
}
