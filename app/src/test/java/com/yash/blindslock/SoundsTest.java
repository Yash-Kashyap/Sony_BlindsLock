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

    @Test
    public void volumeFollowsSlider() {
        Context c = RuntimeEnvironment.getApplication();
        Prefs p = new Prefs(c);
        p.setSoundVolume(100);
        org.junit.Assert.assertEquals(1f, Sounds.volume(c), 0.001f);
        p.setSoundVolume(50);
        org.junit.Assert.assertEquals(0.25f, Sounds.volume(c), 0.001f);
        p.setSoundVolume(0);
        p.setSounds(true);
        c.getSystemService(AudioManager.class).setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        Sounds.init(c);
        shadowOf(Sounds.poolForTest()).clearPlayed();
        Sounds.playUnlock(c);
        assertFalse("0 % plays nothing", shadowOf(Sounds.poolForTest()).wasResourcePlayed(R.raw.unlock));
    }

    /** The click must start right away: no silence at the head of either file. */
    @Test
    public void clicksStartImmediately() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        for (int id : new int[]{R.raw.lock, R.raw.unlock}) {
            byte[] b;
            try (java.io.InputStream in = c.getResources().openRawResource(id)) {
                b = in.readAllBytes();
            }
            int first = -1;
            for (int i = 44; i + 1 < b.length; i += 2) { // 16-bit PCM after the WAV header
                int v = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
                if (Math.abs(v) > 330) { first = (i - 44) / 2; break; }
            }
            assertTrue("click found", first >= 0);
            assertTrue("click starts after " + first / 44.1 + " ms", first / 44.1 < 3);
        }
    }

    @Test
    public void warmUpPlaysOnceAndOnlyWhenSoundsAreOn() {
        Context c = RuntimeEnvironment.getApplication();
        new Prefs(c).setSounds(true);
        c.getSystemService(AudioManager.class).setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        Sounds.init(c);
        Sounds.resetWarmForTest();
        shadowOf(Sounds.poolForTest()).clearPlayed();
        Sounds.warm(c);
        assertTrue(shadowOf(Sounds.poolForTest()).wasResourcePlayed(R.raw.warm));
        shadowOf(Sounds.poolForTest()).clearPlayed();
        Sounds.warm(c);
        assertFalse("rate-limited", shadowOf(Sounds.poolForTest()).wasResourcePlayed(R.raw.warm));
    }
}
