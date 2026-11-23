package com.tlinkx.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors USB AOA attachment/detachment for Toyota/JVCKENWOOD T-Link without stealing
 * USB_ACCESSORY_ATTACHED or ever force-stopping com.jvckenwood.tlink.
 *
 * Two-stage activation prevents interference with T-Link's Accessibility & MediaProjection dialogs:
 *  Stage 1 (USB AOA accessory attached): immediately apply 1080x1844 @ 320dpi + ensure touchd running.
 *  Stage 2 (T-Link "Remote Control Display" state ON): show CarDockOverlay + activate Bluetooth EQ/Vol.
 *  On detach/stop: hide overlay, restore EQ/Vol, restore phone AMOLED backlight, reset wm size/density.
 */
public class CarModeService extends Service {
    public static final String ACTION_START = "com.tlinkx.companion.ACTION_START";
    public static final String ACTION_STOP = "com.tlinkx.companion.ACTION_STOP";
    public static final String ACTION_RESET_NOW = "com.tlinkx.companion.ACTION_RESET_NOW";
    public static final String ACTION_APP_PICKED = "com.tlinkx.companion.ACTION_APP_PICKED";
    public static final String ACTION_HIDE_DOCK_FOR_LAUNCHER = "com.tlinkx.companion.ACTION_HIDE_DOCK_FOR_LAUNCHER";
    public static final String ACTION_AUTO_ATTACH = "com.tlinkx.companion.ACTION_AUTO_ATTACH";
    public static final String ACTION_AUTO_DETACH = "com.tlinkx.companion.ACTION_AUTO_DETACH";

    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String CHANNEL_ID = "tlinkx_car_mode";
    private static final int NOTIF_ID = 5101;
    private static final String PREFS = "tlinkx_prefs";

    public interface Callback {
        void onLog(String msg);
        void onStateUpdate(boolean monitoring, boolean carActive);
    }

