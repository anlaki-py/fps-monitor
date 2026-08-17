package com.anlaki.fpsmonitor;

import java.util.Objects;

final class LayerStat {
    final String name;
    final String stableName;
    final long frames;
    final double fps;

    LayerStat(String name, long frames, double fps) {
        this.name = name;
        this.stableName = name.replaceAll("#\\d+", "#").trim();
        this.frames = frames;
        this.fps = fps;
    }

    boolean preferredSurface() {
        return name.contains("SurfaceView[") || name.contains("(BLAST)");
    }

    String shortName() {
        String value = name.replaceAll("#\\d+", "").trim();
        if (value.length() > 58) value = value.substring(0, 55) + "…";
        return value;
    }

    @Override public boolean equals(Object object) {
        return object instanceof LayerStat && stableName.equals(((LayerStat) object).stableName);
    }

    @Override public int hashCode() { return Objects.hash(stableName); }
}
