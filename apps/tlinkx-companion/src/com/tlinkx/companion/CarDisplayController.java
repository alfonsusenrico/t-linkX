package com.tlinkx.companion;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CarDisplayController {
    private CarDisplayController() {}

    public static final String PRESET_NATIVE = "native_600x1024";
    public static final String PRESET_HIDPI = "hidpi_1080x1844";

    public static final String FIT_X_INSET = "x_inset";
    public static final String FIT_Y_INSET = "y_inset";
    public static final String FIT_FULL = "full";

    public static final int DEFAULT_TOUCH_ROT = 270;
    public static final String DEFAULT_TOUCH_FIT = FIT_X_INSET;
    public static final String DEFAULT_PRESET = PRESET_HIDPI;

    public static final String IDC_VIRT_DATA_PATH = "/data/system/devices/idc/Vendor_1d6b_Product_7101.idc";
    public static final String IDC_KSU_DIR = "/data/adb/modules/tlinkx-touch";
    public static final String IDC_VIRT_KSU_PATH = IDC_KSU_DIR + "/system/usr/idc/Vendor_1d6b_Product_7101.idc";
    public static final String TOUCH_CFG_PATH = "/data/local/tmp/tlinkx_touch.cfg";
    public static final String TOUCH_PID_PATH = "/data/local/tmp/tlinkx_touchd.pid";
    public static final String TOUCH_LOG_PATH = "/data/local/tmp/tlinkx_touchd.log";
    public static final String SAVED_BL_PATH = "/data/local/tmp/tlinkx_saved_bl";

    public static final String TLINK_PKG = "com.jvckenwood.tlink";
    public static final String MAPS_PKG = "com.google.android.apps.maps";
    private static final String[] YTM_CANDIDATES = new String[]{
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "app.rvx.android.apps.youtube.music"
    };

    private static volatile boolean sBlackoutActive = false;

    public static String buildVirtualTouchIdc(boolean orientationAware) {
        return "# T-LinkX Virtual Direct Touchscreen (uinput bridge for Toyota T-Link)\n"
                + "device.internal = 1\n"
                + "touch.deviceType = touchScreen\n"
                + "touch.orientationAware = " + (orientationAware ? "1" : "0") + "\n";
    }

    public static RootShell.Result installTouchEnvironment(Context ctx, boolean orientationAware) {
        String idc = buildVirtualTouchIdc(orientationAware);
        String nativeBin = ctx.getApplicationInfo().nativeLibraryDir + "/libtlinkx_touchd.so";
        String ksuBin = IDC_KSU_DIR + "/bin/tlinkx-touchd";
        String moduleProp = "id=tlinkx-touch\n"
                + "name=T-LinkX Zero-Touch Car Mode + Touch Bridge\n"
                + "version=v2.4.4\n"
                + "versionCode=16\n"
                + "author=T-LinkX\n"
                + "description=Zero-touch USB AOA auto-trigger, direct touchscreen bridge for Toyota T-Link, and boot failsafe.\n";
        String serviceSh = "#!/system/bin/sh\n"
                + "until [ \"$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 2; done\n"
                + "wm size reset\n"
                + "wm density reset\n"
                + "for b in /sys/class/backlight/*/brightness; do\n"
                + "  if [ -f \"$b\" ] && [ \"$(cat \"$b\")\" = \"0\" ]; then echo 400 > \"$b\"; fi\n"
                + "done\n"
                + "if [ -x " + ksuBin + " ]; then\n"
                + "  nohup " + ksuBin + " >/dev/null 2>&1 </dev/null &\n"
                + "fi\n";

        String cmd = "mkdir -p /data/system/devices/idc " + IDC_KSU_DIR + "/system/usr/idc " + IDC_KSU_DIR + "/bin && "
                + "rm -f /data/system/devices/idc/Vendor_ffff_Product_ffff.idc "
                + IDC_KSU_DIR + "/system/usr/idc/Vendor_ffff_Product_ffff.idc && "
                + "cp -f \"" + nativeBin + "\" " + ksuBin + " && chmod 755 " + ksuBin + " && "
                + "cat <<'EOF' > " + IDC_VIRT_DATA_PATH + "\n" + idc + "EOF\n"
                + "chmod 755 /data/system/devices /data/system/devices/idc && "
                + "chmod 644 " + IDC_VIRT_DATA_PATH + " && "
                + "cat <<'EOF' > " + IDC_VIRT_KSU_PATH + "\n" + idc + "EOF\n"
                + "chmod 644 " + IDC_VIRT_KSU_PATH + " && "
                + "cat <<'EOF' > " + IDC_KSU_DIR + "/module.prop\n" + moduleProp + "EOF\n"
                + "chmod 644 " + IDC_KSU_DIR + "/module.prop && "
                + "cat <<'EOF' > " + IDC_KSU_DIR + "/service.sh\n" + serviceSh + "EOF\n"
                + "chmod 755 " + IDC_KSU_DIR + "/service.sh && "
                + "appops set com.tlinkx.companion SYSTEM_ALERT_WINDOW allow && "
                + "dumpsys deviceidle whitelist +com.tlinkx.companion >/dev/null 2>&1 ; "
                + "settings put global force_resizable_activities 1 && "
                + "echo OK_INSTALLED";

        return RootShell.runRoot(cmd, 6000);
    }

    public static RootShell.Result writeTouchConfig(int rotDeg, String fitMode, boolean autoTrigger, String preset) {
        int xMin = 0, xMax = 65535, yMin = 0, yMax = 65535;
        if (FIT_X_INSET.equals(fitMode)) {
            xMin = 6946;
            xMax = 58561;
        } else if (FIT_Y_INSET.equals(fitMode)) {
            yMin = 6946;
            yMax = 58561;
        }
        String wmSize = PRESET_NATIVE.equals(preset) ? "600x1024" : "1080x1844";
        int wmDensity = PRESET_NATIVE.equals(preset) ? 160 : 280;
        String cfg = "rot=" + rotDeg + "\n"
                + "x_min=" + xMin + "\n"
                + "x_max=" + xMax + "\n"
                + "y_min=" + yMin + "\n"
                + "y_max=" + yMax + "\n"
                + "auto_trigger=" + (autoTrigger ? "1" : "0") + "\n"
                + "wm_size=" + wmSize + "\n"
                + "wm_density=" + wmDensity + "\n";
        String cmd = "cat <<'EOF' > " + TOUCH_CFG_PATH + "\n" + cfg + "EOF\n"
                + "chmod 644 " + TOUCH_CFG_PATH + " && echo CFG_OK";
        return RootShell.runRoot(cmd, 3000);
    }

    public static RootShell.Result startTouchDaemon(Context ctx) {
        String binPath = ctx.getApplicationInfo().nativeLibraryDir + "/libtlinkx_touchd.so";
        String cmd = "if [ -f " + TOUCH_PID_PATH + " ]; then "
                + "OLD=$(cat " + TOUCH_PID_PATH + "); "
                + "if [ -n \"$OLD\" ] && kill -0 \"$OLD\" 2>/dev/null; then "
                + "echo ALREADY_RUNNING:$OLD; exit 0; fi; fi; "
                + "if [ ! -x \"" + binPath + "\" ]; then echo BIN_MISSING:" + binPath + "; exit 2; fi; "
                + "nohup \"" + binPath + "\" >/dev/null 2>&1 </dev/null & "
                + "sleep 0.3; "
                + "if [ -f " + TOUCH_PID_PATH + " ]; then echo STARTED:$(cat " + TOUCH_PID_PATH + "); else echo START_CHECK_LOG; fi";
        return RootShell.runRoot(cmd, 5000);
    }

    public static RootShell.Result stopTouchDaemon() {
        String cmd = "if [ -f " + TOUCH_PID_PATH + " ]; then "
                + "OLD=$(cat " + TOUCH_PID_PATH + "); "
                + "if [ -n \"$OLD\" ]; then kill \"$OLD\" 2>/dev/null; fi; "
                + "rm -f " + TOUCH_PID_PATH + "; fi; "
                + "pkill -f libtlinkx_touchd.so 2>/dev/null; echo STOPPED";
        return RootShell.runRoot(cmd, 4000);
    }

    public static String getTouchDaemonStatus() {
        RootShell.Result r = RootShell.runRoot(
                "if [ -f " + TOUCH_PID_PATH + " ]; then "
                        + "P=$(cat " + TOUCH_PID_PATH + "); "
                        + "if [ -n \"$P\" ] && kill -0 \"$P\" 2>/dev/null; then echo RUNNING_PID_$P; exit 0; fi; fi; "
                        + "echo STOPPED",
                2500);
        return r.out.trim();
    }

    public static RootShell.Result applyCarDisplay(String preset) {
        String targetSize = PRESET_NATIVE.equals(preset) ? "600x1024" : "1080x1844";
        // Use sw>=600dp density (280dpi at 1080px = 617dp, 160dpi at 600px = 600dp)
        // so Android 15 unlocks true resizable 70/30 & 50/50 split-screen divider snap targets!
        String targetDensity = PRESET_NATIVE.equals(preset) ? "160" : "280";
        String cmd = "CUR=$(wm size | grep Override); "
                + "wm size " + targetSize + " && wm density " + targetDensity + " && "
                + "if echo \"$CUR\" | grep -q \"" + targetSize + "\"; then echo UNCHANGED; else echo CHANGED; fi";
        return RootShell.runRoot(cmd, 5000);
    }

    public static RootShell.Result resetDisplay() {
        setPhoneScreenBlackout(false);
        return RootShell.runRoot("wm size reset && wm density reset", 5000);
    }

    public static boolean launchApp(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                ctx.startActivity(i);
                return true;
            }
        } catch (Exception ignored) {
        }
        RootShell.Result r = RootShell.runRoot(
                "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && echo LAUNCHED",
                4000);
        return r.out.contains("LAUNCHED");
    }

    public static boolean launchOfficialTlink(Context ctx) {
        return launchApp(ctx, TLINK_PKG);
    }

    public static boolean isTlinkMirroringActive() {
        RootShell.Result r = RootShell.runRoot(
                "dumpsys display | grep -E 'DisplayDeviceInfo\\{\"Remote Control Display\".*state ON.*owner com\\.jvckenwood\\.tlink' | head -n 1",
                2500);
        return !r.out.trim().isEmpty();
    }

    public static String resolveYouTubeMusicPackage(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String cand : YTM_CANDIDATES) {
            try {
                pm.getPackageInfo(cand, 0);
                return cand;
            } catch (Exception ignored) {
            }
        }
        RootShell.Result r = RootShell.runRoot(
                "pm list packages | grep -i 'youtube\\.music' | head -n 1 | cut -d: -f2", 2500);
        String found = r.out.trim();
        return found.isEmpty() ? YTM_CANDIDATES[0] : found;
    }

    public static String getTopForegroundActivity() {
        RootShell.Result r = RootShell.runRoot(
                "dumpsys activity activities | grep -E 'topResumedActivity=|mResumedActivity:' | head -n 1",
                2000);
        return r.out.trim();
    }

    public static String getTopForegroundPackage() {
        String out = getTopForegroundActivity();
        Matcher m = Pattern.compile("\\s([a-zA-Z0-9_.]+)/").matcher(out);
        return m.find() ? m.group(1) : "";
    }

    public static String quickSwitchMapsMusic(Context ctx) {
        String ytmPkg = resolveYouTubeMusicPackage(ctx);
        String top = getTopForegroundPackage();
        if (MAPS_PKG.equals(top)) {
            launchApp(ctx, ytmPkg);
            return "YouTube Music";
        } else {
            launchApp(ctx, MAPS_PKG);
            return "Google Maps";
        }
    }

    public static synchronized String toggleSplitScreen(Context ctx) {
        String ytmPkg = resolveYouTubeMusicPackage(ctx);
        RootShell.Result state = RootShell.runRoot(
                "dumpsys activity service SystemUIService WMShell | grep -A 25 'StageCoordinator mDisplayId=0' | head -n 26",
                2500);
        String stageDump = state.out;

        // If split-screen is currently visible, exit cleanly back to fullscreen Maps
        if (stageDump.contains("isSplitVisible=true")) {
            int mapsTask = findTaskIdForPackage(MAPS_PKG);
            if (mapsTask > 0) {
                RootShell.runRoot("dumpsys activity service SystemUIService WMShell splitscreen exitSplitScreen " + mapsTask, 3000);
            }
            launchApp(ctx, MAPS_PKG);
            return "Split-Screen OFF (Maps Full)";
        }

        // If a stale/background split is still active, clear it first so moveToSideStage NEVER crashes SystemUI
        if (stageDump.contains("isSplitActive=true")) {
            Matcher existingTask = Pattern.compile("taskID=(\\d+)").matcher(stageDump);
            if (existingTask.find()) {
                RootShell.runRoot("dumpsys activity service SystemUIService WMShell splitscreen exitSplitScreen " + existingTask.group(1), 3000);
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }

        // Smart pairing: if user is currently in YouTube or ApexOBD, pair that app with Maps; otherwise Maps + YT Music
        String topPkg = getTopForegroundPackage();
        String sidePkg = ytmPkg;
        if ("com.google.android.youtube".equals(topPkg) || "com.apexobd.app".equals(topPkg)) {
            sidePkg = topPkg;
        }

        launchApp(ctx, sidePkg);
        int sideTask = waitForTaskId(sidePkg, 2000);
        try { Thread.sleep(650); } catch (InterruptedException ignored) {}

        launchApp(ctx, MAPS_PKG);
        waitForTaskId(MAPS_PKG, 1500);
        try { Thread.sleep(750); } catch (InterruptedException ignored) {}

        if (sideTask <= 0) {
            sideTask = findTaskIdForPackage(sidePkg);
        }
        if (sideTask <= 0) {
            return "Split-Screen failed — open Maps & Music once";
        }

        // Safety guard: never call moveToSideStage if sideTask is already inside StageCoordinator
        RootShell.Result guard = RootShell.runRoot(
                "dumpsys activity service SystemUIService WMShell | grep -A 25 'StageCoordinator mDisplayId=0' | grep 'taskID=" + sideTask + "'",
                2000);
        if (!guard.out.trim().isEmpty()) {
            RootShell.runRoot("dumpsys activity service SystemUIService WMShell splitscreen removeFromSideStage " + sideTask, 2500);
            try { Thread.sleep(450); } catch (InterruptedException ignored) {}
        }

        RootShell.Result res = RootShell.runRoot(
                "dumpsys activity service SystemUIService WMShell splitscreen moveToSideStage " + sideTask + " 1",
                3500);
        if (res.code == 0) {
            return "Split-Screen ON (Maps + " + ("com.google.android.youtube".equals(sidePkg) ? "YouTube" : "YT Music") + ")";
        }
        return "Split-Screen unavailable right now";
    }

    private static int waitForTaskId(String pkg, int maxMs) {
        int elapsed = 0;
        while (elapsed < maxMs) {
            int id = findTaskIdForPackage(pkg);
            if (id > 0) return id;
            try { Thread.sleep(250); } catch (InterruptedException ignored) { break; }
            elapsed += 250;
        }
        return findTaskIdForPackage(pkg);
    }

    private static int findTaskIdForPackage(String pkg) {
        RootShell.Result r = RootShell.runRoot(
                "dumpsys activity recents | grep -E 'Recent #[0-9]+:.*" + pkg + "' | head -n 1",
                2500);
        Matcher m = Pattern.compile("#(\\d+)\\s+type=").matcher(r.out);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        m = Pattern.compile("Task\\{[a-f0-9]+\\s+#(\\d+)").matcher(r.out);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    public static void sendMediaKey(Context ctx, int keyCode) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long now = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
                return;
            }
        } catch (Exception ignored) {
        }
        RootShell.runRoot("input keyevent " + keyCode, 2500);
    }

    public static String toggleDarkMode() {
        RootShell.Result cur = RootShell.runRoot("cmd uimode night", 2500);
        boolean isNight = cur.out.toLowerCase().contains("yes");
        String target = isNight ? "no" : "yes";
        RootShell.runRoot("cmd uimode night " + target, 3000);
        return isNight ? "Light Mode ON" : "Dark Mode ON";
    }

    public static boolean isPhoneScreenBlackout() {
        RootShell.Result r = RootShell.runRoot("if [ -f " + SAVED_BL_PATH + " ]; then echo 1; else echo 0; fi", 1500);
        sBlackoutActive = "1".equals(r.out.trim());
        return sBlackoutActive;
    }

    public static String setPhoneScreenBlackout(boolean off) {
        if (off) {
            String cmd = "BL=$(ls /sys/class/backlight/*/brightness 2>/dev/null | head -n 1); "
                    + "if [ -z \"$BL\" ]; then echo NO_BACKLIGHT_NODE; exit 1; fi; "
                    + "CUR=$(cat \"$BL\"); "
                    + "if [ \"$CUR\" -gt 0 ] 2>/dev/null; then echo \"$CUR\" > " + SAVED_BL_PATH + "; fi; "
                    + "echo 0 > \"$BL\" && echo BL_OFF:$BL";
            RootShell.Result r = RootShell.runRoot(cmd, 3000);
            if (r.out.contains("BL_OFF")) {
                sBlackoutActive = true;
                return "Phone Screen OFF (Mirroring Active)";
            }
            return "Backlight node unavailable";
        } else {
            String cmd = "BL=$(ls /sys/class/backlight/*/brightness 2>/dev/null | head -n 1); "
                    + "if [ -z \"$BL\" ]; then echo NO_BACKLIGHT_NODE; exit 0; fi; "
                    + "RESTORE=400; "
                    + "if [ -f " + SAVED_BL_PATH + " ]; then "
                    + "  V=$(cat " + SAVED_BL_PATH + "); "
                    + "  if [ \"$V\" -gt 20 ] 2>/dev/null; then RESTORE=$V; fi; "
                    + "fi; "
                    + "echo \"$RESTORE\" > \"$BL\" && rm -f " + SAVED_BL_PATH + " && echo BL_ON:$RESTORE";
            RootShell.runRoot(cmd, 3000);
            sBlackoutActive = false;
            return "Phone Screen ON";
        }
    }

    public static String getCurrentDisplaySummary() {
        RootShell.Result r = RootShell.runRoot("wm size && wm density", 3000);
        if (r.code != 0) {
            r = RootShell.run(new String[]{"sh", "-c", "wm size && wm density"}, 3000);
        }
        return r.out.trim();
    }

    public static String inspectTouchBridgeAndLog() {
        RootShell.Result r = RootShell.runRoot(
                "echo '=== DAEMON LOG ==='; "
                        + "if [ -f " + TOUCH_LOG_PATH + " ]; then tail -n 25 " + TOUCH_LOG_PATH + "; else echo '(no daemon log yet)'; fi; "
                        + "echo '=== INPUT READER (T-LinkX) ==='; "
                        + "dumpsys input | awk '/Device [0-9]+: T-LinkX Direct Touchscreen/{p=1} p{print} p && /PressureScale/{p=0}' | head -n 35",
                5000);
        return r.out.trim();
    }
}
