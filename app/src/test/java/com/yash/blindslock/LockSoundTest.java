package com.yash.blindslock;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@Config(sdk = {28, 34})
public class LockSoundTest {
    private int answer = -1;

    private Context setUp() {
        Context c = RuntimeEnvironment.getApplication();
        new Prefs(c).setEnabled(true);
        new Prefs(c).setSounds(true);
        c.getSystemService(AudioManager.class).setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        Sounds.init(c);
        Sounds.playedForTest().clear();
        return c;
    }

    /** CRT Off's "about to lock" cue plays the click at once, and only once per screen-off. */
    @Test
    public void crtCuePlaysClickOnceAndAnswers() {
        Context c = setUp();
        LockService s = Robolectric.setupService(LockService.class);
        c.sendOrderedBroadcast(new Intent(LockService.ACTION_CRT_LOCKING), null, new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { answer = getResultCode(); }
        }, null, 0, null, null);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(LockService.LOCK_SOUND_TAKEN, answer);
        assertEquals(1, Collections.frequency(Sounds.playedForTest(), R.raw.lock));
        // the real "screen off" notice that follows must not click a second time
        c.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, Collections.frequency(Sounds.playedForTest(), R.raw.lock));
        s.onDestroy();
    }

    @Test
    public void screenOffStillClicksWithoutCrt() {
        Context c = setUp();
        LockService s = Robolectric.setupService(LockService.class);
        c.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, Collections.frequency(Sounds.playedForTest(), R.raw.lock));
        s.onDestroy();
    }

    @Test
    public void noClickWhenSoundsOff() {
        Context c = setUp();
        new Prefs(c).setSounds(false);
        LockService s = Robolectric.setupService(LockService.class);
        c.sendOrderedBroadcast(new Intent(LockService.ACTION_CRT_LOCKING), null, new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { answer = getResultCode(); }
        }, null, 0, null, null);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("CRT Off plays its own", 0, answer);
        assertEquals(0, Collections.frequency(Sounds.playedForTest(), R.raw.lock));
        s.onDestroy();
    }
}
