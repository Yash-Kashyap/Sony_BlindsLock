package com.yash.blindslock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.media.AudioManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@Config(sdk = {28, 34})
public class SoundsTest {
    @Test
    public void lockAndUnlockSoundsPlay() {
        Context c = RuntimeEnvironment.getApplication();
        c.getSystemService(AudioManager.class).setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        Sounds.playLock(c);
        Sounds.playUnlock(c);
        assertTrue(shadowOf(Sounds.poolForTest()).wasResourcePlayed(R.raw.lock));
        assertTrue(shadowOf(Sounds.poolForTest()).wasResourcePlayed(R.raw.unlock));
    }

    @Test
    public void silentWhenRingerSilentOrDisabled() {
        Context c = RuntimeEnvironment.getApplication();
        Sounds.init(c);
        shadowOf(Sounds.poolForTest()).clearPlayed();
        c.getSystemService(AudioManager.class).setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        Sounds.playUnlock(c);
        assertFalse(shadowOf(Sounds.poolForTest()).wasResourcePlayed(R.raw.unlock));
        c.getSystemService(AudioManager.class).setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        new Prefs(c).setSounds(false);
        Sounds.playLock(c);
        assertFalse(shadowOf(Sounds.poolForTest()).wasResourcePlayed(R.raw.lock));
    }
}