    public class LocalBinder extends Binder {
        public CarModeService getService() {
            return CarModeService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final AtomicBoolean carDisplayActive = new AtomicBoolean(false);
    private final AtomicBoolean mirroringOverlaysActive = new AtomicBoolean(false);
    private final AtomicBoolean launcherShownThisConnection = new AtomicBoolean(false);
    private volatile Callback callback;
    private UsbManager usbManager;
    private Thread pollerThread;
    private CarDockOverlay dockOverlay;
    private BluetoothAudioController audioController;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                emit("[USB] Accessory detached -> resetting display, dock, audio & backlight!");
                new Thread(() -> deactivateCarMode()).start();
            } else if (ACTION_USB_STATE.equals(action) && monitoring.get()) {
                boolean accessory = intent.getBooleanExtra("accessory", false);
                boolean connected = intent.getBooleanExtra("connected", false);
                if (accessory && connected && !carDisplayActive.get()) {
                    new Thread(() -> {
                        if (isTlinkAccessoryAttached() && !carDisplayActive.get()) {
                            activateStage1DisplayAndTouch();
                        }
                    }).start();
                } else if (!connected && carDisplayActive.get()) {
                    emit("[USB] USB disconnected -> resetting display & backlight!");
                    new Thread(() -> deactivateCarMode()).start();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        dockOverlay = new CarDockOverlay(this);
        audioController = new BluetoothAudioController(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        filter.addAction(ACTION_USB_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_RESET_NOW.equals(action)) {
            emit("[Failsafe] Notification Reset tapped -> restoring display & backlight!");
            new Thread(() -> deactivateCarMode()).start();
            if (!monitoring.get()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action) || ACTION_AUTO_DETACH.equals(action)) {
            stopDriveSession(ACTION_STOP.equals(action));
            return START_NOT_STICKY;
        }
        if (ACTION_AUTO_ATTACH.equals(action)) {
            startMonitoring(true);
            return START_STICKY;
        }
        if (ACTION_APP_PICKED.equals(action)) {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (sp.getBoolean("dock_enabled", true) && dockOverlay != null) {
                boolean dockRight = sp.getBoolean("dock_right", true);
                dockOverlay.show(dockRight);
                emit("[Car Dock] App picked -> Dock shown (" + (dockRight ? "RIGHT/RHD" : "LEFT/LHD") + ")");
            }
            return START_STICKY;
        }
        if (ACTION_HIDE_DOCK_FOR_LAUNCHER.equals(action)) {
            if (dockOverlay != null) dockOverlay.hide();
            return START_STICKY;
        }
        startMonitoring(false);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        monitoring.set(false);
        if (pollerThread != null) pollerThread.interrupt();
        if (carDisplayActive.get() || mirroringOverlaysActive.get()) {
            deactivateCarMode();
        } else {
            if (dockOverlay != null) dockOverlay.hide();
            if (audioController != null) audioController.deactivateCarAudio();
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
        notifyState();
    }

    public boolean isMonitoring() {
        return monitoring.get();
    }

    public boolean isCarDisplayActive() {
        return carDisplayActive.get();
    }

    public void togglePreviewDock() {
        if (dockOverlay == null) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean rightSide = sp.getBoolean("dock_right", true);
        if (dockOverlay.isShowing()) {
            dockOverlay.hide();
            emit("[Car Dock] Preview hidden.");
        } else {
            dockOverlay.show(rightSide);
            emit("[Car Dock] Preview shown (" + (rightSide ? "RIGHT/RHD" : "LEFT/LHD") + ").");
        }
    }

    public void updateLiveEqPreset(String preset) {
        if (mirroringOverlaysActive.get() && audioController != null) {
            String res = audioController.applyEqPreset(preset);
            emit("[Audio] Live EQ updated: " + res);
        }
    }

    public void startMonitoring(boolean autoTriggered) {
        if (!monitoring.compareAndSet(false, true)) {
            updateNotification();
            return;
        }
        startForegroundCompat(autoTriggered
                ? "Avanza USB detected — preparing 1024x600 Car Mode..."
                : "Starting Touch Bridge + Watching Avanza USB...");
        notifyState();

        pollerThread = new Thread(() -> {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            int rot = sp.getInt("touch_rot", CarDisplayController.DEFAULT_TOUCH_ROT);
            String fit = sp.getString("touch_fit", CarDisplayController.DEFAULT_TOUCH_FIT);
            String preset = sp.getString("preset", CarDisplayController.DEFAULT_PRESET);
            boolean autoTrig = sp.getBoolean("auto_trigger", true);

            if (!autoTriggered) {
                RootShell.Result idcRes = CarDisplayController.installTouchEnvironment(this, false);
                emit("[Setup] Touch IDC + Netlink boot module: " + idcRes.out.trim());
                CarDisplayController.writeTouchConfig(rot, fit, autoTrig, preset);
                RootShell.Result daemonRes = CarDisplayController.startTouchDaemon(this);
                emit("[Touch Bridge] tlinkx-touchd: " + daemonRes.out.trim());
            } else {
                activateStage1DisplayAndTouch();
            }
            updateNotification();
            notifyState();

            pollLoop(autoTriggered);
        }, "TLinkUsbPoller");
        pollerThread.start();
    }

    public void stopDriveSession(boolean stopDaemonToo) {
        monitoring.set(false);
        if (pollerThread != null) {
            pollerThread.interrupt();
            pollerThread = null;
        }
        new Thread(() -> {
            deactivateCarMode();
            if (stopDaemonToo) {
                RootShell.Result stopRes = CarDisplayController.stopTouchDaemon();
                emit("[Touch Bridge] Daemon stopped: " + stopRes.out.trim());
            }
            emit("[Auto Car Mode] Drive session closed.");
            notifyState();
        }).start();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void pollLoop(boolean autoShutdownOnDetach) {
        int ticks = 0;
        int detachedTicks = 0;
        boolean everSawAccessory = false;
        while (monitoring.get()) {
            boolean tlinkPresent = isTlinkAccessoryAttached();
            if (tlinkPresent) {
                everSawAccessory = true;
                detachedTicks = 0;
                if (!carDisplayActive.get()) {
                    activateStage1DisplayAndTouch();
                }
            } else {
                if (carDisplayActive.get()) {
                    detachedTicks++;
                    // Wait up to 8s during initial AOA re-enumeration before treating as unplug;
                    // once accessory was seen, 2.5s (10 ticks) confirms physical unplug.
                    int limit = everSawAccessory ? 10 : 32;
                    if (detachedTicks >= limit) {
                        emit("[USB] T-Link accessory gone -> resetting everything!");
                        if (autoShutdownOnDetach) {
                            stopDriveSession(false);
                            return;
                        } else {
                            deactivateCarMode();
                        }
                    }
                }
            }

            // Stage 2: open 4-App Car Home & activate EQ once official T-Link's Remote Control Display is live
            if (tlinkPresent && carDisplayActive.get() && (ticks % 4 == 0)) {
                boolean mirroring = CarDisplayController.isTlinkMirroringActive();
                if (mirroring && !mirroringOverlaysActive.get()) {
                    activateStage2DockAndAudio();
                } else if (!mirroring && mirroringOverlaysActive.get()) {
                    deactivateStage2DockAndAudio();
                } else if (mirroring && mirroringOverlaysActive.get() && (ticks % 8 == 0)) {
                    syncDockWithForegroundApp();
                }
            }

            ticks++;
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                break;
            }
        }
    }

    private void syncDockWithForegroundApp() {
        if (dockOverlay == null) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!sp.getBoolean("dock_enabled", true)) {
            if (dockOverlay.isShowing()) dockOverlay.hide();
            return;
        }
        String top = CarDisplayController.getTopForegroundActivity();
        if (top.isEmpty()) return;
        boolean onLauncher = top.contains("CarLauncherActivity");
        boolean onSettingsOrEq = top.contains("com.tlinkx.companion/.MainActivity")
                || top.contains("com.tlinkx.companion/.EqTunerActivity");
        if (onLauncher || onSettingsOrEq) {
            if (dockOverlay.isShowing()) dockOverlay.hide();
        } else {
            if (!dockOverlay.isShowing()) {
                boolean dockRight = sp.getBoolean("dock_right", true);
                dockOverlay.show(dockRight);
                emit("[Car Dock] Auto-shown for active app.");
            }
        }
    }

    public boolean isTlinkAccessoryAttached() {
        if (usbManager == null) return false;
        UsbAccessory[] accs = usbManager.getAccessoryList();
        if (accs == null) return false;
        for (UsbAccessory a : accs) {
            if ("com.jvckenwood".equalsIgnoreCase(a.getManufacturer())
                    && "tlink".equalsIgnoreCase(a.getModel())) {
                return true;
            }
        }
        return false;
    }

    private synchronized void activateStage1DisplayAndTouch() {
        if (carDisplayActive.get()) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String preset = sp.getString("preset", CarDisplayController.DEFAULT_PRESET);
        int rot = sp.getInt("touch_rot", CarDisplayController.DEFAULT_TOUCH_ROT);
        String fit = sp.getString("touch_fit", CarDisplayController.DEFAULT_TOUCH_FIT);

        // Resize FIRST so official T-Link (auto-launched by Android USB_ACCESSORY_ATTACHED)
        // creates MediaProjection directly at 1080x1844 without ever needing force-stop!
        RootShell.Result r = CarDisplayController.applyCarDisplay(preset);
        CarDisplayController.writeTouchConfig(rot, fit, sp.getBoolean("auto_trigger", true), preset);
        RootShell.Result d = CarDisplayController.startTouchDaemon(this);

        if (r.code == 0) {
            carDisplayActive.set(true);
            emit("[Car Mode] T-Link USB attached -> display (" + preset + ") + touchd (" + d.out.trim() + ")");
            checkReadinessOnAutoConnect();
            emit("[Car Mode] Waiting for official T-Link mirroring to show Car Home Launcher...");
            updateNotification();
            notifyState();
        } else {
            emit("[Car Mode] Display switch failed (grant root in KernelSU): " + r.out.trim());
        }
    }

    private synchronized void activateStage2DockAndAudio() {
        if (mirroringOverlaysActive.get()) return;
        mirroringOverlaysActive.set(true);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String eqPreset = sp.getString("eq_preset", BluetoothAudioController.EQ_AVANZA_BALANCED);
        boolean autoVol = sp.getBoolean("auto_vol_95", true);

        if (audioController != null) {
            String audioRes = audioController.activateCarAudio(eqPreset, autoVol);
            emit("[Audio] " + audioRes);
        }

        // Open the clean Netflix-style 4-App Car Home Launcher ONCE per USB connection.
        // Side dock appears automatically once the driver taps an app card!
        if (launcherShownThisConnection.compareAndSet(false, true)) {
            if (dockOverlay != null) {
                dockOverlay.hide();
            }
            Intent launcher = new Intent(this, CarLauncherActivity.class);
            launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try {
                startActivity(launcher);
                emit("[Car Home] Mirroring live -> Opened 4-App Car Home Launcher.");
            } catch (Exception e) {
                emit("[Car Home] Launcher open error: " + e.getMessage());
            }
        }

        updateNotification();
        notifyState();
    }

    private synchronized void deactivateStage2DockAndAudio() {
        mirroringOverlaysActive.set(false);
        if (dockOverlay != null) {
            dockOverlay.hide();
        }
        if (audioController != null) {
            audioController.deactivateCarAudio();
        }
        CarDisplayController.setPhoneScreenBlackout(false);
        emit("[Car Mode] Mirroring paused -> Dock hidden & audio restored.");
        updateNotification();
        notifyState();
    }

    public synchronized void deactivateCarMode() {
        mirroringOverlaysActive.set(false);
        launcherShownThisConnection.set(false);
        if (dockOverlay != null) {
            dockOverlay.hide();
        }
        if (audioController != null) {
            audioController.deactivateCarAudio();
        }
        RootShell.Result r = CarDisplayController.resetDisplay();
        carDisplayActive.set(false);
        emit("[Car Mode] Display, backlight, dock & audio restored (exit=" + r.code + ")");
        if (monitoring.get()) {
            updateNotification();
        }
        notifyState();
    }

    private void checkReadinessOnAutoConnect() {
        ReadinessChecker.Status st = ReadinessChecker.check(this);
        if (st.allReady()) return;
        StringBuilder missing = new StringBuilder();
        if (!st.dataOn) missing.append("Data ");
        if (!st.bluetoothOn) missing.append("Bluetooth ");
        if (!st.locationOn) missing.append("Location ");
        String missTxt = missing.toString().trim().replace(" ", ", ");
        emit("[Readiness] Disabled on phone: " + missTxt);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        "tlinkx_readiness", "T-LinkX Readiness Alert", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 22, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, "tlinkx_readiness")
                    .setContentTitle("T-LinkX Pre-Drive Warning")
                    .setContentText("Disabled: " + missTxt + ". Tap to enable or continue anyway.")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(5102, n);
        }
    }

    private void emit(String msg) {
        Callback cb = callback;
        if (cb != null) cb.onLog(msg);
    }

    private void notifyState() {
        Callback cb = callback;
        if (cb != null) cb.onStateUpdate(monitoring.get(), carDisplayActive.get());
    }

    private void updateNotification() {
        String txt;
        if (mirroringOverlaysActive.get()) {
            txt = "MIRRORING LIVE (1024x600 + Direct Touch + Dock + EQ)";
        } else if (carDisplayActive.get()) {
            txt = "USB ATTACHED (1024x600 Ready) - Waiting for T-Link cast";
        } else {
            txt = "Direct Touch Bridge READY - Plug into Avanza USB";
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(txt));
        }
    }

    private void startForegroundCompat(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "T-LinkX Auto Car Mode", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification notif = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent resetIntent = new Intent(this, CarModeService.class);
        resetIntent.setAction(ACTION_RESET_NOW);
        PendingIntent resetPi = PendingIntent.getService(
                this, 1, resetIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("T-LinkX Companion v2.0.1")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(
                        null, "RESET DISPLAY", resetPi).build())
                .setOngoing(true)
                .build();
    }
}
