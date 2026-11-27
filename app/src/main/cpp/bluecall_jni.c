/*
 * BlueCall native audio bridge.
 *
 * Exposes three small objects to Kotlin through opaque handles:
 *   - Opus encoder (VOIP mode, DTX, mono)
 *   - Opus decoder (with packet-loss concealment when no packet is available)
 *   - Voice processor: wind/rumble low-cut biquad + RNNoise, applied in place
 *
 * All calls are made from the app's dedicated audio threads; none of these
 * objects are shared between threads.
 */
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <opus.h>
#include <rnnoise.h>

#define JNI_FN(name) Java_com_codemew_bluecall_audio_NativeAudio_##name

#define RNN_FRAME 480 /* RNNoise works on 10 ms frames at 48 kHz */
#define MAX_FRAME 5760 /* 120 ms at 48 kHz, Opus maximum */

#define FLAG_LOW_CUT 1
#define FLAG_DENOISE 2

/* ------------------------------------------------------------------------- */
/* Opus encoder                                                              */
/* ------------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL JNI_FN(opusEncoderCreate)(JNIEnv *env, jclass clazz,
                                                  jint sampleRate, jint bitrate,
                                                  jint complexity, jboolean dtx) {
    (void) env;
    (void) clazz;
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sampleRate, 1, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || enc == NULL) return 0;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_VBR(1));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_MAX_BANDWIDTH(OPUS_BANDWIDTH_SUPERWIDEBAND));
    opus_encoder_ctl(enc, OPUS_SET_DTX(dtx ? 1 : 0));
    /* The Bluetooth L2CAP channel is reliable, so in-band FEC only wastes bits. */
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(0));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(0));
    return (jlong) (intptr_t) enc;
}

