#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#define LOG_TAG "NDK_ENGINE"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT void JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_applyLutNative(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jintArray lutR_arr,
        jintArray lutG_arr,
        jintArray lutB_arr,
        jint lutSize) {

    AndroidBitmapInfo info;
    void* pixels;

    // 1. Get image info
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("AndroidBitmap_getInfo() failed !");
        return;
    }

    // 2. Lock the pixels in memory so we can edit them directly
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("AndroidBitmap_lockPixels() failed !");
        return;
    }

    // 3. Grab the LUT arrays from Java
    jint* lutR = env->GetIntArrayElements(lutR_arr, NULL);
    jint* lutG = env->GetIntArrayElements(lutG_arr, NULL);
    jint* lutB = env->GetIntArrayElements(lutB_arr, NULL);

    uint32_t* line = (uint32_t*) pixels;
    int width = info.width;
    int height = info.height;
    int lutSize2 = lutSize * lutSize;

    // 4. THE SPEED LOOP
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            uint32_t pixel = line[y * width + x];
            
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            float fX = r * (lutSize - 1) / 255.0f;
            float fY = g * (lutSize - 1) / 255.0f;
            float fZ = b * (lutSize - 1) / 255.0f;

            int x0 = (int) fX; int y0 = (int) fY; int z0 = (int) fZ;
            int x1 = x0 + 1; if (x1 >= lutSize) x1 = lutSize - 1;
            int y1 = y0 + 1; if (y1 >= lutSize) y1 = lutSize - 1;
            int z1 = z0 + 1; if (z1 >= lutSize) z1 = lutSize - 1;

            float dx = fX - x0; float dy = fY - y0; float dz = fZ - z0;
            float idx_x = 1.0f - dx; float idy = 1.0f - dy; float idz = 1.0f - dz;

            float w000 = idx_x * idy * idz;
            float w100 = dx * idy * idz;
            float w010 = idx_x * dy * idz;
            float w110 = dx * dy * idz;
            float w001 = idx_x * idy * dz;
            float w101 = dx * idy * dz;
            float w011 = idx_x * dy * dz;
            float w111 = dx * dy * dz;

            int i000 = x0 + y0 * lutSize + z0 * lutSize2;
            int i100 = x1 + y0 * lutSize + z0 * lutSize2;
            int i010 = x0 + y1 * lutSize + z0 * lutSize2;
            int i110 = x1 + y1 * lutSize + z0 * lutSize2;
            int i001 = x0 + y0 * lutSize + z1 * lutSize2;
            int i101 = x1 + y0 * lutSize + z1 * lutSize2;
            int i011 = x0 + y1 * lutSize + z1 * lutSize2;
            int i111 = x1 + y1 * lutSize + z1 * lutSize2;

            int outR = (int) (lutR[i000]*w000 + lutR[i100]*w100 + lutR[i010]*w010 + lutR[i110]*w110 + lutR[i001]*w001 + lutR[i101]*w101 + lutR[i011]*w011 + lutR[i111]*w111);
            int outG = (int) (lutG[i000]*w000 + lutG[i100]*w100 + lutG[i010]*w010 + lutG[i110]*w110 + lutG[i001]*w001 + lutG[i101]*w101 + lutG[i011]*w011 + lutG[i111]*w111);
            int outB = (int) (lutB[i000]*w000 + lutB[i100]*w100 + lutB[i010]*w010 + lutB[i110]*w110 + lutB[i001]*w001 + lutB[i101]*w101 + lutB[i011]*w011 + lutB[i111]*w111);

            // Write the pixel straight back into memory
            line[y * width + x] = (0xFF << 24) | (outR << 16) | (outG << 8) | outB;
        }
    }

    // 5. Clean up and unlock
    env->ReleaseIntArrayElements(lutR_arr, lutR, 0);
    env->ReleaseIntArrayElements(lutG_arr, lutG, 0);
    env->ReleaseIntArrayElements(lutB_arr, lutB, 0);
    AndroidBitmap_unlockPixels(env, bitmap);
}