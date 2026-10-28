package com.winlator.cmod.perf;

import android.app.GameManager;
import android.app.GameState;
import android.content.Context;
import android.os.Build;
import android.util.Log;

public final class GameModeSignal {
    private static final String TAG = "GameModeSignal";

    private GameModeSignal() {}

    public static void report(Context context, boolean playing) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            GameManager manager = context.getSystemService(GameManager.class);
            if (manager == null) {
                Log.w(TAG, "GameManager unavailable; gameplay hint not sent");
                return;
            }
            int mode = playing ? GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE : GameState.MODE_NONE;
            manager.setGameState(new GameState(false, mode));
            Log.d(TAG, "GameState sent: playing=" + playing + " mode=" + mode);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not report gameplay state: playing=" + playing, e);
        }
    }
}
