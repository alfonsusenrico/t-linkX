package com.tlinkx.companion;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores and resolves the 4 customizable Car Home Launcher app slots.
 * Defaults: ApexOBD, Google Maps, YouTube, YouTube Music.
 */
public final class CarLauncherConfig {
    private CarLauncherConfig() {}

    public static final String PREFS = "tlinkx_prefs";
    public static final int SLOT_COUNT = 4;

    public static final String[] DEFAULT_PACKAGES = new String[]{
            "com.apexobd.app",
            "com.google.android.apps.maps",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music"
    };

    public static final String[] DEFAULT_FALLBACK_NAMES = new String[]{
            "ApexOBD",
            "Google Maps",
            "YouTube",
            "YouTube Music"
    };

    public static final String[] SLOT_ACCENTS = new String[]{
            "#10B981", // Emerald for ApexOBD / slot 1
            "#0EA5E9", // Sky Blue for Google Maps / slot 2
            "#EF4444", // Red for YouTube / slot 3
            "#F97316"  // Amber/Orange for YouTube Music / slot 4
    };

    public static final class AppEntry {
        public final String packageName;
        public final String label;
        public final Drawable icon;

        public AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    public static String getSlotPackage(Context ctx, int slotIndex) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String def = (slotIndex >= 0 && slotIndex < SLOT_COUNT) ? DEFAULT_PACKAGES[slotIndex] : "";
        return sp.getString("launcher_slot_" + slotIndex, def);
    }

    public static void setSlotPackage(Context ctx, int slotIndex, String pkg) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("launcher_slot_" + slotIndex, pkg)
                .apply();
    }

    public static AppEntry resolveSlot(Context ctx, int slotIndex) {
        String pkg = getSlotPackage(ctx, slotIndex);
        PackageManager pm = ctx.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String label = String.valueOf(pm.getApplicationLabel(ai));
            Drawable icon = pm.getApplicationIcon(ai);
            return new AppEntry(pkg, label, icon);
        } catch (Exception ignored) {
            String fallback = (slotIndex >= 0 && slotIndex < SLOT_COUNT) ? DEFAULT_FALLBACK_NAMES[slotIndex] : pkg;
            return new AppEntry(pkg, fallback, null);
        }
    }

    public static List<AppEntry> loadInstalledLaunchableApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(mainIntent, 0);

        List<AppEntry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : resolved) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || seen.contains(pkg) || ctx.getPackageName().equals(pkg)) continue;
            seen.add(pkg);
            String label = String.valueOf(ri.loadLabel(pm));
            Drawable icon = ri.loadIcon(pm);
            result.add(new AppEntry(pkg, label, icon));
        }

        Collections.sort(result, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return result;
    }
}
