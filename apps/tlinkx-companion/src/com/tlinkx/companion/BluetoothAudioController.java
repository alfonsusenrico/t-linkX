package com.tlinkx.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.audiofx.DynamicsProcessing;

/**
 * Full 10-band car audio EQ + soft limiter using Android DynamicsProcessing,
 * with 8 curated automotive presets + live user-tunable Custom 10-band curve.
 */
public final class BluetoothAudioController {
    private static final String PREFS = "tlinkx_prefs";

    public static final String EQ_CAR_BALANCED = "car_balanced";
    public static final String EQ_DEEP_BASS = "deep_bass";
    public static final String EQ_VOCAL_CLARITY = "vocal_clarity";
    public static final String EQ_ROCK_POP = "rock_pop";
    public static final String EQ_ACOUSTIC_JAZZ = "acoustic_jazz";
    public static final String EQ_EDM_HIPHOP = "edm_hiphop";
    public static final String EQ_NIGHT_DRIVE = "night_drive";
    public static final String EQ_CUSTOM = "custom";
    public static final String EQ_OFF = "off";

    public static final String[] ALL_PRESETS = new String[]{
            EQ_CAR_BALANCED,
            EQ_DEEP_BASS,
            EQ_VOCAL_CLARITY,
            EQ_ROCK_POP,
            EQ_ACOUSTIC_JAZZ,
            EQ_EDM_HIPHOP,
            EQ_NIGHT_DRIVE,
            EQ_CUSTOM,
            EQ_OFF
    };

    public static final int BAND_COUNT = 10;
    public static final float[] BAND_CUTOFFS = new float[]{
            32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    };
    public static final String[] BAND_LABELS = new String[]{
            "32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"
    };

    private static volatile BluetoothAudioController sActiveInstance;

    private final Context ctx;
    private DynamicsProcessing dpEffect;
    private int savedVolume = -1;

    public BluetoothAudioController(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sActiveInstance = this;
    }

    public static BluetoothAudioController getActive() {
        return sActiveInstance;
    }

    public static String labelForPreset(String preset) {
        if (EQ_DEEP_BASS.equals(preset)) return "Deep Bass";
        if (EQ_VOCAL_CLARITY.equals(preset)) return "Vocal & Podcast";
        if (EQ_ROCK_POP.equals(preset)) return "Rock & Pop";
        if (EQ_ACOUSTIC_JAZZ.equals(preset)) return "Acoustic & Jazz";
        if (EQ_EDM_HIPHOP.equals(preset)) return "EDM & Hip-Hop";
        if (EQ_NIGHT_DRIVE.equals(preset)) return "Night Drive";
        if (EQ_CUSTOM.equals(preset)) return "Custom";
        if (EQ_OFF.equals(preset)) return "Flat / Off";
        return "Car Balanced";
    }

    public static float[] getGainsForPreset(Context ctx, String preset) {
        if (EQ_DEEP_BASS.equals(preset)) {
            return new float[]{5.5f, 4.5f, 2.0f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 2.0f, 1.5f};
        }
        if (EQ_VOCAL_CLARITY.equals(preset)) {
            return new float[]{-3.0f, -2.0f, -1.0f, 0.5f, 2.0f, 3.5f, 3.5f, 2.0f, 0.5f, -0.5f};
        }
        if (EQ_ROCK_POP.equals(preset)) {
            return new float[]{3.5f, 2.5f, 0.5f, -1.5f, -1.0f, 0.5f, 2.0f, 3.0f, 3.5f, 2.5f};
        }
        if (EQ_ACOUSTIC_JAZZ.equals(preset)) {
            return new float[]{1.5f, 1.0f, 0.0f, 1.0f, 1.5f, 1.5f, 1.0f, 1.5f, 2.5f, 2.0f};
        }
        if (EQ_EDM_HIPHOP.equals(preset)) {
            return new float[]{6.0f, 5.0f, 2.5f, -1.0f, -1.5f, 0.0f, 1.0f, 2.5f, 3.5f, 3.0f};
        }
        if (EQ_NIGHT_DRIVE.equals(preset)) {
            return new float[]{1.5f, 1.0f, -0.5f, 0.0f, 0.5f, 0.0f, -1.0f, -1.5f, -2.0f, -2.5f};
        }
        if (EQ_OFF.equals(preset)) {
            return new float[]{0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
        }
        if (EQ_CUSTOM.equals(preset)) {
            return loadCustomGains(ctx);
        }
        // Car Balanced: tight sub-bass, tame door boom @ 125-250Hz, clear mids & crisp highs
        return new float[]{2.5f, 1.5f, -1.5f, -1.0f, 0.0f, 0.5f, 1.5f, 2.0f, 2.5f, 2.0f};
    }

    public static float[] loadCustomGains(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float[] gains = new float[BAND_COUNT];
        for (int i = 0; i < BAND_COUNT; i++) {
            gains[i] = sp.getFloat("eq_custom_band_" + i, 0f);
        }
        return gains;
    }

    public static void saveCustomGains(Context ctx, float[] gains) {
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (int i = 0; i < BAND_COUNT && i < gains.length; i++) {
            ed.putFloat("eq_custom_band_" + i, gains[i]);
        }
        ed.putString("eq_preset", EQ_CUSTOM);
        ed.apply();
    }

    public synchronized String activateCarAudio(String preset, boolean autoVolume95) {
        StringBuilder sb = new StringBuilder();
        if (autoVolume95) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (savedVolume < 0) savedVolume = cur;
                int target = Math.max(1, Math.round(max * 0.95f));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                sb.append("Vol=").append(target).append("/").append(max).append(" ");
            }
        }
        sb.append(applyEqPreset(preset));
        return sb.toString().trim();
    }

    public synchronized String applyEqPreset(String preset) {
        if (EQ_OFF.equals(preset)) {
            releaseEffect();
            return "EQ=OFF";
        }
        float[] gains = getGainsForPreset(ctx, preset);
        return applyRawGains(gains, labelForPreset(preset));
    }

    public synchronized String applyRawGains(float[] gains, String label) {
        try {
            if (dpEffect == null) {
                DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2,
                        true, BAND_COUNT,
                        false, 0,
                        false, 0,
                        true);
                dpEffect = new DynamicsProcessing(0, 0, b.build());
                DynamicsProcessing.Limiter lim = new DynamicsProcessing.Limiter(
                        true, true, 0, 2.0f, 40.0f, 4.0f, -1.0f, 0.0f);
                dpEffect.setLimiterAllChannelsTo(lim);
            }

            DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, BAND_COUNT);
            for (int i = 0; i < BAND_COUNT; i++) {
                float g = (i < gains.length) ? Math.max(-12f, Math.min(12f, gains[i])) : 0f;
                eq.setBand(i, new DynamicsProcessing.EqBand(true, BAND_CUTOFFS[i], g));
            }
            dpEffect.setPreEqAllChannelsTo(eq);
            dpEffect.setEnabled(true);
            return "EQ=" + label;
        } catch (Throwable t) {
            releaseEffect();
            return "EQ_ERR:" + t.getClass().getSimpleName();
        }
    }

    public synchronized void deactivateCarAudio() {
        releaseEffect();
        if (savedVolume >= 0) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
            }
            savedVolume = -1;
        }
    }

    private void releaseEffect() {
        if (dpEffect != null) {
            try {
                dpEffect.setEnabled(false);
                dpEffect.release();
            } catch (Throwable ignored) {
            }
            dpEffect = null;
        }
    }
}
