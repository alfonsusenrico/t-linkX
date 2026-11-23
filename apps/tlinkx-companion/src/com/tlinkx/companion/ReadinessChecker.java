package com.tlinkx.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.widget.Toast;

/**
 * Checks phone readiness (Internet Data, Bluetooth, Location) before driving
 * and prompts the user with a one-tap Enable Missing option or Continue Anyway.
 */
public final class ReadinessChecker {
    private ReadinessChecker() {}

    public static final class Status {
        public final boolean dataOn;
        public final boolean bluetoothOn;
        public final boolean locationOn;

        public Status(boolean dataOn, boolean bluetoothOn, boolean locationOn) {
            this.dataOn = dataOn;
            this.bluetoothOn = bluetoothOn;
            this.locationOn = locationOn;
        }

        public boolean allReady() {
            return dataOn && bluetoothOn && locationOn;
        }
    }

    public static Status check(Context ctx) {
        return new Status(isInternetAvailable(ctx), isBluetoothEnabled(ctx), isLocationEnabled(ctx));
    }

    public static boolean isInternetAvailable(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null) return false;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isBluetoothEnabled(Context ctx) {
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
            return adapter != null && adapter.isEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isLocationEnabled(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void enableMissingViaRoot(Status st) {
        StringBuilder cmd = new StringBuilder();
        if (!st.dataOn) {
            cmd.append("svc data enable; ");
        }
        if (!st.bluetoothOn) {
            cmd.append("svc bluetooth enable; ");
        }
        if (!st.locationOn) {
            cmd.append("settings put secure location_mode 3; ");
        }
        if (cmd.length() > 0) {
            RootShell.runRoot(cmd.toString(), 4000);
        }
    }

    public static void showPromptIfNeeded(Activity activity, Runnable onProceed) {
        Status st = check(activity);
        if (st.allReady()) {
            if (onProceed != null) onProceed.run();
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("Some phone services are currently turned off:\n\n");
        if (!st.dataOn) {
            msg.append("\u2022  Internet Data (Wi-Fi / Mobile): OFF\n")
                    .append("    Required for Maps traffic, search, YouTube & YT Music.\n\n");
        }
        if (!st.bluetoothOn) {
            msg.append("\u2022  Bluetooth: OFF\n")
                    .append("    Required to stream audio to your Avanza speakers (BAM).\n\n");
        }
        if (!st.locationOn) {
            msg.append("\u2022  Location (GPS): OFF\n")
                    .append("    Required for Google Maps navigation.\n\n");
        }
        msg.append("You can enable them automatically now or continue with them disabled.");

        new AlertDialog.Builder(activity)
                .setTitle("Pre-Drive Readiness Check")
                .setMessage(msg.toString().trim())
                .setCancelable(true)
                .setPositiveButton("Enable Missing", (d, w) -> {
                    new Thread(() -> {
                        enableMissingViaRoot(st);
                        activity.runOnUiThread(() -> {
                            Toast.makeText(activity, "Enabled missing car services", Toast.LENGTH_SHORT).show();
                            if (onProceed != null) onProceed.run();
                        });
                    }).start();
                })
                .setNegativeButton("Continue Anyway", (d, w) -> {
                    Toast.makeText(activity, "Continuing — some car features may be limited", Toast.LENGTH_SHORT).show();
                    if (onProceed != null) onProceed.run();
                })
                .show();
    }
}
