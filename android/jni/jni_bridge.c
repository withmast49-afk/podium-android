// JNI glue between dev.podium.android.PodiumNative (Kotlin) and the C entry points
// that libPodiumNative.so (the Swift emulator core, Podium/AndroidBridge) exports.
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Implemented in Swift.
extern int podium_bridge_version(void);
extern int podium_inspect_ipsw(const char *path, char *out, int capacity);
extern int podium_start(const char *ipsw, const char *dataDir, const char *assetsDir);
extern int podium_power_off(void);
extern int podium_status(char *out, int capacity);
extern int podium_log_tail(char *out, int capacity);
extern int podium_frame_width(void);
extern int podium_frame_height(void);
extern int podium_copy_frame(void *buffer, int capacity);
extern void podium_touch(int phase, int touchID, double x, double y);
extern void podium_button(int which, int pressed);

#define JNI(name) Java_dev_podium_android_PodiumNative_##name

JNIEXPORT jint JNICALL JNI(bridgeVersion)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return podium_bridge_version();
}

JNIEXPORT jstring JNICALL JNI(inspect)(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    char summary[512];
    summary[0] = 0;
    int code = podium_inspect_ipsw(chars, summary, (int)sizeof(summary));
    (*env)->ReleaseStringUTFChars(env, path, chars);
    char line[600];
    snprintf(line, sizeof(line), "%d|%s", code, summary);
    return (*env)->NewStringUTF(env, line);
}

JNIEXPORT jint JNICALL JNI(start)(JNIEnv *env, jclass cls, jstring ipsw, jstring dataDir, jstring assetsDir) {
    (void)cls;
    const char *a = (*env)->GetStringUTFChars(env, ipsw, NULL);
    const char *b = (*env)->GetStringUTFChars(env, dataDir, NULL);
    const char *c = (*env)->GetStringUTFChars(env, assetsDir, NULL);
    int result = podium_start(a, b, c);
    (*env)->ReleaseStringUTFChars(env, ipsw, a);
    (*env)->ReleaseStringUTFChars(env, dataDir, b);
    (*env)->ReleaseStringUTFChars(env, assetsDir, c);
    return result;
}

JNIEXPORT jint JNICALL JNI(powerOff)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return podium_power_off();
}

// "stage|fraction|secondsRemaining|jit|instructionsPerSecond|message"
JNIEXPORT jstring JNICALL JNI(status)(JNIEnv *env, jclass cls) {
    (void)cls;
    char detail[1024];
    detail[0] = 0;
    int stage = podium_status(detail, (int)sizeof(detail));
    char line[1100];
    snprintf(line, sizeof(line), "%d|%s", stage, detail);
    return (*env)->NewStringUTF(env, line);
}

JNIEXPORT jstring JNICALL JNI(log)(JNIEnv *env, jclass cls) {
    (void)cls;
    char text[16384];
    text[0] = 0;
    podium_log_tail(text, (int)sizeof(text));
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jint JNICALL JNI(frameWidth)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return podium_frame_width();
}

JNIEXPORT jint JNICALL JNI(frameHeight)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return podium_frame_height();
}

static uint8_t *g_frame = NULL;
static size_t g_frame_capacity = 0;

// The guest frame is BGRA8; an ARGB_8888 Bitmap is R,G,B,A in memory, so swap the red
// and blue bytes while copying row by row (the Bitmap's stride may be padded).
JNIEXPORT jboolean JNICALL JNI(drawFrame)(JNIEnv *env, jclass cls, jobject bitmap) {
    (void)cls;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    int width = podium_frame_width(), height = podium_frame_height();
    if ((int)info.width != width || (int)info.height != height) return JNI_FALSE;

    size_t needed = (size_t)width * (size_t)height * 4;
    if (g_frame_capacity < needed) {
        free(g_frame);
        g_frame = (uint8_t *)malloc(needed);
        g_frame_capacity = g_frame ? needed : 0;
        if (!g_frame) return JNI_FALSE;
    }
    if ((size_t)podium_copy_frame(g_frame, (int)needed) != needed) return JNI_FALSE;

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return JNI_FALSE;
    for (int y = 0; y < height; y++) {
        const uint8_t *src = g_frame + (size_t)y * (size_t)width * 4;
        uint8_t *dst = (uint8_t *)pixels + (size_t)y * info.stride;
        for (int x = 0; x < width; x++) {
            dst[4 * x + 0] = src[4 * x + 2];
            dst[4 * x + 1] = src[4 * x + 1];
            dst[4 * x + 2] = src[4 * x + 0];
            dst[4 * x + 3] = 0xFF;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI(touch)(JNIEnv *env, jclass cls, jint phase, jint id, jdouble x, jdouble y) {
    (void)env; (void)cls;
    podium_touch(phase, id, x, y);
}

JNIEXPORT void JNICALL JNI(button)(JNIEnv *env, jclass cls, jint which, jboolean pressed) {
    (void)env; (void)cls;
    podium_button(which, pressed ? 1 : 0);
}