JNIEXPORT jint JNICALL JNI_FN(opusEncode)(JNIEnv *env, jclass clazz, jlong handle,
                                          jshortArray pcm, jint frameSize,
                                          jbyteArray out) {
    (void) clazz;
    OpusEncoder *enc = (OpusEncoder *) (intptr_t) handle;
    if (enc == NULL || frameSize <= 0 || frameSize > MAX_FRAME) return OPUS_BAD_ARG;
    if ((*env)->GetArrayLength(env, pcm) < frameSize) return OPUS_BAD_ARG;
    jsize outLen = (*env)->GetArrayLength(env, out);
    jshort *in = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    if (in == NULL) return OPUS_ALLOC_FAIL;
    jbyte *dst = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (dst == NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }
    opus_int32 n = opus_encode(enc, (const opus_int16 *) in, frameSize,
                               (unsigned char *) dst, outLen);
    (*env)->ReleasePrimitiveArrayCritical(env, out, dst, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL JNI_FN(opusEncoderSetBitrate)(JNIEnv *env, jclass clazz,
                                                     jlong handle, jint bitrate) {
    (void) env;
    (void) clazz;
    OpusEncoder *enc = (OpusEncoder *) (intptr_t) handle;
    if (enc != NULL) opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
}

JNIEXPORT void JNICALL JNI_FN(opusEncoderDestroy)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusEncoder *enc = (OpusEncoder *) (intptr_t) handle;
    if (enc != NULL) opus_encoder_destroy(enc);
}

/* ------------------------------------------------------------------------- */
/* Opus decoder                                                              */
/* ------------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL JNI_FN(opusDecoderCreate)(JNIEnv *env, jclass clazz, jint sampleRate) {
    (void) env;
    (void) clazz;
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(sampleRate, 1, &err);
    if (err != OPUS_OK || dec == NULL) return 0;
    return (jlong) (intptr_t) dec;
}

/*
 * Decodes one packet into pcm. When data is null (or len is 0) the decoder
 * runs packet-loss concealment / comfort noise for frameSize samples.
 */
JNIEXPORT jint JNICALL JNI_FN(opusDecode)(JNIEnv *env, jclass clazz, jlong handle,
                                          jbyteArray data, jint offset, jint len,
                                          jshortArray pcm, jint frameSize) {
    (void) clazz;
    OpusDecoder *dec = (OpusDecoder *) (intptr_t) handle;
    if (dec == NULL || frameSize <= 0 || frameSize > MAX_FRAME) return OPUS_BAD_ARG;
    if ((*env)->GetArrayLength(env, pcm) < frameSize) return OPUS_BAD_ARG;

    jbyte *src = NULL;
    if (data != NULL && len > 0) {
        jsize dataLen = (*env)->GetArrayLength(env, data);
        if (offset < 0 || len > dataLen - offset) return OPUS_BAD_ARG;
        src = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
        if (src == NULL) return OPUS_ALLOC_FAIL;
    }
    jshort *out = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    if (out == NULL) {
        if (src != NULL) (*env)->ReleasePrimitiveArrayCritical(env, data, src, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }
    int n = opus_decode(dec,
                        src != NULL ? (const unsigned char *) src + offset : NULL,
                        src != NULL ? len : 0,
                        (opus_int16 *) out, frameSize, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, out, 0);
    if (src != NULL) (*env)->ReleasePrimitiveArrayCritical(env, data, src, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL JNI_FN(opusDecoderDestroy)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusDecoder *dec = (OpusDecoder *) (intptr_t) handle;
    if (dec != NULL) opus_decoder_destroy(dec);
}

/* ------------------------------------------------------------------------- */
/* Voice processor: low-cut filter + RNNoise                                 */
/* ------------------------------------------------------------------------- */

typedef struct {
    /* 2nd-order Butterworth high-pass, transposed direct form II */
    float b0, b1, b2, a1, a2;
    float z1, z2;
    DenoiseState *rnn;
    float rnnIn[RNN_FRAME];
    float rnnOut[RNN_FRAME];
    float lastVad;
} VoiceProcessor;

static void design_highpass(VoiceProcessor *p, float sampleRate, float cutoff) {
    const float q = 0.70710678f;
    float w0 = 2.0f * (float) M_PI * cutoff / sampleRate;
    float cs = cosf(w0);
    float alpha = sinf(w0) / (2.0f * q);
    float a0 = 1.0f + alpha;
    p->b0 = ((1.0f + cs) / 2.0f) / a0;
    p->b1 = (-(1.0f + cs)) / a0;
    p->b2 = ((1.0f + cs) / 2.0f) / a0;
    p->a1 = (-2.0f * cs) / a0;
    p->a2 = (1.0f - alpha) / a0;
    p->z1 = p->z2 = 0.0f;
}

static inline float clamp16(float v) {
    if (v > 32767.0f) return 32767.0f;
    if (v < -32768.0f) return -32768.0f;
    return v;
}

JNIEXPORT jlong JNICALL JNI_FN(processorCreate)(JNIEnv *env, jclass clazz,
                                                jint sampleRate, jfloat cutoffHz) {
    (void) env;
    (void) clazz;
    VoiceProcessor *p = calloc(1, sizeof(VoiceProcessor));
    if (p == NULL) return 0;
    design_highpass(p, (float) sampleRate, cutoffHz);
    /* RNNoise only supports 48 kHz; at other rates the denoise stage is skipped. */
    p->rnn = sampleRate == 48000 ? rnnoise_create(NULL) : NULL;
    return (jlong) (intptr_t) p;
}

/*
 * Processes `length` samples of pcm in place. `flags` selects the stages.
 * The denoise stage needs `length` to be a multiple of 480; any remainder is
 * left untouched by RNNoise. Returns the last voice-activity probability
 * (0..1) from RNNoise, or -1 when it did not run.
 */
JNIEXPORT jfloat JNICALL JNI_FN(processorProcess)(JNIEnv *env, jclass clazz, jlong handle,
                                                  jshortArray pcm, jint length, jint flags) {
    (void) clazz;
    VoiceProcessor *p = (VoiceProcessor *) (intptr_t) handle;
    if (p == NULL || length <= 0) return -1.0f;
    if ((*env)->GetArrayLength(env, pcm) < length) return -1.0f;
    jshort *s = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    if (s == NULL) return -1.0f;

    if (flags & FLAG_LOW_CUT) {
        float z1 = p->z1, z2 = p->z2;
        for (int i = 0; i < length; i++) {
            float x = (float) s[i];
            float y = p->b0 * x + z1;
            z1 = p->b1 * x - p->a1 * y + z2;
            z2 = p->b2 * x - p->a2 * y;
            s[i] = (jshort) lrintf(clamp16(y));
        }
        p->z1 = z1;
        p->z2 = z2;
    }

    float vad = -1.0f;
    if ((flags & FLAG_DENOISE) && p->rnn != NULL) {
        for (int off = 0; off + RNN_FRAME <= length; off += RNN_FRAME) {
            for (int i = 0; i < RNN_FRAME; i++) p->rnnIn[i] = (float) s[off + i];
            vad = rnnoise_process_frame(p->rnn, p->rnnOut, p->rnnIn);
            for (int i = 0; i < RNN_FRAME; i++) s[off + i] = (jshort) lrintf(clamp16(p->rnnOut[i]));
        }
        p->lastVad = vad;
    }

    (*env)->ReleasePrimitiveArrayCritical(env, pcm, s, 0);
    return vad;
}

JNIEXPORT void JNICALL JNI_FN(processorDestroy)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    VoiceProcessor *p = (VoiceProcessor *) (intptr_t) handle;
    if (p == NULL) return;
    if (p->rnn != NULL) rnnoise_destroy(p->rnn);
    free(p);
}

JNIEXPORT jstring JNICALL JNI_FN(opusVersion)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, opus_get_version_string());
}
