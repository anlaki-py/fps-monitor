package com.anlaki.fpsmonitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 10;
    private static final int OVERLAY_REQUEST = 11;
    private static final int NOTIFICATION_REQUEST = 12;
    private static final int PICK_APP_REQUEST = 13;

    private Button toggle;
    private Button loggingToggle;
    private TextView status;
    private TextView target;
    private TextView overlaySizeLabel;
    private SeekBar overlaySize;
    private boolean startAfterPermission;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_REQUEST) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    continueStart();
                } else {
                    show("Shizuku permission was denied");
                }
            };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Shizuku.addRequestPermissionResultListener(permissionListener);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        target = new TextView(this);
        target.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        targetParams.topMargin = dp(12);
        root.addView(target, targetParams);

        toggle = addButton(root, "Start");
        toggle.setOnClickListener(v -> {
            if (isRunning()) stopMonitor();
            else beginStart();
        });

        Button select = addButton(root, "Select app");
        select.setOnClickListener(v -> pickApp());

        Button automatic = addButton(root, "Use automatic detection");
        automatic.setOnClickListener(v -> {
            getSharedPreferences("state", MODE_PRIVATE).edit()
                    .remove("target_package").remove("target_label").apply();
            noteMonitor("automatic app detection selected");
            refreshUi();
            show("Automatic detection enabled");
        });

        Button debug = addButton(root, "View debug log");
        debug.setOnClickListener(v -> showDebugLog());

        loggingToggle = addButton(root, "Turn off debug logging");
        loggingToggle.setOnClickListener(v -> {
            SharedPreferences preferences = getSharedPreferences("state", MODE_PRIVATE);
            boolean enabled = !preferences.getBoolean("debug_logging_enabled", true);
            preferences.edit().putBoolean("debug_logging_enabled", enabled).apply();
            setMonitorLogging(enabled);
            refreshUi();
            show("Debug logging " + (enabled ? "enabled" : "disabled"));
        });

        overlaySizeLabel = new TextView(this);
        overlaySizeLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sizeLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sizeLabelParams.topMargin = dp(18);
        root.addView(overlaySizeLabel, sizeLabelParams);

        overlaySize = new SeekBar(this);
        overlaySize.setMin(MonitorService.OVERLAY_SCALE_MIN);
        overlaySize.setMax(MonitorService.OVERLAY_SCALE_MAX);
        overlaySize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                     boolean fromUser) {
                overlaySizeLabel.setText("Overlay size: " + progress + "%");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                int scale = seekBar.getProgress();
                getSharedPreferences("state", MODE_PRIVATE).edit()
                        .putInt(MonitorService.PREF_OVERLAY_SCALE, scale).apply();
                setMonitorScale(scale);
            }
        });
        root.addView(overlaySize, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (startAfterPermission && Settings.canDrawOverlays(this)) {
            startAfterPermission = false;
            continueStart();
        }
        refreshUi();
    }

    private void beginStart() {
        if (!Shizuku.pingBinder()) {
            show("Start Shizuku first");
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_REQUEST);
                return;
            }
        } catch (Exception e) {
            show("Shizuku is not ready");
            return;
        }
        continueStart();
    }

    private void continueStart() {
        if (!Settings.canDrawOverlays(this)) {
            startAfterPermission = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_REQUEST);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_REQUEST);
            return;
        }
        Intent service = new Intent(this, MonitorService.class);
        startForegroundService(service);
        getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("running", true).apply();
        refreshUi();
        moveTaskToBack(true);
    }

    private void stopMonitor() {
        Intent intent = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP);
        startService(intent);
        getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("running", false).apply();
        refreshUi();
    }

    private void pickApp() {
        Intent launcherApps = new Intent(Intent.ACTION_MAIN);
        launcherApps.addCategory(Intent.CATEGORY_LAUNCHER);
        Intent picker = new Intent(Intent.ACTION_PICK_ACTIVITY);
        picker.putExtra(Intent.EXTRA_INTENT, launcherApps);
        try {
            startActivityForResult(picker, PICK_APP_REQUEST);
        } catch (Exception e) {
            show("The system app picker is unavailable");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_APP_REQUEST || resultCode != RESULT_OK || data == null) return;
        ComponentName component = data.getComponent();
        if (component == null) return;
        String packageName = component.getPackageName();
        String label = packageName;
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(component, 0);
            CharSequence loaded = info.loadLabel(getPackageManager());
            if (loaded != null) label = loaded.toString();
        } catch (PackageManager.NameNotFoundException ignored) {}
        getSharedPreferences("state", MODE_PRIVATE).edit()
                .putString("target_package", packageName)
                .putString("target_label", label)
                .apply();
        noteMonitor("fixed target selected: " + label + " (" + packageName + ")");
        refreshUi();
        show("Monitoring " + label);
    }

    private void showDebugLog() {
        SharedPreferences preferences = getSharedPreferences("state", MODE_PRIVATE);
        String log = preferences.getBoolean("debug_logging_enabled", true)
                ? preferences.getString("debug_log", "No diagnostics yet. Start the monitor first.")
                : "Debug logging is turned off.";
        TextView text = new TextView(this);
        int pad = dp(16);
        text.setPadding(pad, pad, pad, pad);
        text.setTextIsSelectable(true);
        text.setText(log);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this)
                .setTitle("FPS Monitor diagnostics")
                .setView(scroll)
                .setPositiveButton("Copy", (dialog, which) -> copyLog(log))
                .setNeutralButton("Share", (dialog, which) -> shareLog(log))
                .setNegativeButton("Close", null)
                .show();
    }

    private void copyLog(String log) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("FPS Monitor diagnostics", log));
        show("Debug log copied");
    }

    private void shareLog(String log) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "FPS Monitor diagnostics")
                .putExtra(Intent.EXTRA_TEXT, log);
        startActivity(Intent.createChooser(share, "Share diagnostics"));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQUEST) continueStart();
    }

    private boolean isRunning() {
        return getSharedPreferences("state", MODE_PRIVATE).getBoolean("running", false);
    }

    private void noteMonitor(String note) {
        if (!isRunning()) return;
        Intent intent = new Intent(this, MonitorService.class)
                .setAction(MonitorService.ACTION_NOTE)
                .putExtra(MonitorService.EXTRA_NOTE, note);
        startService(intent);
    }

    private void setMonitorLogging(boolean enabled) {
        if (!isRunning()) return;
        Intent intent = new Intent(this, MonitorService.class)
                .setAction(MonitorService.ACTION_SET_LOGGING)
                .putExtra(MonitorService.EXTRA_ENABLED, enabled);
        startService(intent);
    }

    private void setMonitorScale(int scale) {
        if (!isRunning()) return;
        Intent intent = new Intent(this, MonitorService.class)
                .setAction(MonitorService.ACTION_SET_SCALE)
                .putExtra(MonitorService.EXTRA_SCALE, scale);
        startService(intent);
    }

    private void refreshUi() {
        boolean running = isRunning();
        status.setText(running ? "Monitoring is running" : "Monitoring is stopped");
        toggle.setText(running ? R.string.stop : R.string.start);
        SharedPreferences preferences = getSharedPreferences("state", MODE_PRIVATE);
        String packageName = preferences.getString("target_package", null);
        String label = preferences.getString("target_label", packageName);
        target.setText(packageName == null
                ? "Target: automatic foreground app"
                : "Target: " + label + "\n" + packageName);
        loggingToggle.setText(preferences.getBoolean("debug_logging_enabled", true)
                ? "Turn off debug logging" : "Turn on debug logging");
        int scale = preferences.getInt(MonitorService.PREF_OVERLAY_SCALE,
                MonitorService.OVERLAY_SCALE_DEFAULT);
        overlaySize.setProgress(scale);
        overlaySizeLabel.setText("Overlay size: " + scale + "%");
    }

    private Button addButton(LinearLayout root, String text) {
        Button button = new Button(this);
        button.setText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        root.addView(button, params);
        return button;
    }

    private void show(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }
}
