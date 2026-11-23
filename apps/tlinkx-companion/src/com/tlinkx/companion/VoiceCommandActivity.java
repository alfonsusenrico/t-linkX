package com.tlinkx.companion;

import android.app.Activity;
import android.os.Bundle;

/**
 * Transparent trampoline that captures Toyota steering-wheel Voice button
 * (android.intent.action.VOICE_COMMAND over Bluetooth HFP) and forwards it to ShortcutRouter.
 */
public class VoiceCommandActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ShortcutRouter.onVoiceButtonPressed(this);
        finish();
        overridePendingTransition(0, 0);
    }
}
