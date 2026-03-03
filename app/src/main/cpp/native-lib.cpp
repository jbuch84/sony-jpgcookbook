#include <jni.h>
#include <vector>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include "jpeglib.h"
#include <android/log.h>

#define LOG_TAG "COOKBOOK_LOG"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::vector<int> nativeLutR, nativeLutG, nativeLutB;
int nativeLutSize = 0;
uint32_t random_seed = 12345; // Fast PRNG seed for Film Grain

struct my_error_mgr {
    struct jpeg_error_mgr pub;
    jmp_buf setjmp_buffer;
};

METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    longjmp(myerr->setjmp_buffer, 1);
}
METHODDEF(void) my_emit_message (j_common_ptr cinfo, int msg_level) {}
METHODDEF(void) my_output_message (j_common_ptr cinfo) {}

// Fast Integer Highlight Roll-Off (Soft Shoulder)
inline int rollOff(int v) {
    if (v > 200) return 200 + ((v - 200) * (310 - v)) / 110;
    return v;
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
            nativeLutR.reserve(expected); nativeLutG.reserve(expected); nativeLutB.reserve(expected);
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
Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(JNIEnv* env, jobject, jstring inPath, jstring outPath, jint scaleDenom, jint opacity, jint grain, jint vignette) {
    if (nativeLutSize == 0) return JNI_FALSE;
    const char *in_file = env->GetStringUTFChars(inPath, NULL);
    const char *out_file = env->GetStringUTFChars(outPath, NULL);
    
    FILE *infile = fopen(in_file, "rb");
    FILE *outfile = fopen(out_file, "wb");
    if (!infile || !outfile) {
        if (infile) fclose(infile); if (outfile) fclose(outfile);
        env->ReleaseStringUTFChars(inPath, in_file); env->ReleaseStringUTFChars(outPath, out_file);
        return JNI_FALSE;
    }

    struct jpeg_decompress_struct* cinfo_d = (struct jpeg_decompress_struct*) malloc(sizeof(struct jpeg_decompress_struct));
    struct my_error_mgr* jerr_d = (struct my_error_mgr*) malloc(sizeof(struct my_error_mgr));
    struct jpeg_compress_struct* cinfo_c = (struct jpeg_compress_struct*) malloc(sizeof(struct jpeg_compress_struct));
    struct my_error_mgr* jerr_c = (struct my_error_mgr*) malloc(sizeof(struct my_error_mgr));
    int* map = (int*) malloc(256 * sizeof(int));

    if (!cinfo_d || !jerr_d || !cinfo_c || !jerr_c || !map) return JNI_FALSE;

    memset(cinfo_d, 0, sizeof(struct jpeg_decompress_struct));
    memset(cinfo_c, 0, sizeof(struct jpeg_compress_struct));

    cinfo_d->err = jpeg_std_error(&jerr_d->pub);
    jerr_d->pub.error_exit = my_error_exit;
    jerr_d->pub.emit_message = my_emit_message; jerr_d->pub.output_message = my_output_message;
    
    if (setjmp(jerr_d->setjmp_buffer)) {
        jpeg_destroy_decompress(cinfo_d); free(cinfo_d); free(jerr_d); free(cinfo_c); free(jerr_c); free(map);
        fclose(infile); fclose(outfile); return JNI_FALSE;
    }
    
    jpeg_create_decompress(cinfo_d);
    jpeg_save_markers(cinfo_d, JPEG_APP0 + 1, 0xFFFF); 
    jpeg_stdio_src(cinfo_d, infile);
    jpeg_read_header(cinfo_d, TRUE);
    cinfo_d->scale_num = 1;
    cinfo_d->scale_denom = scaleDenom;
    cinfo_d->out_color_space = JCS_RGB; 
    jpeg_start_decompress(cinfo_d);

    cinfo_c->err = jpeg_std_error(&jerr_c->pub);
    jerr_c->pub.error_exit = my_error_exit;
    jerr_c->pub.emit_message = my_emit_message; jerr_c->pub.output_message = my_output_message;
    
    if (setjmp(jerr_c->setjmp_buffer)) {
        jpeg_destroy_compress(cinfo_c); jpeg_destroy_decompress(cinfo_d);
        free(cinfo_d); free(jerr_d); free(cinfo_c); free(jerr_c); free(map);
        fclose(infile); fclose(outfile); return JNI_FALSE;
    }
    
    jpeg_create_compress(cinfo_c);
    jpeg_stdio_dest(cinfo_c, outfile);
    cinfo_c->image_width = cinfo_d->output_width;
    cinfo_c->image_height = cinfo_d->output_height;
    cinfo_c->input_components = 3;
    cinfo_c->in_color_space = JCS_RGB;
    jpeg_set_defaults(cinfo_c);
    jpeg_set_quality(cinfo_c, 95, TRUE);

    jpeg_saved_marker_ptr marker = cinfo_d->marker_list;
    while (marker != NULL) {
        jpeg_write_marker(cinfo_c, marker->marker, marker->data, marker->data_length);
        marker = marker->next;
    }
    jpeg_start_compress(cinfo_c, TRUE);

    int lutMax = nativeLutSize - 1;
    int lutSize2 = nativeLutSize * nativeLutSize;
    for (int i = 0; i < 256; i++) { map[i] = (i * lutMax * 128) / 255; }
    
    int row_stride = cinfo_d->output_width * cinfo_d->output_components;
    JSAMPARRAY buffer = (*cinfo_d->mem->alloc_sarray)((j_common_ptr) cinfo_d, JPOOL_IMAGE, row_stride, 1);

    const int* pR = &nativeLutR[0]; const int* pG = &nativeLutG[0]; const int* pB = &nativeLutB[0];

    // VIGNETTE GEOMETRY PRE-CALCULATION
    long cx = cinfo_d->output_width / 2;
    long cy = cinfo_d->output_height / 2;
    long max_dist_sq = cx*cx + cy*cy;
    if (max_dist_sq == 0) max_dist_sq = 1;

    while (cinfo_d->output_scanline < cinfo_d->output_height) {
        long current_y = cinfo_d->output_scanline;
        jpeg_read_scanlines(cinfo_d, buffer, 1);
        unsigned char* row = buffer[0];

        // VIGNETTE ROW CACHE
        long dy = current_y - cy;
        long dy_sq = dy * dy;

        for (int x = 0; x < row_stride; x += 3) {
            int origR = row[x]; int origG = row[x+1]; int origB = row[x+2];

            // 1. VIGNETTE
            if (vignette > 0) {
                long dx = (x / 3) - cx;
                long dist_sq = dx*dx + dy_sq;
                int vig_mult = 256 - ((dist_sq * vignette) / max_dist_sq);
                if (vig_mult < 0) vig_mult = 0;
                origR = (origR * vig_mult) >> 8;
                origG = (origG * vig_mult) >> 8;
                origB = (origB * vig_mult) >> 8;
            }

            // 2. HIGHLIGHT ROLL-OFF
            origR = rollOff(origR);
            origG = rollOff(origG);
            origB = rollOff(origB);

            // 3. LUT MATH (Tetrahedral)
            int fX = map[origR]; int fY = map[origG]; int fZ = map[origB];
            int x0 = fX >> 7; int y0 = fY >> 7; int z0 = fZ >> 7;
            int x1 = x0 + 1; if (x1 > lutMax) x1 = lutMax;
            int y1 = y0 + 1; if (y1 > lutMax) y1 = lutMax;
            int z1 = z0 + 1; if (z1 > lutMax) z1 = lutMax;

            int dx_lut = fX & 0x7F; int dy_lut = fY & 0x7F; int dz_lut = fZ & 0x7F;
            int y0_idx = y0 * nativeLutSize; int y1_idx = y1 * nativeLutSize;
            int z0_idx = z0 * lutSize2;      int z1_idx = z1 * lutSize2;

            int i000 = x0 + y0_idx + z0_idx; int i100 = x1 + y0_idx + z0_idx;
            int i010 = x0 + y1_idx + z0_idx; int i110 = x1 + y1_idx + z0_idx;
            int i001 = x0 + y0_idx + z1_idx; int i101 = x1 + y0_idx + z1_idx;
            int i011 = x0 + y1_idx + z1_idx; int i111 = x1 + y1_idx + z1_idx;

            int v0, v1, v2, v3;
            int w0, w1, w2, w3;

            if (dx_lut >= dy_lut) {
                if (dy_lut >= dz_lut) {
                    v0 = i000; v1 = i100; v2 = i110; v3 = i111;
                    w0 = 128 - dx_lut; w1 = dx_lut - dy_lut; w2 = dy_lut - dz_lut; w3 = dz_lut;
                } else if (dx_lut >= dz_lut) {
                    v0 = i000; v1 = i100; v2 = i101; v3 = i111;
                    w0 = 128 - dx_lut; w1 = dx_lut - dz_lut; w2 = dz_lut - dy_lut; w3 = dy_lut;
                } else {
                    v0 = i000; v1 = i001; v2 = i101; v3 = i111;
                    w0 = 128 - dz_lut; w1 = dz_lut - dx_lut; w2 = dx_lut - dy_lut; w3 = dy_lut;
                }
            } else {
                if (dz_lut >= dy_lut) {
                    v0 = i000; v1 = i001; v2 = i011; v3 = i111;
                    w0 = 128 - dz_lut; w1 = dz_lut - dy_lut; w2 = dy_lut - dx_lut; w3 = dx_lut;
                } else if (dz_lut >= dx_lut) {
                    v0 = i000; v1 = i010; v2 = i011; v3 = i111;
                    w0 = 128 - dy_lut; w1 = dy_lut - dz_lut; w2 = dz_lut - dx_lut; w3 = dx_lut;
                } else {
                    v0 = i000; v1 = i010; v2 = i110; v3 = i111;
                    w0 = 128 - dy_lut; w1 = dy_lut - dx_lut; w2 = dx_lut - dz_lut; w3 = dz_lut;
                }
            }

            int lutR = (pR[v0]*w0 + pR[v1]*w1 + pR[v2]*w2 + pR[v3]*w3) >> 7;
            int lutG = (pG[v0]*w0 + pG[v1]*w1 + pG[v2]*w2 + pG[v3]*w3) >> 7;
            int lutB = (pB[v0]*w0 + pB[v1]*w1 + pB[v2]*w2 + pB[v3]*w3) >> 7;

            // 4. OPACITY BLEND
            int outR = origR + (((lutR - origR) * opacity) >> 8);
            int outG = origG + (((lutG - origG) * opacity) >> 8);
            int outB = origB + (((lutB - origB) * opacity) >> 8);

            // 5. AUTHENTIC FILM GRAIN
            if (grain > 0) {
                // Luminance calculation (0-255)
                int lum = (outR*77 + outG*150 + outB*29) >> 8; 
                // Midtone mask (strongest at 128, zero at 0/255)
                int mask = 128 - abs(lum - 128); 
                // Fast Random Generator
                random_seed = (214013 * random_seed + 2531011);
                int noise = ((random_seed >> 16) & 0xFF) - 128; // -128 to 127
                // Apply masked noise
                int grain_val = (noise * mask * grain) >> 14; 
                outR += grain_val;
                outG += grain_val;
                outB += grain_val;
            }

            // CLAMP AND WRITE
            row[x]   = (unsigned char)(outR < 0 ? 0 : (outR > 255 ? 255 : outR));
            row[x+1] = (unsigned char)(outG < 0 ? 0 : (outG > 255 ? 255 : outG));
            row[x+2] = (unsigned char)(outB < 0 ? 0 : (outB > 255 ? 255 : outB));
        }
        jpeg_write_scanlines(cinfo_c, buffer, 1);
    }

    jpeg_finish_compress(cinfo_c); jpeg_destroy_compress(cinfo_c);
    jpeg_finish_decompress(cinfo_d); jpeg_destroy_decompress(cinfo_d);
    free(cinfo_d); free(jerr_d); free(cinfo_c); free(jerr_c); free(map);
    fclose(infile); fclose(outfile);
    env->ReleaseStringUTFChars(inPath, in_file); env->ReleaseStringUTFChars(outPath, out_file);
    return JNI_TRUE;
}