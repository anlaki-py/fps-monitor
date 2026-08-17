package com.anlaki.fpsmonitor;

public final class OverlayGeometryTest {
    public static void main(String[] args) {
        if (OverlayGeometry.fitPanelSize(900, 1000, 200) != 800) {
            throw new AssertionError("panel should leave reserved screen space");
        }
        if (OverlayGeometry.fitPanelSize(600, 1000, 200) != 600) {
            throw new AssertionError("panel should keep its preferred size when it fits");
        }
        if (OverlayGeometry.clampPosition(-20, 300, 1000) != 0
                || OverlayGeometry.clampPosition(900, 300, 1000) != 700
                || OverlayGeometry.clampPosition(250, 300, 1000) != 250
                || OverlayGeometry.clampPosition(20, 1200, 1000) != 0) {
            throw new AssertionError("overlay position clamping failed");
        }
        if (OverlayGeometry.scaleDimension(16, 50) != 8
                || OverlayGeometry.scaleDimension(12, 75) != 9
                || OverlayGeometry.scaleDimension(16, 100) != 16
                || OverlayGeometry.scaleDimension(16, 200) != 32) {
            throw new AssertionError("overlay dimension scaling failed");
        }
        System.out.println("Overlay geometry test passed");
    }
}
