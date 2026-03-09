#include <jni.h>
#include <vector>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <math.h>
#include <sys/time.h>
#include <arm_neon.h>
#include "jpeglib.h"
#include <android/log.h>

#define LOG_TAG "COOKBOOK_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

std::vector<uint8_t> nativeLut; 
int nativeLutSize = 0;

struct my_error_mgr { struct jpeg_error_mgr pub; jmp_buf setjmp_buffer; };

METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    longjmp(myerr->setjmp_buffer, 1);
}

inline uint32_t fast_rand(uint32_t* state) {
    uint32_t x = *state; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *state = x; return x;
}

long long get_time_ms() {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject obj, jstring path) {
    const char *file_path = env->GetStringUTFChars(path, NULL);
    FILE *file = fopen(file_path, "r");
    if (!file) { env->ReleaseStringUTFChars(path, file_path); return JNI_FALSE; }
    nativeLut.clear(); nativeLutSize = 0;
    char line[256];
    while(fgets(line, sizeof(line), file)) {
        if (strncmp(line, "LUT_3D_SIZE", 11) == 0) {
            sscanf(line, "LUT_3D_SIZE %d", &nativeLutSize);
            nativeLut.reserve(nativeLutSize * nativeLutSize * nativeLutSize * 3);
        }
        float r, g, b;
        if (sscanf(line, "%f %f %f", &r, &g, &b) == 3) {
            nativeLut.push_back((uint8_t)(r * 255.0f)); 
            nativeLut.push_back((uint8_t)(g * 255.0f)); 
            nativeLut.push_back((uint8_t)(b * 255.0f));
        }
    }
    fclose(file); env->ReleaseStringUTFChars(path, file_path);
    return nativeLutSize > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(
    JNIEnv* env, jobject obj, jstring inPath, jstring outPath, 
    jint scaleDenom, jint opacity, jint grain, jint grainSize, 
    jint vignette, jint rollOff) {
    
    long long start_time = get_time_ms();
    
    const char *in_file = env->GetStringUTFChars(inPath, NULL); 
    const char *out_file = env->GetStringUTFChars(outPath, NULL);
    FILE *infile = fopen(in_file, "rb"); 
    FILE *outfile = fopen(out_file, "wb"); 
    
    if (!infile || !outfile) {
        if (infile) fclose(infile); if (outfile) fclose(outfile);
        env->ReleaseStringUTFChars(inPath, in_file); env->ReleaseStringUTFChars(outPath, out_file); 
        return JNI_FALSE;
    }

    std::vector<uint8_t> exifData;
    int targetOffset = 0;
    int finalScale = scaleDenom;

    // --- 1. EXIF & DIMENSION SNIPER ---
    unsigned char* header = (unsigned char*)malloc(1048576); // 1MB scan window
    if (header) {
        int readLen = fread(header, 1, 1048576, infile);
        
        // A. Rip the EXIF safely
        for (int i = 0; i < readLen - 8; i++) {
            if (header[i] == 0xFF && header[i+1] == 0xE1) {
                if (header[i+4] == 'E' && header[i+5] == 'x' && header[i+6] == 'i' && header[i+7] == 'f') {
                    int len = (header[i+2] << 8) | header[i+3];
                    if (i + 2 + len <= readLen) {
                        exifData.assign(header + i + 4, header + i + 2 + len);
                    }
                    break; 
                }
            }
        }

        // B. Dimension Sniper for Proxy Mode
        if (scaleDenom == 4) {
            // Start at 1000 to skip the main 24MP Start-Of-Image
            for (int i = 1000; i < readLen - 10; i++) {
                if (header[i] == 0xFF && header[i+1] == 0xD8) {
                    // Found an embedded SOI. Now scan forward for its Start-Of-Frame (FF C0)
                    for (int j = i + 2; j < i + 65536 && j < readLen - 10; j++) {
                        if (header[j] == 0xFF && header[j+1] == 0xC0) {
                            int height = (header[j+5] << 8) | header[j+6];
                            int width  = (header[j+7] << 8) | header[j+8];
                            
                            // Target lock: Width between 1000 and 2000 pixels (1.5MP to 2MP)
                            if (width >= 1000 && width <= 2000) { 
                                targetOffset = i;
                                finalScale = 1; // It's already the right size
                                LOGD("Sniper locked on proxy at offset %d: %dx%d", i, width, height);
                                break;
                            }
                            break; // Stop looking for SOF for this SOI, move to next
                        }
                    }
                }
                if (finalScale == 1) break; // Break outer loop if found
            }
        }
        free(header);
    }

    struct jpeg_decompress_struct cinfo_d; 
    struct jpeg_compress_struct cinfo_c; 
    struct my_error_mgr jerr_d, jerr_c;
    
    cinfo_d.err = jpeg_std_error(&jerr_d.pub); 
    jerr_d.pub.error_exit = my_error_exit;
    jpeg_create_decompress(&cinfo_d); 

    bool decoded = false;

    // --- 2. THUMBNAIL RIPPING & SAFETY NET ---
    if (scaleDenom == 4 && targetOffset > 0) {
        fseek(infile, targetOffset, SEEK_SET);
        if (setjmp(jerr_d.setjmp_buffer)) {
            jpeg_abort_decompress(&cinfo_d); 
            decoded = false; 
        } else {
            jpeg_stdio_src(&cinfo_d, infile);
            jpeg_read_header(&cinfo_d, TRUE); 
            cinfo_d.scale_num = 1;
            cinfo_d.scale_denom = 1; 
            cinfo_d.out_color_space = JCS_RGB; 
            jpeg_start_decompress(&cinfo_d);
            decoded = true;
        }
    }

    if (!decoded) {
        fseek(infile, 0, SEEK_SET);
        if (setjmp(jerr_d.setjmp_buffer)) { 
            jpeg_destroy_decompress(&cinfo_d); fclose(infile); fclose(outfile); return JNI_FALSE; 
        }
        jpeg_stdio_src(&cinfo_d, infile);
        jpeg_read_header(&cinfo_d, TRUE); 
        cinfo_d.scale_num = 1;
        cinfo_d.scale_denom = (scaleDenom == 4) ? 4 : scaleDenom; 
        cinfo_d.out_color_space = JCS_RGB; 
        jpeg_start_decompress(&cinfo_d);
    }

    LOGD("Decompressor Ready. Target Dimensions: %d x %d", cinfo_d.output_width, cinfo_d.output_height);

    cinfo_c.err = jpeg_std_error(&jerr_c.pub); 
    jerr_c.pub.error_exit = my_error_exit;
    if (setjmp(jerr_c.setjmp_buffer)) { 
        jpeg_destroy_compress(&cinfo_c); jpeg_destroy_decompress(&cinfo_d); 
        fclose(infile); fclose(outfile); return JNI_FALSE; 
    }
    
    jpeg_create_compress(&cinfo_c); 
    jpeg_stdio_dest(&cinfo_c, outfile);
    cinfo_c.image_width = cinfo_d.output_width; 
    cinfo_c.image_height = cinfo_d.output_height; 
    cinfo_c.input_components = 3; 
    cinfo_c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cinfo_c); 
    jpeg_set_quality(&cinfo_c, 95, TRUE); 
    
    jpeg_start_compress(&cinfo_c, TRUE);

    // --- 3. EXIF INJECTION ---
    if (!exifData.empty()) {
        jpeg_write_marker(&cinfo_c, JPEG_APP0 + 1, exifData.data(), exifData.size());
    }

    int map[256]; 
    int lutMax = nativeLutSize > 0 ? nativeLutSize - 1 : 0; 
    int lutSize2 = nativeLutSize * nativeLutSize;
    for (int i = 0; i < 256; i++) { map[i] = lutMax > 0 ? (i * lutMax * 128) / 255 : 0; }
    
    int row_stride = cinfo_d.output_width * 3;
    long long cx = cinfo_d.output_width / 2; 
    long long cy_center = cinfo_d.output_height / 2;
    long long max_dist_sq = cx*cx + cy_center*cy_center;
    long long vig_coef = ((long long)((vignette * 256) / 100) << 24) / (max_dist_sq > 0 ? max_dist_sq : 1); 
    int opac_mapped = (opacity * 256) / 100;
    
    unsigned char* row_buf = (unsigned char*)malloc(row_stride);
    JSAMPROW row_pointer[1];
    uint32_t master_seed = 98765;

    // --- 4. PROCESSING LOOP (SCALAR + NEON) ---
    while (cinfo_d.output_scanline < cinfo_d.output_height) {
        int abs_y = cinfo_d.output_scanline;
        row_pointer[0] = row_buf;
        jpeg_read_scanlines(&cinfo_d, row_pointer, 1);
        uint32_t seed = master_seed + (abs_y * 1337); 
        int prev_noise = 0; 

        int x = 0;

        // NEON FAST PATH: 8 Pixels (24 bytes) at a time for pure LUT processing
        if (nativeLutSize > 0 && grain == 0 && vignette == 0 && rollOff == 0) {
            for (; x <= row_stride - 24; x += 24) {
                uint8x8x3_t v_rgb = vld3_u8(&row_buf[x]); 
                uint8_t r_arr[8], g_arr[8], b_arr[8];
                
                vst1_u8(r_arr, v_rgb.val[0]);
                vst1_u8(g_arr, v_rgb.val[1]);
                vst1_u8(b_arr, v_rgb.val[2]);

                for(int p = 0; p < 8; ++p) {
                    int r = r_arr[p], g = g_arr[p], b = b_arr[p];
                    int fX = map[r], fY = map[g], fZ = map[b];
                    int x0 = fX >> 7, y0 = fY >> 7, z0 = fZ >> 7;
                    int x1 = (x0 < lutMax) ? x0 + 1 : lutMax;
                    int y1 = (y0 < lutMax) ? y0 + 1 : lutMax;
                    int z1 = (z0 < lutMax) ? z0 + 1 : lutMax;
                    int dx = fX & 0x7F, dy_l = fY & 0x7F, dz = fZ & 0x7F;
                    int v1, v2, w0, w1, w2, w3;
                    
                    if (dx >= dy_l) {
                        if (dy_l >= dz) { v1=x1+y0*nativeLutSize+z0*lutSize2; v2=x1+y1*nativeLutSize+z0*lutSize2; w0=128-dx; w1=dx-dy_l; w2=dy_l-dz; w3=dz; } 
                        else if (dx >= dz) { v1=x1+y0*nativeLutSize+z0*lutSize2; v2=x1+y0*nativeLutSize+z1*lutSize2; w0=128-dx; w1=dx-dz; w2=dz-dy_l; w3=dy_l; } 
                        else { v1=x0+y0*nativeLutSize+z1*lutSize2; v2=x1+y0*nativeLutSize+z1*lutSize2; w0=128-dz; w1=dz-dx; w2=dx-dy_l; w3=dy_l; }
                    } else {
                        if (dz >= dy_l) { v1=x0+y0*nativeLutSize+z1*lutSize2; v2=x0+y1*nativeLutSize+z1*lutSize2; w0=128-dz; w1=dz-dy_l; w2=dy_l-dx; w3=dx; } 
                        else if (dz >= dx) { v1=x0+y1*nativeLutSize+z0*lutSize2; v2=x0+y1*nativeLutSize+z1*lutSize2; w0=128-dy_l; w1=dy_l-dz; w2=dz-dx; w3=dx; } 
                        else { v1=x0+y1*nativeLutSize+z0*lutSize2; v2=x1+y1*nativeLutSize+z0*lutSize2; w0=128-dy_l; w1=dy_l-dx; w2=dx-dz; w3=dz; }
                    }
                    
                    const uint8_t* p0 = &nativeLut[(x0 + y0*nativeLutSize + z0*lutSize2)*3];
                    const uint8_t* p1 = &nativeLut[v1*3];
                    const uint8_t* p2 = &nativeLut[v2*3];
                    const uint8_t* p3 = &nativeLut[(x1 + y1*nativeLutSize + z1*lutSize2)*3];
                    
                    int lR = (p0[0]*w0 + p1[0]*w1 + p2[0]*w2 + p3[0]*w3) >> 7;
                    int lG = (p0[1]*w0 + p1[1]*w1 + p2[1]*w2 + p3[1]*w3) >> 7;
                    int lB = (p0[2]*w0 + p1[2]*w1 + p2[2]*w2 + p3[2]*w3) >> 7;

                    int outR = r + (((lR - r) * opac_mapped) >> 8);
                    int outG = g + (((lG - g) * opac_mapped) >> 8);
                    int outB = b + (((lB - b) * opac_mapped) >> 8);

                    r_arr[p] = (uint8_t)(outR<0?0:outR>255?255:outR);
                    g_arr[p] = (uint8_t)(outG<0?0:outG>255?255:outG);
                    b_arr[p] = (uint8_t)(outB<0?0:outB>255?255:outB);
                }

                v_rgb.val[0] = vld1_u8(r_arr);
                v_rgb.val[1] = vld1_u8(g_arr);
                v_rgb.val[2] = vld1_u8(b_arr);
                vst3_u8(&row_buf[x], v_rgb);
            }
        }

        // SCALAR FALLBACK (For edge pixels, or Grain/Vignette/RollOff)
        for (; x < row_stride; x += 3) {
            int r = row_buf[x], g = row_buf[x+1], b = row_buf[x+2];
            int outR = r, outG = g, outB = b;

            if (nativeLutSize > 0) {
                int fX = map[r], fY = map[g], fZ = map[b];
                int x0 = fX >> 7, y0 = fY >> 7, z0 = fZ >> 7;
                int x1 = (x0 < lutMax) ? x0 + 1 : lutMax;
                int y1 = (y0 < lutMax) ? y0 + 1 : lutMax;
                int z1 = (z0 < lutMax) ? z0 + 1 : lutMax;
                int dx = fX & 0x7F, dy_l = fY & 0x7F, dz = fZ & 0x7F;
                int v1, v2, w0, w1, w2, w3;
                if (dx >= dy_l) {
                    if (dy_l >= dz) { v1=x1+y0*nativeLutSize+z0*lutSize2; v2=x1+y1*nativeLutSize+z0*lutSize2; w0=128-dx; w1=dx-dy_l; w2=dy_l-dz; w3=dz; } 
                    else if (dx >= dz) { v1=x1+y0*nativeLutSize+z0*lutSize2; v2=x1+y0*nativeLutSize+z1*lutSize2; w0=128-dx; w1=dx-dz; w2=dz-dy_l; w3=dy_l; } 
                    else { v1=x0+y0*nativeLutSize+z1*lutSize2; v2=x1+y0*nativeLutSize+z1*lutSize2; w0=128-dz; w1=dz-dx; w2=dx-dy_l; w3=dy_l; }
                } else {
                    if (dz >= dy_l) { v1=x0+y0*nativeLutSize+z1*lutSize2; v2=x0+y1*nativeLutSize+z1*lutSize2; w0=128-dz; w1=dz-dy_l; w2=dy_l-dx; w3=dx; } 
                    else if (dz >= dx) { v1=x0+y1*nativeLutSize+z0*lutSize2; v2=x0+y1*nativeLutSize+z1*lutSize2; w0=128-dy_l; w1=dy_l-dz; w2=dz-dx; w3=dx; } 
                    else { v1=x0+y1*nativeLutSize+z0*lutSize2; v2=x1+y1*nativeLutSize+z0*lutSize2; w0=128-dy_l; w1=dy_l-dx; w2=dx-dz; w3=dz; }
                }
                const uint8_t* p0 = &nativeLut[(x0 + y0*nativeLutSize + z0*lutSize2)*3];
                const uint8_t* p1 = &nativeLut[v1*3];
                const uint8_t* p2 = &nativeLut[v2*3];
                const uint8_t* p3 = &nativeLut[(x1 + y1*nativeLutSize + z1*lutSize2)*3];
                int lR = (p0[0]*w0 + p1[0]*w1 + p2[0]*w2 + p3[0]*w3) >> 7;
                int lG = (p0[1]*w0 + p1[1]*w1 + p2[1]*w2 + p3[1]*w3) >> 7;
                int lB = (p0[2]*w0 + p1[2]*w1 + p2[2]*w2 + p3[2]*w3) >> 7;
                outR = r + (((lR - r) * opac_mapped) >> 8);
                outG = g + (((lG - g) * opac_mapped) >> 8);
                outB = b + (((lB - b) * opac_mapped) >> 8);
            }
            if (rollOff > 0) { 
                int r_t = (outR > 200) ? outR - ((outR - 200) * (outR - 200) * rollOff) / 11000 : outR;
                int g_t = (outG > 200) ? outG - ((outG - 200) * (outG - 200) * rollOff) / 11000 : outG;
                int b_t = (outB > 200) ? outB - ((outB - 200) * (outB - 200) * rollOff) / 11000 : outB;
                outR = r_t < 0 ? 0 : r_t; outG = g_t < 0 ? 0 : g_t; outB = b_t < 0 ? 0 : b_t;
            }
            if (vignette > 0) {
                long long d_sq = ((long long)(x/3)-cx)*((long long)(x/3)-cx) + (long long)(abs_y-cy_center)*(abs_y-cy_center);
                int v_m = 256 - (int)((d_sq * vig_coef) >> 24); if (v_m < 0) v_m = 0;
                outR = (outR * v_m) >> 8; outG = (outG * v_m) >> 8; outB = (outB * v_m) >> 8;
            }
            if (grain > 0) {
                int raw_noise = (fast_rand(&seed) & 0xFF) - 128;
                int noise = (grainSize == 0) ? raw_noise : (grainSize == 1) ? (raw_noise + prev_noise) >> 1 : (raw_noise + prev_noise * 2) / 3;
                int lum = (outR*77 + outG*150 + outB*29) >> 8; 
                int mask = (lum < 128) ? lum : 255 - lum; 
                if (lum < 64) mask = (mask * lum) >> 6;
                int gv = (noise * mask * grain) >> 15; 
                outR += gv; outG += gv; outB += gv;
                prev_noise = raw_noise;
            }
            row_buf[x] = (unsigned char)(outR<0?0:outR>255?255:outR);
            row_buf[x+1] = (unsigned char)(outG<0?0:outG>255?255:outG);
            row_buf[x+2] = (unsigned char)(outB<0?0:outB>255?255:outB);
        }
        jpeg_write_scanlines(&cinfo_c, row_pointer, 1);
    }
    
    free(row_buf); jpeg_finish_compress(&cinfo_c); jpeg_destroy_compress(&cinfo_c);
    jpeg_finish_decompress(&cinfo_d); jpeg_destroy_decompress(&cinfo_d);
    fclose(infile); fclose(outfile); env->ReleaseStringUTFChars(inPath, in_file); 
    env->ReleaseStringUTFChars(outPath, out_file); 

    long long end_time = get_time_ms();
    LOGD("Native processing finished. Total time: %lld ms", (end_time - start_time));

    return JNI_TRUE;
}