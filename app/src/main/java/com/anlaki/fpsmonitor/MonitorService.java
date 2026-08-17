package com.anlaki.fpsmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public final class MonitorService extends Service {
    public static final String ACTION_STOP = "com.anlaki.fpsmonitor.STOP";
    private static final String CHANNEL_ID = "monitor";
    private static final int NOTIFICATION_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService worker;
    private IShellService shell;
    private WindowManager windows;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout overlay;
    private Button fpsButton;
    private RadioGroup layerChoices;
    private String currentPackage;
    private String selectedLayer;
    private List<String> shownLayerKeys = new ArrayList<>();
    private boolean stopping;
    private final StringBuilder debugLog = new StringBuilder();
    private long lastDebugWrite;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            shell = IShellService.Stub.asInterface(binder);
            appendDebug("Shizuku UserService connected; uid=" + safeShizukuUid());
            startSampling();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            shell = null;
            appendDebug("Shizuku UserService disconnected");
            main.post(() -> fpsButton.setText("Shizuku disconnected"));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification());
        createOverlay();
        getSharedPreferences("state", MODE_PRIVATE).edit().remove("debug_log").apply();
        appendDebug("FPS Monitor 1.1.0 started; binder=" + Shizuku.pingBinder()
                + "; uid=" + safeShizukuUid());
        bindShell();
        getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("running", true).apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }

    private void bindShell() {
        if (!Shizuku.pingBinder()) {
            fpsButton.setText("Start Shizuku");
            return;
        }
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(this, ShellService.class))
                .processNameSuffix("fps")
                .debuggable(BuildConfig.DEBUG)
                .version(2)
                .daemon(false);
        Shizuku.bindUserService(args, connection);
    }

    private void startSampling() {
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.execute(() -> {
            try { shell.run("enable"); }
            catch (Exception e) { showError(e); }
        });
        worker.scheduleWithFixedDelay(this::sample, 500, 500, TimeUnit.MILLISECONDS);
    }

    private void sample() {
        IShellService service = shell;
        if (service == null || stopping) return;
        try {
            SharedPreferences preferences = getSharedPreferences("state", MODE_PRIVATE);
            String fixedPackage = preferences.getString("target_package", null);
            String windowDump = null;
            String activityDump = null;
            String foreground = fixedPackage;
            String source = "fixed app selection";

            if (foreground == null || foreground.isEmpty()) {
                windowDump = service.run("foregroundWindow");
                foreground = TimeStatsParser.foregroundPackage(windowDump);
                source = "WindowManager";
                if (foreground == null) {
                    activityDump = service.run("foregroundActivity");
                    foreground = TimeStatsParser.foregroundPackage(activityDump);
                    source = "ActivityManager fallback";
                }
            }
            String dump = service.run("sample");
            List<LayerStat> layers = TimeStatsParser.layers(dump, foreground);
            recordSample(source, fixedPackage, foreground, windowDump, activityDump, dump, layers);
            String targetPackage = foreground;
            main.post(() -> display(targetPackage, layers));
        } catch (Exception e) {
            showError(e);
        }
    }

    private void display(String foreground, List<LayerStat> layers) {
        if (foreground == null) {
            fpsButton.setText("No foreground app");
            layerChoices.setVisibility(View.GONE);
            return;
        }
        if (!foreground.equals(currentPackage)) {
            currentPackage = foreground;
            selectedLayer = null;
            shownLayerKeys.clear();
        }
        if (layers.isEmpty()) {
            fpsButton.setText("Idle / no data");
            layerChoices.setVisibility(View.GONE);
            return;
        }

        LayerStat chosen = layers.get(0);
        if (selectedLayer != null) {
            for (LayerStat layer : layers) {
                if (layer.stableName.equals(selectedLayer)) {
                    chosen = layer;
                    break;
                }
            }
        }
        fpsButton.setText(String.format(Locale.US, "%.1f FPS", chosen.fps));
        updateChoices(layers);
    }

    private void updateChoices(List<LayerStat> layers) {
        List<String> keys = new ArrayList<>();
        for (LayerStat layer : layers) keys.add(layer.stableName);
        if (keys.equals(shownLayerKeys)) {
            for (int i = 1; i < layerChoices.getChildCount() && i <= layers.size(); i++) {
                RadioButton item = (RadioButton) layerChoices.getChildAt(i);
                LayerStat layer = layers.get(i - 1);
                item.setText(layer.shortName() + String.format(Locale.US, "  %.1f", layer.fps));
            }
            return;
        }

        shownLayerKeys = keys;
        layerChoices.setOnCheckedChangeListener(null);
        layerChoices.removeAllViews();
        RadioButton automatic = new RadioButton(this);
        automatic.setText("Auto");
        automatic.setId(View.generateViewId());
        automatic.setTag(null);
        layerChoices.addView(automatic);
        if (selectedLayer == null) automatic.setChecked(true);

        for (LayerStat layer : layers) {
            RadioButton item = new RadioButton(this);
            item.setId(View.generateViewId());
            item.setTag(layer.stableName);
            item.setText(layer.shortName() + String.format(Locale.US, "  %.1f", layer.fps));
            if (layer.stableName.equals(selectedLayer)) item.setChecked(true);
            layerChoices.addView(item);
        }
        layerChoices.setVisibility(layers.size() > 1 ? View.VISIBLE : View.GONE);
        layerChoices.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            selectedLayer = checked == null ? null : (String) checked.getTag();
        });
    }

    private void createOverlay() {
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);

        fpsButton = new Button(this);
        fpsButton.setText("Connecting…");
        fpsButton.setAllCaps(false);
        overlay.addView(fpsButton);

        layerChoices = new RadioGroup(this);
        layerChoices.setOrientation(RadioGroup.VERTICAL);
        layerChoices.setVisibility(View.GONE);
        overlay.addView(layerChoices);

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = dp(12);
        overlayParams.y = dp(80);

        fpsButton.setOnTouchListener(new DragListener());
        windows.addView(overlay, overlayParams);
    }

    private final class DragListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = overlayParams.x;
                    startY = overlayParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    overlayParams.x = startX + Math.round(event.getRawX() - downX);
                    overlayParams.y = startY + Math.round(event.getRawY() - downY);
                    windows.updateViewLayout(overlay, overlayParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    return true;
                default:
                    return false;
            }
        }
    }

    private void showError(Exception error) {
        String text = error instanceof RemoteException ? "SurfaceFlinger error" : "Monitor error";
        appendDebug("ERROR " + error.getClass().getName() + ": " + error.getMessage());
        main.post(() -> fpsButton.setText(text));
    }

    private void recordSample(String source, String fixedPackage, String foreground,
                              String windowDump, String activityDump, String surfaceDump,
                              List<LayerStat> layers) {
        StringBuilder entry = new StringBuilder();
        entry.append("Mode: ").append(fixedPackage == null ? "AUTO" : "FIXED").append('\n');
        if (fixedPackage != null) entry.append("Configured package: ").append(fixedPackage).append('\n');
        entry.append("Detection source: ").append(source).append('\n');
        entry.append("Detected/target package: ").append(foreground).append('\n');
        if (windowDump != null) {
            entry.append("WindowManager bytes: ").append(windowDump.length()).append('\n');
            entry.append("Window focus lines:\n")
                    .append(TimeStatsParser.diagnosticLines(windowDump, 12)).append('\n');
        }
        if (activityDump != null) {
            entry.append("ActivityManager bytes: ").append(activityDump.length()).append('\n');
            entry.append("Activity focus lines:\n")
                    .append(TimeStatsParser.diagnosticLines(activityDump, 12)).append('\n');
        }
        entry.append("TimeStats bytes: ").append(surfaceDump == null ? 0 : surfaceDump.length()).append('\n');
        entry.append("Layer blocks returned: ").append(TimeStatsParser.layerBlockCount(surfaceDump)).append('\n');
        entry.append("Matching candidates: ").append(layers.size()).append('\n');
        for (LayerStat layer : layers) {
            entry.append("  - fps=").append(String.format(Locale.US, "%.3f", layer.fps))
                    .append(" frames=").append(layer.frames)
                    .append(" name=").append(layer.name).append('\n');
        }
        if (layers.isEmpty()) {
            entry.append("Returned layer names (first 20):\n")
                    .append(TimeStatsParser.layerNames(surfaceDump, 20)).append('\n');
        }
        appendDebug(entry.toString().trim());
    }

    private synchronized void appendDebug(String message) {
        String timestamp = String.format(Locale.US, "%1$tF %1$tT.%1$tL", new Date());
        debugLog.append("\n=== ").append(timestamp).append(" ===\n")
                .append(message).append('\n');
        if (debugLog.length() > 24000) debugLog.delete(0, debugLog.length() - 24000);
        long now = System.currentTimeMillis();
        if (now - lastDebugWrite >= 1000 || message.startsWith("ERROR")) {
            persistDebug();
            lastDebugWrite = now;
        }
    }

    private synchronized void persistDebug() {
        getSharedPreferences("state", MODE_PRIVATE).edit()
                .putString("debug_log", debugLog.toString().trim()).apply();
    }

    private int safeShizukuUid() {
        try { return Shizuku.getUid(); }
        catch (Exception ignored) { return -1; }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "FPS monitoring", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification() {
        Intent stop = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("FPS Monitor")
                .setContentText("Monitoring the foreground app")
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopIntent).build())
                .build();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        stopping = true;
        appendDebug("Monitor stopping");
        persistDebug();
        if (worker != null) {
            worker.execute(() -> {
                try { if (shell != null) shell.run("disable"); }
                catch (Exception ignored) {}
            });
            worker.shutdown();
        }
        if (overlay != null) windows.removeView(overlay);
        try {
            Shizuku.unbindUserService(new Shizuku.UserServiceArgs(
                    new ComponentName(this, ShellService.class)).processNameSuffix("fps"),
                    connection, true);
        } catch (Exception ignored) {}
        getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("running", false).apply();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
