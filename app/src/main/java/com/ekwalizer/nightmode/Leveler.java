package com.ekwalizer.nightmode;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;

import java.lang.reflect.Constructor;
import java.util.UUID;

/**
 * Owns one audio effect attached to the global output mix (session 0), so it processes
 * every app's audio before it reaches HDMI.
 *
 * DynamicsProcessing engine (default): a single full-band compressor with slow release acts
 * as a leveler (loud videos pushed down, quiet ones lifted by post gain), followed by a
 * limiter. AOSP runs it on STFT blocks (see FRAME_MS): with the 2048-sample block it
 * measured clean (SINAD ~66 dB, spurs < -88 dB, no extra crackle on speech) and leveled
 * speech from a 20 dB spread to 15/9/3 dB.
 *
 * LoudnessEnhancer engine: makeup gain plus AOSP's adaptive time-domain compressor. No
 * latency and clean, but it levels much less (20 -> 15 dB at most).
 */
final class Leveler {
    static final int PRESET_SOFT = 0;
    static final int PRESET_MEDIUM = 1;
    static final int PRESET_STRONG = 2;

    /** Higher priority wins control of the effect if another app also grabs session 0. */
    private static final int PRIORITY = 1000;
    private static final int OUTPUT_MIX_SESSION = 0;
    private static final int CHANNELS = 2;
    /**
     * Requested DynamicsProcessing frame. AOSP rounds it up to a power-of-two block (measured:
     * 30 and 40 ms gave identical output), so at 48 kHz this is 2048 samples ≈ 43 ms of added
     * latency. Measured on the emulator: 256 (5 ms) = robot voice; 1024 (20 ms) = clean tone but
     * audible crackle on speech with the Medium preset; 2048 = clean on speech and tones in
     * all presets. 43 ms of audio lag is within broadcast lip-sync tolerance (~60 ms).
     */
    static final float FRAME_MS = 40f;

    /** Debug-build test overrides (AudioTestActivity); 0 = use FRAME_MS / the preset. */
    static volatile float debugFrameMs;
    static volatile int debugTargetGainMb;

    /** Amlogic vendor "avl" (auto volume leveling) effect, from /vendor/etc/audio_effects.xml. */
    private static final UUID AVL_UUID = UUID.fromString("08246a2a-b2d3-4621-b804-42c9b478eb9d");
    /** AudioEffect.EFFECT_TYPE_NULL (hidden): match by implementation UUID only. */
    private static final UUID TYPE_NULL = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");

    /** LoudnessEnhancer makeup gain per preset, millibels. */
    private static final int[] TARGET_GAIN_MB = {400, 800, 1200};

    //                                       soft    medium  strong
    private static final float[] THRESHOLD = {-24f,  -30f,   -36f};  // dBFS where compression starts
    private static final float[] RATIO     = {3f,    6f,     12f};
    private static final float[] ATTACK    = {20f,   10f,    5f};    // ms
    private static final float[] RELEASE   = {400f,  600f,   900f};  // ms
    private static final float[] MAKEUP    = {6f,    10f,    15f};   // dB lift for quiet content
    private static final float[] CEILING   = {-6f,   -8f,    -10f};  // limiter threshold, dBFS

    private AudioEffect effect;
    private String engine;

    /** Attaches the effect. Returns a human-readable status; throws if the device refuses. */
    String start(String engine, int preset) throws Exception {
        release();
        this.engine = engine;
        if (Prefs.ENGINE_AVL.equals(engine)) {
            effect = createVendorEffect(AVL_UUID);
        } else if (Prefs.ENGINE_LOUDNESS.equals(engine)) {
            LoudnessEnhancer le = new LoudnessEnhancer(OUTPUT_MIX_SESSION);
            le.setTargetGain(debugTargetGainMb > 0 ? debugTargetGainMb : TARGET_GAIN_MB[preset]);
            effect = le;
        } else {
            effect = new DynamicsProcessing(PRIORITY, OUTPUT_MIX_SESSION, buildConfig(preset));
        }
        int rc = effect.setEnabled(true);
        if (rc != AudioEffect.SUCCESS) {
            release();
            throw new IllegalStateException("setEnabled returned " + rc);
        }
        return describe();
    }

    /** Applies a new preset in place when possible; avoids a gap in processing. */
    String applyPreset(int preset) throws Exception {
        if (effect instanceof LoudnessEnhancer) {
            ((LoudnessEnhancer) effect).setTargetGain(TARGET_GAIN_MB[preset]);
            return describe();
        }
        if (effect instanceof DynamicsProcessing) {
            DynamicsProcessing dp = (DynamicsProcessing) effect;
            dp.setMbcBandAllChannelsTo(0, mbcBand(preset));
            dp.setLimiterAllChannelsTo(limiter(preset));
            return describe();
        }
        return start(engine, preset);
    }

    boolean isHealthy() {
        if (effect == null) return false;
        try {
            return effect.hasControl() && effect.getEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    void release() {
        if (effect != null) {
            try {
                effect.setEnabled(false);
            } catch (RuntimeException ignored) {
                // Effect may already be dead (audio server restart); release anyway.
            }
            effect.release();
            effect = null;
        }
    }

    /** Real added latency of the DynamicsProcessing engine: the power-of-two block AOSP uses. */
    static int dynamicsLatencyMs(int sampleRate) {
        int samples = Integer.highestOneBit(Math.max(1, Math.round(FRAME_MS * sampleRate / 1000f) - 1)) << 1;
        return Math.round(samples * 1000f / sampleRate);
    }

    String effectName() {
        return effect != null ? effect.getDescriptor().name : null;
    }

    private String describe() {
        AudioEffect.Descriptor d = effect.getDescriptor();
        return d.name + " (" + d.implementor + "), session 0, control="
                + effect.hasControl() + ", enabled=" + effect.getEnabled();
    }

    private static DynamicsProcessing.Config buildConfig(int preset) {
        DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                // AOSP libdynproc only implements variant 0; latency is set by FRAME_MS.
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS,
                false, 0,   // pre-EQ
                true, 1,    // multi-band compressor with one full-range band
                false, 0,   // post-EQ
                true)       // limiter
                .setPreferredFrameDuration(debugFrameMs > 0 ? debugFrameMs : FRAME_MS)
                .build();
        config.setMbcBandAllChannelsTo(0, mbcBand(preset));
        config.setLimiterAllChannelsTo(limiter(preset));
        return config;
    }

    private static DynamicsProcessing.MbcBand mbcBand(int p) {
        return new DynamicsProcessing.MbcBand(
                true,           // in use
                20000f,         // cutoff: whole audible range in one band
                ATTACK[p],
                RELEASE[p],
                RATIO[p],
                THRESHOLD[p],
                6f,             // knee width, dB
                -60f,           // noise gate threshold: don't lift silence/hiss
                2f,             // expander ratio below the gate
                0f,             // pre gain
                MAKEUP[p]);     // post gain
    }

    private static DynamicsProcessing.Limiter limiter(int p) {
        return new DynamicsProcessing.Limiter(
                true, true,
                0,              // link group: both channels limited together
                1f,             // attack, ms
                80f,            // release, ms
                10f,            // ratio
                CEILING[p],
                0f);            // post gain
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private static AudioEffect createVendorEffect(UUID uuid) throws Exception {
        Constructor<AudioEffect> ctor = AudioEffect.class.getDeclaredConstructor(
                UUID.class, UUID.class, int.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(TYPE_NULL, uuid, PRIORITY, OUTPUT_MIX_SESSION);
    }
}
