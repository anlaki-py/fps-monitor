package com.anlaki.fpsmonitor;

final class OverlayGeometry {
    private OverlayGeometry() {}

    static int fitPanelSize(int preferredSize, int screenSize, int reservedSpace) {
        return Math.max(1, Math.min(preferredSize, Math.max(1, screenSize - reservedSpace)));
    }

    static int clampPosition(int position, int overlaySize, int screenSize) {
        return Math.max(0, Math.min(position, Math.max(0, screenSize - overlaySize)));
    }

    static int scaleDimension(int baseSize, int percent) {
        return Math.max(1, Math.round(baseSize * percent / 100f));
    }
}
