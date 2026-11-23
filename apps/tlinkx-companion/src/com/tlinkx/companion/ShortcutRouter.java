package com.tlinkx.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Routes Toyota Avanza steering-wheel Voice button presses (ACTION_VOICE_COMMAND via BT HFP)
 * into multi-tap shortcuts (1x, 2x, 3x, 4x within 650 ms).
 */
public final class ShortcutRouter {
    private ShortcutRouter() {}

    public static final String PREFS = "tlinkx_prefs";

    public static final String ACT_PLAY_PAUSE = "play_pause";
    public static final String ACT_SWITCH_APPS = "switch_apps";
    public static final String ACT_SPLIT_SCREEN = "split_screen";
    public static final String ACT_TOGGLE_THEME = "toggle_theme";
    public static final String ACT_TOGGLE_BLACKOUT = "toggle_blackout";
    public static final String ACT_NEXT_TRACK = "next_track";
    public static final String ACT_NONE = "none";

    public static final String[] ALL_ACTIONS = new String[]{
            ACT_PLAY_PAUSE,
            ACT_SWITCH_APPS,
            ACT_SPLIT_SCREEN,
            ACT_TOGGLE_THEME,
            ACT_TOGGLE_BLACKOUT,
            ACT_NEXT_TRACK,
            ACT_NONE
    };

    private static final long TAP_WINDOW_MS = 650L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int sTapCount = 0;
    private static Runnable sPendingRun = null;

    public static String getDefaultForSlot(int taps) {
        switch (taps) {
            case 1: return ACT_PLAY_PAUSE;
            case 2: return ACT_SWITCH_APPS;
            case 3: return ACT_SPLIT_SCREEN;
            case 4: return ACT_TOGGLE_THEME;
            default: return ACT_NONE;
        }
    }

    public static String getActionForSlot(Context ctx, int taps) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getString("voice_tap_" + taps, getDefaultForSlot(taps));
    }

    public static String cycleSlotAction(Context ctx, int taps) {
        String cur = getActionForSlot(ctx, taps);
        int idx = 0;
        for (int i = 0; i < ALL_ACTIONS.length; i++) {
            if (ALL_ACTIONS[i].equals(cur)) {
                idx = i;
                break;
            }
        }
        String next = ALL_ACTIONS[(idx + 1) % ALL_ACTIONS.length];
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("voice_tap_" + taps, next).apply();
        return next;
    }

    public static String labelForAction(String action) {
        if (ACT_PLAY_PAUSE.equals(action)) return "Play / Pause";
        if (ACT_SWITCH_APPS.equals(action)) return "Switch Maps <-> YTM";
        if (ACT_SPLIT_SCREEN.equals(action)) return "Split 70/30";
        if (ACT_TOGGLE_THEME.equals(action)) return "Dark / Light";
        if (ACT_TOGGLE_BLACKOUT.equals(action)) return "Phone OLED Off";
        if (ACT_NEXT_TRACK.equals(action)) return "Next Track";
        return "Disabled";
    }

    public static void onVoiceButtonPressed(Context ctx) {
        final Context appCtx = ctx.getApplicationContext();
        MAIN.post(() -> {
            sTapCount++;
            if (sPendingRun != null) {
                MAIN.removeCallbacks(sPendingRun);
            }
            sPendingRun = () -> {
                int taps = Math.min(sTapCount, 4);
                sTapCount = 0;
                sPendingRun = null;
                String action = getActionForSlot(appCtx, taps);
                executeAction(appCtx, action, "Voice " + taps + "x");
            };
            MAIN.postDelayed(sPendingRun, TAP_WINDOW_MS);
        });
    }

    public static void executeAction(Context ctx, String action, String sourceLabel) {
        if (ACT_NONE.equals(action)) return;
        final Context appCtx = ctx.getApplicationContext();
        new Thread(() -> {
            String feedback;
            if (ACT_PLAY_PAUSE.equals(action)) {
                CarDisplayController.sendMediaKey(appCtx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                feedback = "Play / Pause";
            } else if (ACT_NEXT_TRACK.equals(action)) {
                CarDisplayController.sendMediaKey(appCtx, KeyEvent.KEYCODE_MEDIA_NEXT);
                feedback = "Next Track";
            } else if (ACT_SWITCH_APPS.equals(action)) {
                feedback = "Switched -> " + CarDisplayController.quickSwitchMapsMusic(appCtx);
            } else if (ACT_SPLIT_SCREEN.equals(action)) {
                feedback = CarDisplayController.toggleSplitScreen(appCtx);
            } else if (ACT_TOGGLE_THEME.equals(action)) {
                feedback = CarDisplayController.toggleDarkMode();
            } else if (ACT_TOGGLE_BLACKOUT.equals(action)) {
                boolean next = !CarDisplayController.isPhoneScreenBlackout();
                feedback = CarDisplayController.setPhoneScreenBlackout(next);
            } else {
                return;
            }
            final String toastMsg = "T-LinkX [" + sourceLabel + "]: " + feedback;
            MAIN.post(() -> Toast.makeText(appCtx, toastMsg, Toast.LENGTH_SHORT).show());
        }).start();
    }
}
