#include <jni.h>
#include <vector>
#include <stdio.h>
#include <string.h>
#include <setjmp.h>
#include "jpeglib.h"
#include <android/log.h>

#define LOG_TAG "NDK_ENGINE"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::vector<int> nativeLutR, nativeLutG, nativeLutB;
int nativeLutSize = 0;

struct my_error_mgr {
    struct jpeg_error_mgr pub;
    jmp_buf setjmp_buffer;
};

METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    longjmp(myerr->setjmp_buffer, 1);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject, jstring path) {
    const char *file_path = env->GetStringUTFChars(path, NULL);
    FILE *file = fopen(file_path, "r");
    env->ReleaseStringUTFChars(path, file_path);
    if (!file) return JNI_FALSE;

    nativeLutR.clear(); nativeLutG.clear(); nativeLutB.clear();
    nativeLutSize = 0;

    char line[256];
    while(fgets(line, sizeof(line), file)) {
        if (strncmp(line, "LUT_3D_SIZE", 11) == 0) {
            sscanf(line, "LUT_3D_SIZE %d", &nativeLutSize);
            int expected = nativeLutSize * nativeLutSize * nativeLutSize;
            nativeLutR.reserve(expected);
            nativeLutG.reserve(expected);
            nativeLutB.reserve(expected);
        }
        float r, g, b;
        if (sscanf(line, "%f %f %f", &r, &g, &b) == 3) {
            nativeLutR.push_back((int)(r * 255.0f));
            nativeLutG.push_back((int)(g * 255.0f));
            nativeLutB.push_back((int)(b * 255.0f));
        }
    }
    fclose(file);
    return nativeLutSize > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(JNIEnv* env, jobject, jstring inPath, jstring outPath) {
    if (nativeLutSize == 0) return JNI_FALSE;

    const char *in_file = env->GetStringUTFChars(inPath, NULL);
    const char *out_file = env->GetStringUTFChars(outPath, NULL);

    FILE *infile = fopen(in_file, "rb");
    if (!infile) {
        env->ReleaseStringUTFChars(inPath, in_file);
        env->ReleaseStringUTFChars(outPath, out_file);
        return JNI_FALSE;
    }

    FILE *outfile = fopen(out_file, "wb");
    if (!outfile) {
        fclose(infile);
        env->ReleaseStringUTFChars(inPath, in_file);
        env->ReleaseStringUTFChars(outPath, out_file);
        return JNI_FALSE;
    }

    // 1. Setup Decompressor (Reader)
    struct jpeg_decompress_struct cinfo_d;
    struct my_error_mgr jerr_d;
    cinfo_d.err = jpeg_std_error(&jerr_d.pub);
    jerr_d.pub.error_exit = my_error_exit;
    if (setjmp(jerr_d.setjmp_buffer)) {
        jpeg_destroy_decompress(&cinfo_d);
        fclose(infile); fclose(outfile);
        return JNI_FALSE;
    }
    jpeg_create_decompress(&cinfo_d);
    jpeg_stdio_src(&cinfo_d, infile);
    jpeg_read_header(&cinfo_d, TRUE);
    cinfo_d.out_color_space = JCS_RGB; // Force raw bytes into RGB
    jpeg_start_decompress(&cinfo_d);

    // 2. Setup Compressor (Writer)
    struct jpeg_compress_struct cinfo_c;
    struct my_error_mgr jerr_c;
    cinfo_c.err = jpeg_std_error(&jerr_c.pub);
    jerr_c.pub.error_exit = my_error_exit;
    if (setjmp(jerr_c.setjmp_buffer)) {
        jpeg_destroy_compress(&cinfo_c);
        jpeg_destroy_decompress(&cinfo_d);
        fclose(infile); fclose(outfile);
        return JNI_FALSE;
    }
    jpeg_create_compress(&cinfo_c);
    jpeg_stdio_dest(&cinfo_c, outfile);
    
    // Copy image specs from reader to writer
    cinfo_c.image_width = cinfo_d.output_width;
    cinfo_c.image_height = cinfo_d.output_height;
    cinfo_c.input_components = 3;
    cinfo_c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cinfo_c);
    jpeg_set_quality(&cinfo_c, 95, TRUE);
    jpeg_start_compress(&cinfo_c, TRUE);

    // 3. Precompute Math Maps
    int map[256];
    int lutMax = nativeLutSize - 1;
    for (int i = 0; i < 256; i++) {
        map[i] = (i * lutMax * 128) / 255;
    }
    int lutSize2 = nativeLutSize * nativeLutSize;
    int row_stride = cinfo_d.output_width * cinfo_d.output_components;
    
    // Allocate exactly ONE row of memory
    JSAMPARRAY buffer = (*cinfo_d.mem->alloc_sarray)((j_common_ptr) &cinfo_d, JPOOL_IMAGE, row_stride, 1);

    // 4. THE SCANLINE STREAMING LOOP
    while (cinfo_d.output_scanline < cinfo_d.output_height) {
        // Read 1 row
        jpeg_read_scanlines(&cinfo_d, buffer, 1);
        unsigned char* row = buffer[0];

        // Process 1 row
        for (int x = 0; x < row_stride; x += 3) {
            int r = row[x]; int g = row[x+1]; int b = row[x+2];

            int fX = map[r]; int fY = map[g]; int fZ = map[b];

            int x0 = fX >> 7; int y0 = fY >> 7; int z0 = fZ >> 7;
            int x1 = x0 + 1; if (x1 > lutMax) x1 = lutMax;
            int y1 = y0 + 1; if (y1 > lutMax) y1 = lutMax;
            int z1 = z0 + 1; if (z1 > lutMax) z1 = lutMax;

            int dx = fX & 0x7F; int dy = fY & 0x7F; int dz = fZ & 0x7F;
            int idx_x = 128 - dx; int idy = 128 - dy; int idz = 128 - dz;

            int w000 = idx_x * idy * idz; int w100 = dx * idy * idz;
            int w010 = idx_x * dy * idz;  int w110 = dx * dy * idz;
            int w001 = idx_x * idy * dz;  int w101 = dx * idy * dz;
            int w011 = idx_x * dy * dz;   int w111 = dx * dy * dz;

            int y0_idx = y0 * nativeLutSize; int y1_idx = y1 * nativeLutSize;
            int z0_idx = z0 * lutSize2;      int z1_idx = z1 * lutSize2;

            int i000 = x0 + y0_idx + z0_idx; int i100 = x1 + y0_idx + z0_idx;
            int i010 = x0 + y1_idx + z0_idx; int i110 = x1 + y1_idx + z0_idx;
            int i001 = x0 + y0_idx + z1_idx; int i101 = x1 + y0_idx + z1_idx;
            int i011 = x0 + y1_idx + z1_idx; int i111 = x1 + y1_idx + z1_idx;

            int outR = (nativeLutR[i000]*w000 + nativeLutR[i100]*w100 + nativeLutR[i010]*w010 + nativeLutR[i110]*w110 + nativeLutR[i001]*w001 + nativeLutR[i101]*w101 + nativeLutR[i011]*w011 + nativeLutR[i111]*w111) >> 21;
            int outG = (nativeLutG[i000]*w000 + nativeLutG[i100]*w100 + nativeLutG[i010]*w010 + nativeLutG[i110]*w110 + nativeLutG[i001]*w001 + nativeLutG[i101]*w101 + nativeLutG[i011]*w011 + nativeLutG[i111]*w111) >> 21;
            int outB = (nativeLutB[i000]*w000 + nativeLutB[i100]*w100 + nativeLutB[i010]*w010 + nativeLutB[i110]*w110 + nativeLutB[i001]*w001 + nativeLutB[i101]*w101 + nativeLutB[i011]*w011 + nativeLutB[i111]*w111) >> 21;

            // Safe clamping
            row[x]   = outR > 255 ? 255 : (outR < 0 ? 0 : outR);
            row[x+1] = outG > 255 ? 255 : (outG < 0 ? 0 : outG);
            row[x+2] = outB > 255 ? 255 : (outB < 0 ? 0 : outB);
        }
        
        // Save 1 row
        jpeg_write_scanlines(&cinfo_c, buffer, 1);
    }

    // 5. Cleanup
    jpeg_finish_compress(&cinfo_c);
    jpeg_destroy_compress(&cinfo_c);
    jpeg_finish_decompress(&cinfo_d);
    jpeg_destroy_decompress(&cinfo_d);
    
    fclose(infile);
    fclose(outfile);

    env->ReleaseStringUTFChars(inPath, in_file);
    env->ReleaseStringUTFChars(outPath, out_file);
    return JNI_TRUE;
}