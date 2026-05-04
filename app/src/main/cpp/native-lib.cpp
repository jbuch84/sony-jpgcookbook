#include <jni.h>
#include <vector>
#include <string>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <math.h>
#include <sys/time.h>
#include <pthread.h>
#include "jpeglib.h"
#include <android/log.h>
#include "process_kernel.h"
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#define LOG_TAG "COOKBOOK_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

std::vector<uint8_t> nativeLut;
int nativeLutSize = 0;
std::vector<uint8_t> nativeGrainTexture;
int nativeLastGrainTransform = -1;

static pthread_mutex_t g_lut_mutex = PTHREAD_MUTEX_INITIALIZER;

struct my_error_mgr { struct jpeg_error_mgr pub; jmp_buf setjmp_buffer; };
METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    longjmp(myerr->setjmp_buffer, 1);
}

struct YuvTextureFastRowsTask {
    unsigned char* base;
    int rowStride;
    int width;
    int startY;
    int rowStart;
    int rowEnd;
    int grain;
    int scaleDenom;
    const YuvTextureFastLut* fastLut;
    const uint8_t* externalTex;
    bool is1024Grain;
    int grainTransform;
};

static void process_yuv_texture_fast_rows(YuvTextureFastRowsTask* task) {
    for (int row = task->rowStart; row < task->rowEnd; row++) {
        process_row_yuv_texture_fast(
            task->base + row * task->rowStride,
            task->width,
            task->startY + row,
            task->grain,
            task->scaleDenom,
            *task->fastLut,
            task->externalTex,
            task->is1024Grain,
            task->grainTransform);
    }
}

static void* yuv_texture_fast_rows_thread(void* data) {
    process_yuv_texture_fast_rows((YuvTextureFastRowsTask*)data);
    return NULL;
}

struct WorkerPool {
    pthread_mutex_t lock;
    pthread_cond_t cond_work;
    pthread_cond_t cond_done;
    YuvTextureFastRowsTask tasks[4];
    bool start_work[4];
    bool terminate;
    int active_workers;
    int completed_workers;
    pthread_t threads[4];
    bool initialized;
};

static WorkerPool g_pool;

static void* persistent_worker_thread(void* arg) {
    int id = (int)(intptr_t)arg;
    
    pthread_mutex_lock(&g_pool.lock);
    while (true) {
        while (!g_pool.start_work[id] && !g_pool.terminate) {
            pthread_cond_wait(&g_pool.cond_work, &g_pool.lock);
        }
        if (g_pool.terminate) break;
        
        g_pool.start_work[id] = false;
        pthread_mutex_unlock(&g_pool.lock);
        
        process_yuv_texture_fast_rows(&g_pool.tasks[id]);
        
        pthread_mutex_lock(&g_pool.lock);
        g_pool.completed_workers++;
        if (g_pool.completed_workers == g_pool.active_workers) {
            pthread_cond_signal(&g_pool.cond_done);
        }
    }
    pthread_mutex_unlock(&g_pool.lock);
    return NULL;
}

static void init_worker_pool() {
    if (g_pool.initialized) return;
    pthread_mutex_init(&g_pool.lock, NULL);
    pthread_cond_init(&g_pool.cond_work, NULL);
    pthread_cond_init(&g_pool.cond_done, NULL);
    g_pool.terminate = false;
    for (int i = 1; i < 4; i++) {
        g_pool.start_work[i] = false;
        pthread_create(&g_pool.threads[i], NULL, persistent_worker_thread, (void*)(intptr_t)i);
    }
    g_pool.initialized = true;
}

long long get_time_ms() { struct timeval tv; gettimeofday(&tv, NULL); return (long long)tv.tv_sec*1000 + tv.tv_usec/1000; }

static int choose_grain_transform(uint32_t seed, bool enabled) {
    if (!enabled) return 0;
    uint32_t h = seed ^ (seed >> 16) ^ 0x9E3779B9u;
    h ^= h >> 13;
    h *= 0x85EBCA6Bu;
    h ^= h >> 16;
    int transform = (int)(h & 3u);
    if (transform == nativeLastGrainTransform) transform = (transform + 1) & 3;
    nativeLastGrainTransform = transform;
    return transform;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject obj, jstring path) {
    pthread_mutex_lock(&g_lut_mutex);
    nativeLut.clear(); nativeLutSize = 0; const char *fp = env->GetStringUTFChars(path, NULL); std::string ps(fp); std::string ex = ""; size_t dp = ps.find_last_of('.');
    if (dp != std::string::npos) { ex = ps.substr(dp); for(size_t i=0; i<ex.length(); i++) ex[i]=tolower(ex[i]); }
    if (ex == ".png") {
        int w, h, c; unsigned char *id = stbi_load(fp, &w, &h, &c, 3);
        if (id) {
            if (w*h <= 4000000) {
                int bl=1, md=w*h; for(int l=1; l<=150; l++){ int diff = abs((l*l*l)-(w*h)); if (diff < md) { md = diff; bl = l; } }
                nativeLutSize=bl; nativeLut.resize(bl*bl*bl*3); int tr = w / bl; if (tr == 0) tr = 1;
                for(int b=0; b<bl; b++){ int cx=b%tr, cy=b/tr; for(int g=0; g<bl; g++){ int iy=cy*bl+g; for(int r=0; r<bl; r++){ int ix=cx*bl+r; if(ix>=w)ix=w-1; if(iy>=h)iy=h-1; int s=(iy*w+ix)*3, d=(r+g*bl+b*bl*bl)*3; nativeLut[d]=id[s]; nativeLut[d+1]=id[s+1]; nativeLut[d+2]=id[s+2]; } } }
            }
            stbi_image_free(id);
        }
    } else if (ex==".cube"||ex==".cub") {
        FILE *f = fopen(fp, "r"); if(f){ char l[256]; size_t c=0; while(fgets(l, 256, f)){ if(strncmp(l,"LUT_3D_SIZE",11)==0){ sscanf(l,"LUT_3D_SIZE %d",&nativeLutSize); nativeLut.resize(nativeLutSize*nativeLutSize*nativeLutSize*3); c=0; continue; } float r,g,b; if(nativeLutSize>0 && sscanf(l,"%f %f %f",&r,&g,&b)==3){ if(c+2<nativeLut.size()){ nativeLut[c++]=(uint8_t)(r*255); nativeLut[c++]=(uint8_t)(g*255); nativeLut[c++]=(uint8_t)(b*255); } } } fclose(f); }
    }
    env->ReleaseStringUTFChars(path, fp); 
    jboolean result = nativeLutSize>0 ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&g_lut_mutex);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_loadGrainTextureNative(JNIEnv* env, jobject obj, jstring path) {
    pthread_mutex_lock(&g_lut_mutex);
    nativeGrainTexture.clear(); if(!path) { pthread_mutex_unlock(&g_lut_mutex); return JNI_FALSE; } const char *fp=env->GetStringUTFChars(path, NULL); int w,h,c; unsigned char *id=stbi_load(fp,&w,&h,&c,3); env->ReleaseStringUTFChars(path,fp);
    if(id){ if((w==512||w==1024)&&w==h){ nativeGrainTexture.assign(id, id+(w*h*3)); stbi_image_free(id); pthread_mutex_unlock(&g_lut_mutex); return JNI_TRUE; } stbi_image_free(id); } pthread_mutex_unlock(&g_lut_mutex); return JNI_FALSE;
}

// Patches the EXIF APP1 marker height fields to match the actual cropped output.
// Handles both little-endian (Intel) and big-endian (Motorola) TIFF byte orders.
// Patches IFD0 ImageLength (0x0101) and ExifSubIFD PixelYDimension (0xA003).
static void patch_exif_height(uint8_t* data, int dataLen, int newHeight) {
    if (dataLen < 14 || memcmp(data, "Exif\0\0", 6) != 0) return;
    uint8_t* t = data + 6;
    int tLen = dataLen - 6;
    if (tLen < 8) return;
    bool le = (t[0] == 'I');

    #define EX_R16(o) (le ? ((uint16_t)t[o]|((uint16_t)t[(o)+1]<<8)) : ((uint16_t)t[o]<<8|(uint16_t)t[(o)+1]))
    #define EX_R32(o) (le ? ((uint32_t)t[o]|(uint32_t)t[(o)+1]<<8|(uint32_t)t[(o)+2]<<16|(uint32_t)t[(o)+3]<<24) : ((uint32_t)t[o]<<24|(uint32_t)t[(o)+1]<<16|(uint32_t)t[(o)+2]<<8|(uint32_t)t[(o)+3]))
    #define EX_W16(o,v) do{uint16_t _v=(uint16_t)(v);if(le){t[o]=_v;t[(o)+1]=_v>>8;}else{t[o]=_v>>8;t[(o)+1]=_v;}}while(0)
    #define EX_W32(o,v) do{uint32_t _v=(uint32_t)(v);if(le){t[o]=_v;t[(o)+1]=_v>>8;t[(o)+2]=_v>>16;t[(o)+3]=_v>>24;}else{t[o]=_v>>24;t[(o)+1]=_v>>16;t[(o)+2]=_v>>8;t[(o)+3]=_v;}}while(0)

    uint32_t ifd0 = EX_R32(4);
    if ((int)(ifd0 + 2) <= tLen) {
        uint16_t cnt = EX_R16(ifd0);
        uint32_t exifSub = 0;
        for (int i = 0; i < (int)cnt; i++) {
            int e = (int)ifd0 + 2 + i * 12;
            if (e + 12 > tLen) break;
            uint16_t tag = EX_R16(e);
            uint16_t type = EX_R16(e + 2);
            if (tag == 0x0101) { // ImageLength
                if (type == 3) EX_W16(e + 8, newHeight);
                else if (type == 4) EX_W32(e + 8, newHeight);
            } else if (tag == 0x8769) { // ExifIFD pointer
                exifSub = EX_R32(e + 8);
            }
        }
        if (exifSub > 0 && (int)(exifSub + 2) <= tLen) {
            uint16_t scnt = EX_R16(exifSub);
            for (int i = 0; i < (int)scnt; i++) {
                int e = (int)exifSub + 2 + i * 12;
                if (e + 12 > tLen) break;
                uint16_t tag = EX_R16(e);
                uint16_t type = EX_R16(e + 2);
                if (tag == 0xA003) { // PixelYDimension
                    if (type == 3) EX_W16(e + 8, newHeight);
                    else if (type == 4) EX_W32(e + 8, newHeight);
                    break;
                }
            }
        }
    }

    #undef EX_R16
    #undef EX_R32
    #undef EX_W16
    #undef EX_W32
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(
    JNIEnv* env, jobject obj, jstring inPath, jstring outPath,
    jint scaleDenom, jint opacity, jint grain, jint grainSize,
    jint vignette, jint rollOff, jint colorChrome, jint chromeBlue,
    jint shadowToe, jint subtractiveSat, jint halation,
    jint bloom, jint advancedGrainExperimental, jint jpegQuality,
    jboolean applyCrop, jint numCores) {

    long long st = get_time_ms(); const char *ifn = env->GetStringUTFChars(inPath, NULL); const char *ofn = env->GetStringUTFChars(outPath, NULL);
    FILE *inf = fopen(ifn, "rb"), *ouf = fopen(ofn, "wb");
    if(!inf||!ouf){ if(inf)fclose(inf); if(ouf)fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn); return JNI_FALSE; }

    struct jpeg_decompress_struct cd; struct my_error_mgr jd; cd.err = jpeg_std_error(&jd.pub); jd.pub.error_exit = my_error_exit;
    if(setjmp(jd.setjmp_buffer)){ jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn); return JNI_FALSE; }
    jpeg_create_decompress(&cd); jpeg_stdio_src(&cd, inf); 
    
    // --- PRESERVE ALL SONY MARKERS ---
    for (int i = 0; i < 16; i++) jpeg_save_markers(&cd, JPEG_APP0 + i, 0xFFFF);
    jpeg_save_markers(&cd, JPEG_COM, 0xFFFF);

    // --- THREAD SAFETY COPY ---
    pthread_mutex_lock(&g_lut_mutex);
    std::vector<uint8_t> localLut = nativeLut;
    int localLutSize = nativeLutSize;
    std::vector<uint8_t> localGrainTexture = nativeGrainTexture;
    pthread_mutex_unlock(&g_lut_mutex);

    bool use_rgb = (localLutSize > 0 && opacity > 0);
    jpeg_read_header(&cd, TRUE);
    cd.scale_num = 1;
    cd.scale_denom = scaleDenom;
    cd.out_color_space = use_rgb ? JCS_RGB : JCS_YCbCr;
    cd.dct_method = JDCT_IFAST;
    jpeg_start_decompress(&cd);

    struct jpeg_compress_struct cc; struct my_error_mgr jc; cc.err = jpeg_std_error(&jc.pub); jc.pub.error_exit = my_error_exit;
    if(setjmp(jc.setjmp_buffer)){ jpeg_destroy_compress(&cc); jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn); return JNI_FALSE; }
    jpeg_create_compress(&cc); jpeg_stdio_dest(&cc, ouf);
    
    int fh = cd.output_height, sk = 0; if(applyCrop){ fh=(int)(cd.output_width/2.71f); sk=(cd.output_height-fh)/2; }
    cc.image_width = cd.output_width; cc.image_height = fh; cc.input_components = 3; cc.in_color_space = use_rgb ? JCS_RGB : JCS_YCbCr;
    jpeg_set_defaults(&cc); 
    cc.dct_method = JDCT_IFAST;
    jpeg_set_quality(&cc, jpegQuality, TRUE); 
    
    // --- COPY ALL MARKERS BACK (Fixes Review Error) ---
    jpeg_start_compress(&cc, TRUE);
    jpeg_saved_marker_ptr mark = cd.marker_list;
    while (mark) {
        // When cropping, patch the EXIF APP1 height so viewers see correct dimensions.
        if (applyCrop && mark->marker == JPEG_APP0 + 1 && mark->data_length > 6) {
            std::vector<uint8_t> patched(mark->data, mark->data + mark->data_length);
            patch_exif_height(patched.data(), (int)patched.size(), fh);
            jpeg_write_marker(&cc, mark->marker, patched.data(), mark->data_length);
        } else {
            jpeg_write_marker(&cc, mark->marker, mark->data, mark->data_length);
        }
        mark = mark->next;
    }

    int rs = cd.output_width*3;
    const uint8_t* externalTex = localGrainTexture.empty() ? NULL : localGrainTexture.data();
    bool is_1024_grain = localGrainTexture.size() > 1000000;
    bool use_fast_yuv_texture_candidate = (!use_rgb && advancedGrainExperimental == 2 && externalTex != NULL
        && grain > 0 && colorChrome == 0 && chromeBlue == 0 && subtractiveSat == 0
        && bloom <= 0 && halation == 0 && vignette == 0);

    int CHK = (use_fast_yuv_texture_candidate && !applyCrop) ? 128 : 64;
    int BUF = CHK + 20;

    unsigned char* rb = (unsigned char*)malloc(BUF*rs);
    unsigned char* ob = (unsigned char*)malloc(CHK*rs);
    if (!rb || !ob) {
        if (rb) free(rb);
        if (ob) free(ob);
        jpeg_finish_compress(&cc); jpeg_destroy_compress(&cc);
        jpeg_finish_decompress(&cd); jpeg_destroy_decompress(&cd);
        fclose(inf); fclose(ouf);
        env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn);
        return JNI_FALSE;
    }
    unsigned char* r[256];
    unsigned char* orw[256];
    for(int i=0; i<BUF; i++) r[i]=rb+(i*rs);
    for(int i=0; i<CHK; i++) orw[i]=ob+(i*rs);

    int map[256]; for(int i=0; i<256; i++) map[i]=(i*(localLutSize-1)*128)/255;
    uint8_t roll[256]; generate_rolloff_lut(roll, rollOff);
    if (advancedGrainExperimental == 2 && externalTex != NULL && grain > 0) {
        ensure_overlay_blend_lut();
    }
    int ws_s = cd.output_width * sizeof(int);
    int* work_0 = NULL; int* work_1 = NULL; int* work_2 = NULL; int* work_h = NULL; int* h_line = NULL;
    if (bloom > 0 || halation > 0) {
        work_0 = (int*)malloc(ws_s); work_1 = (int*)malloc(ws_s); work_2 = (int*)malloc(ws_s);
        work_h = (int*)malloc(ws_s); h_line = (int*)malloc(ws_s);
        if (!work_0 || !work_1 || !work_2 || !work_h || !h_line) {
            free(rb); free(ob);
            if(work_0) free(work_0); if(work_1) free(work_1); if(work_2) free(work_2);
            if(work_h) free(work_h); if(h_line) free(h_line);
            jpeg_finish_compress(&cc); jpeg_destroy_compress(&cc);
            jpeg_finish_decompress(&cd); jpeg_destroy_decompress(&cd);
            fclose(inf); fclose(ouf);
            env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn);
            return JNI_FALSE;
        }
    }

    int opac_m = (opacity * 256) / 100;
    long long cx = cd.output_width / 2;
    long long cy_center = cd.output_height / 2;
    long long vig_coef = get_vig_coef(vignette, cx * cx + cy_center * cy_center);
    uint32_t grain_seed = (uint32_t)(st & 0xFFFFFFFF);
    if (grain_seed == 0) grain_seed = 98765;
    int grainTransform = choose_grain_transform(grain_seed, advancedGrainExperimental == 2 && externalTex != NULL && grain > 0);
    bool use_fast_yuv_texture = use_fast_yuv_texture_candidate;
    YuvTextureFastLut fast_yuv_texture_lut;
    if (use_fast_yuv_texture) {
        build_yuv_texture_fast_lut(fast_yuv_texture_lut, shadowToe, rollOff, roll, grain);
    }

    JSAMPROW rpx[1];

    bool row_stream_mode = (bloom <= 0 && halation <= 0 && advancedGrainExperimental != 1);
    bool use_fast_yuv_texture_chunked = use_fast_yuv_texture && !applyCrop;
    if (row_stream_mode) {
        if (use_fast_yuv_texture_chunked) {
            int worker_count = numCores;
            if (worker_count < 1) worker_count = 1;
            if (worker_count > 4) worker_count = 4;

            while (cd.output_scanline < cd.output_height) {
                int ay = cd.output_scanline;
                int rows_read = 0;
                while (rows_read < CHK && cd.output_scanline < cd.output_height) {
                    JDIMENSION got = jpeg_read_scanlines(&cd, &r[rows_read], CHK - rows_read);
                    if (got == 0) break;
                    rows_read += (int)got;
                }
                if (rows_read <= 0) break;

                int active_workers = worker_count;
                if (active_workers > rows_read) active_workers = rows_read;
                if (rows_read < worker_count * 16) active_workers = 1;

                if (!g_pool.initialized && active_workers > 1) {
                    init_worker_pool();
                }

                YuvTextureFastRowsTask tasks[4];
                for (int t = 0; t < active_workers; t++) {
                    int start = (rows_read * t) / active_workers;
                    int end = (rows_read * (t + 1)) / active_workers;
                    tasks[t].base = rb;
                    tasks[t].rowStride = rs;
                    tasks[t].width = cd.output_width;
                    tasks[t].startY = ay;
                    tasks[t].rowStart = start;
                    tasks[t].rowEnd = end;
                    tasks[t].grain = grain;
                    tasks[t].scaleDenom = scaleDenom;
                    tasks[t].fastLut = &fast_yuv_texture_lut;
                    tasks[t].externalTex = externalTex;
                    tasks[t].is1024Grain = is_1024_grain;
                    tasks[t].grainTransform = grainTransform;
                }

                if (active_workers > 1) {
                    pthread_mutex_lock(&g_pool.lock);
                    g_pool.active_workers = active_workers - 1; // Thread 0 is main thread
                    g_pool.completed_workers = 0;
                    for (int t = 1; t < active_workers; t++) {
                        g_pool.tasks[t] = tasks[t];
                        g_pool.start_work[t] = true;
                    }
                    pthread_cond_broadcast(&g_pool.cond_work);
                    pthread_mutex_unlock(&g_pool.lock);
                }

                // Process first chunk on the main thread
                process_yuv_texture_fast_rows(&tasks[0]);

                if (active_workers > 1) {
                    pthread_mutex_lock(&g_pool.lock);
                    while (g_pool.completed_workers < g_pool.active_workers) {
                        pthread_cond_wait(&g_pool.cond_done, &g_pool.lock);
                    }
                    pthread_mutex_unlock(&g_pool.lock);
                }

                int rows_written = 0;
                while (rows_written < rows_read) {
                    JDIMENSION wrote = jpeg_write_scanlines(&cc, &r[rows_written], rows_read - rows_written);
                    if (wrote == 0) break;
                    rows_written += (int)wrote;
                }
            }
        } else {
            while (cd.output_scanline < cd.output_height) {
                int ay = cd.output_scanline;
                rpx[0] = r[0];
                jpeg_read_scanlines(&cd, rpx, 1);

                if (!applyCrop || (ay >= sk && ay < sk + fh)) {
                    if (use_rgb) {
                        process_row_rgb(r[0], cd.output_width, ay, cx, cy_center, vig_coef,
                            shadowToe, rollOff, colorChrome, chromeBlue, subtractiveSat, halation, vignette,
                            grain, grainSize, scaleDenom, advancedGrainExperimental, grain_seed,
                            opac_m, map, localLut.data(),
                            localLutSize, localLutSize - 1, localLutSize * localLutSize,
                            externalTex, is_1024_grain, grainTransform);
                    } else if (use_fast_yuv_texture) {
                        process_row_yuv_texture_fast(r[0], cd.output_width, ay,
                            grain, scaleDenom, fast_yuv_texture_lut,
                            externalTex, is_1024_grain, grainTransform);
                    } else {
                        process_row_yuv(r[0], cd.output_width, ay, cx, cy_center, vig_coef,
                            shadowToe, rollOff, colorChrome, chromeBlue, subtractiveSat, halation, vignette,
                            grain, grainSize, scaleDenom, advancedGrainExperimental, grain_seed,
                            roll, externalTex, is_1024_grain, grainTransform);
                    }
                    jpeg_write_scanlines(&cc, rpx, 1);
                }
            }
        }
    } else {
        if(cd.output_height>0){ rpx[0]=r[10]; jpeg_read_scanlines(&cd,rpx,1); for(int i=0; i<10; i++) memcpy(r[i],r[10],rs); }
        for(int i=11; i<BUF; i++){ if(cd.output_scanline < cd.output_height){ rpx[0]=r[i]; jpeg_read_scanlines(&cd,rpx,1); } else memcpy(r[i],r[i-1],rs); }

        int pr = 0; while(pr < (int)cd.output_height){
            int rtp = std::min(CHK, (int)cd.output_height-pr);
            for (int i = 0; i < rtp; i++) {
                int ay = pr + i;
                if (!applyCrop || (ay >= sk && ay < sk + fh)) {
                    unsigned char* win[21];
                    for (int w = 0; w < 21; w++) win[w] = r[i + w];
                    memcpy(orw[i], win[10], cd.output_width * 3);

                    apply_bloom_halation(win, orw[i], cd.output_width, ay, !use_rgb, bloom, halation, grain_seed,
                        work_0, work_1, work_2, work_h, h_line, scaleDenom);

                    if (use_rgb) {
                        process_row_rgb(orw[i], cd.output_width, ay, cx, cy_center, vig_coef,
                            shadowToe, rollOff, colorChrome, chromeBlue, subtractiveSat, 0, vignette,
                            grain, grainSize, scaleDenom, advancedGrainExperimental, grain_seed,
                            opac_m, map, localLut.data(),
                            localLutSize, localLutSize - 1, localLutSize * localLutSize,
                            externalTex, is_1024_grain, grainTransform);
                    } else {
                        process_row_yuv(orw[i], cd.output_width, ay, cx, cy_center, vig_coef,
                            shadowToe, rollOff, colorChrome, chromeBlue, subtractiveSat, 0, vignette,
                            grain, grainSize, scaleDenom, advancedGrainExperimental, grain_seed,
                            roll, externalTex, is_1024_grain, grainTransform);
                    }
                }
            }
            for(int i=0; i<rtp; i++){ int ay=pr+i; if(!applyCrop||(ay>=sk && ay<sk+fh)){ rpx[0]=orw[i]; jpeg_write_scanlines(&cc,rpx,1); } }
            unsigned char* tmpx[256]; for(int i=0; i<rtp; i++) tmpx[i]=r[i]; for(int i=0; i<BUF-rtp; i++) r[i]=r[i+rtp];
            for(int i=0; i<rtp; i++){ int di=BUF-rtp+i; r[di]=tmpx[i]; if(cd.output_scanline<cd.output_height){ rpx[0]=r[di]; jpeg_read_scanlines(&cd,rpx,1); } else memcpy(r[di],r[di-1],rs); }
            pr += rtp;
        }
    }

    if (work_0) { free(work_0); free(work_1); free(work_2); free(work_h); free(h_line); }
    free(rb); free(ob); jpeg_finish_compress(&cc); jpeg_destroy_compress(&cc); jpeg_finish_decompress(&cd); jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn);
    return JNI_TRUE;
}

#include <errno.h>
#include <unistd.h>

// Helper to open files with retries for OS file-system locks (e.g. Sony media scanner).
FILE* fopen_retry(const char* path, const char* mode) {
    for (int i = 0; i < 5; i++) {
        FILE* f = fopen(path, mode);
        if (f) return f;
        if (errno != EBUSY && errno != EAGAIN && errno != EINTR) break;
        LOGD("File %s busy, retrying... (%d)", path, i + 1);
        usleep(200000); // 200ms
    }
    return fopen(path, mode);
}

// --- FULL RESOLUTION DIPTYCH STITCH ENGINE (FULL STABILITY) ---
extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_DiptychManager_stitchDiptychNative(
    JNIEnv* env, jobject obj, jstring path1, jstring path2, jstring outPath, jboolean shot1PlacedLeft, jboolean shot1WasLeft, jint quality) {
    
    const char *p1 = env->GetStringUTFChars(path1, NULL);
    const char *p2 = env->GetStringUTFChars(path2, NULL);
    const char *po = env->GetStringUTFChars(outPath, NULL);
    FILE *f1 = fopen_retry(p1, "rb");
    if (!f1) {
        LOGD("Diptych open failed (p1): %s, error: %s", p1, strerror(errno));
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }

    FILE *f2 = fopen_retry(p2, "rb");
    if (!f2) {
        LOGD("Diptych open failed (p2): %s, error: %s", p2, strerror(errno));
        fclose(f1);
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }

    FILE *fo = fopen_retry(po, "wb");
    if (!fo) {
        LOGD("Diptych open failed (out): %s, error: %s", po, strerror(errno));
        fclose(f1); fclose(f2);
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }

    struct jpeg_decompress_struct c1, c2; 
    struct my_error_mgr j1, j2;
    
    // Bullet-proof initialization so error handlers don't crash on NULL pointers
    memset(&c1, 0, sizeof(c1));
    memset(&c2, 0, sizeof(c2));
    
    c1.err = jpeg_std_error(&j1.pub); j1.pub.error_exit = my_error_exit;
    c2.err = jpeg_std_error(&j2.pub); j2.pub.error_exit = my_error_exit;
    if(setjmp(j1.setjmp_buffer) || setjmp(j2.setjmp_buffer)) {
        LOGD("Diptych jpeg decode setup failed");
        if (c1.mem) jpeg_destroy_decompress(&c1);
        if (c2.mem) jpeg_destroy_decompress(&c2);
        fclose(f1); fclose(f2); fclose(fo);
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }
    
    jpeg_create_decompress(&c1); jpeg_stdio_src(&c1, f1); jpeg_read_header(&c1, TRUE);
    jpeg_create_decompress(&c2); jpeg_stdio_src(&c2, f2); jpeg_read_header(&c2, TRUE);
    
    c1.scale_denom = (c1.image_width > 3000) ? 2 : 1;
    c2.scale_denom = (c2.image_width > 3000) ? 2 : 1;
    
    c1.dct_method = JDCT_IFAST; c1.do_fancy_upsampling = FALSE;
    c2.dct_method = JDCT_IFAST; c2.do_fancy_upsampling = FALSE;
    
    c1.out_color_space = JCS_RGB; jpeg_start_decompress(&c1);
    c2.out_color_space = JCS_RGB; jpeg_start_decompress(&c2);
    
    struct jpeg_compress_struct co; 
    struct my_error_mgr jo; 
    memset(&co, 0, sizeof(co));
    
    co.err = jpeg_std_error(&jo.pub); jo.pub.error_exit = my_error_exit;
    if(setjmp(jo.setjmp_buffer)) {
        LOGD("Diptych jpeg encode setup failed");
        if (co.mem) jpeg_destroy_compress(&co);
        if (c1.mem) jpeg_destroy_decompress(&c1);
        if (c2.mem) jpeg_destroy_decompress(&c2);
        fclose(f1); fclose(f2); fclose(fo);
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }
    jpeg_create_compress(&co); jpeg_stdio_dest(&co, fo);
    
    int w1 = c1.output_width, h1 = c1.output_height;
    int w2 = c2.output_width, h2 = c2.output_height;
    int half1 = w1 / 2, half2 = w2 / 2;
    int q1 = w1 / 4, q2 = w2 / 4;
    int finalW = half1 + half2, finalH = std::min(h1, h2);
    
    co.image_width = finalW; co.image_height = finalH; co.input_components = 3; co.in_color_space = JCS_RGB;
    jpeg_set_defaults(&co); jpeg_set_quality(&co, quality, TRUE); jpeg_start_compress(&co, TRUE);
    
    unsigned char *row1 = (unsigned char*)malloc(w1 * 3);
    unsigned char *row2 = (unsigned char*)malloc(w2 * 3);
    unsigned char *combined = (unsigned char*)malloc(finalW * 3);
    if (!row1 || !row2 || !combined) {
        LOGD("Diptych malloc failed");
        if (row1) free(row1);
        if (row2) free(row2);
        if (combined) free(combined);
        if (co.mem) jpeg_destroy_compress(&co);
        if (c1.mem) jpeg_destroy_decompress(&c1);
        if (c2.mem) jpeg_destroy_decompress(&c2);
        fclose(f1); fclose(f2); fclose(fo);
        env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
        return JNI_FALSE;
    }
    JSAMPROW rp1[1], rp2[1], rpo[1]; rp1[0] = row1; rp2[0] = row2; rpo[0] = combined;
    
    // --- CENTER CROP LOGIC ---
    // Shot 1: Always take center half (q1 to q1+half1).
    // Shot 2: Take the half matching its placement (Left side? Take left half. Right side? Take right half).
    for (int y = 0; y < finalH; y++) {
        jpeg_read_scanlines(&c1, rp1, 1); jpeg_read_scanlines(&c2, rp2, 1);
        
        // Fill Left Half
        if (shot1PlacedLeft) {
            // Shot 1 is on Left. Use Shot 1's CENTER crop.
            memcpy(combined, row1 + q1 * 3, half1 * 3);
        } else {
            // Shot 2 is on Left. Use Shot 2's LEFT half (exactly what was framed).
            memcpy(combined, row2 + 0, half2 * 3);
        }

        // Fill Right Half
        if (shot1PlacedLeft) {
            // Shot 2 is on Right. Use Shot 2's RIGHT half (exactly what was framed).
            memcpy(combined + half1 * 3, row2 + half2 * 3, half2 * 3);
        } else {
            // Shot 1 is on Right. Use Shot 1's CENTER crop.
            memcpy(combined + half2 * 3, row1 + q1 * 3, half1 * 3);
        }

        // Draw Divider
        int dividerX = half1;
        for(int d=-1; d<=1; d++) {
            int dx = dividerX + d;
            if (dx >= 0 && dx < finalW) {
                int di = dx * 3;
                combined[di]=combined[di+1]=combined[di+2]=0;
            }
        }
        jpeg_write_scanlines(&co, rpo, 1);
    }
    
    free(row1); free(row2); free(combined);
    jpeg_finish_compress(&co); jpeg_destroy_compress(&co);
    
    // Abort decompression immediately without finishing to avoid unread scanline errors
    jpeg_destroy_decompress(&c1);
    jpeg_destroy_decompress(&c2);
    
    LOGD("Diptych saved: %s", po);
    fclose(f1); fclose(f2); fclose(fo);
    env->ReleaseStringUTFChars(path1, p1); env->ReleaseStringUTFChars(path2, p2); env->ReleaseStringUTFChars(outPath, po);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_MultiExposeManager_blendJpegsNative(
    JNIEnv* env, jobject obj, jobjectArray inputPaths, jstring outputPath, jint blendMode) {
    
    int count = env->GetArrayLength(inputPaths);
    if (count < 2 || count > 9) return JNI_FALSE;
    
    struct jpeg_decompress_struct cds[9];
    struct my_error_mgr jerrs[9];
    FILE* infs[9];
    
    for (int i = 0; i < count; i++) {
        memset(&cds[i], 0, sizeof(cds[i]));
        jstring pathObj = (jstring)env->GetObjectArrayElement(inputPaths, i);
        const char* pathStr = env->GetStringUTFChars(pathObj, NULL);
        infs[i] = fopen(pathStr, "rb");
        env->ReleaseStringUTFChars(pathObj, pathStr);
        if (!infs[i]) {
            for (int j = 0; j < i; j++) {
                jpeg_destroy_decompress(&cds[j]);
                fclose(infs[j]);
            }
            return JNI_FALSE;
        }
        cds[i].err = jpeg_std_error(&jerrs[i].pub);
        jerrs[i].pub.error_exit = my_error_exit;
        if(setjmp(jerrs[i].setjmp_buffer)) {
            for (int j = 0; j <= i; j++) {
                jpeg_destroy_decompress(&cds[j]);
                fclose(infs[j]);
            }
            return JNI_FALSE;
        }
        
        jpeg_create_decompress(&cds[i]);
        jpeg_stdio_src(&cds[i], infs[i]);
        jpeg_read_header(&cds[i], TRUE);
        
        cds[i].scale_denom = (cds[i].image_width > 3000) ? 2 : 1;
        cds[i].dct_method = JDCT_IFAST;
        cds[i].do_fancy_upsampling = FALSE;
        
        cds[i].out_color_space = JCS_RGB;
        jpeg_start_decompress(&cds[i]);
    }
    
    int w = cds[0].output_width;
    int h = cds[0].output_height;
    int rs = w * 3;
    
    const char* outf_str = env->GetStringUTFChars(outputPath, NULL);
    FILE* outf = fopen(outf_str, "wb");
    env->ReleaseStringUTFChars(outputPath, outf_str);
    if (!outf) {
        for (int j = 0; j < count; j++) {
            jpeg_destroy_decompress(&cds[j]);
            fclose(infs[j]);
        }
        return JNI_FALSE;
    }
    
    struct jpeg_compress_struct cc;
    struct my_error_mgr jcerr;
    memset(&cc, 0, sizeof(cc));
    cc.err = jpeg_std_error(&jcerr.pub);
    jcerr.pub.error_exit = my_error_exit;
    if(setjmp(jcerr.setjmp_buffer)) {
        jpeg_destroy_compress(&cc);
        for (int j = 0; j < count; j++) {
            jpeg_destroy_decompress(&cds[j]);
            fclose(infs[j]);
        }
        fclose(outf);
        return JNI_FALSE;
    }
    
    jpeg_create_compress(&cc);
    jpeg_stdio_dest(&cc, outf);
    
    cc.image_width = w;
    cc.image_height = h;
    cc.input_components = 3;
    cc.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cc);
    cc.dct_method = JDCT_IFAST;
    jpeg_set_quality(&cc, 95, TRUE);
    jpeg_start_compress(&cc, TRUE);
    
    int CHUNK = 32;
    unsigned char* row_bufs[9];
    for (int i = 0; i < count; i++) row_bufs[i] = (unsigned char*)malloc(rs * CHUNK);
    unsigned char* out_buf = (unsigned char*)malloc(rs * CHUNK);
    
    JSAMPROW row_ptrs[9][32];
    JSAMPROW out_ptrs[32];
    for(int i=0; i<count; i++) {
        for(int c=0; c<CHUNK; c++) {
            row_ptrs[i][c] = row_bufs[i] + (c * rs);
        }
    }
    for(int c=0; c<CHUNK; c++) {
        out_ptrs[c] = out_buf + (c * rs);
    }
    
    while (cc.next_scanline < cc.image_height) {
        int rows_to_read = CHUNK;
        if (cc.image_height - cc.next_scanline < CHUNK) {
            rows_to_read = cc.image_height - cc.next_scanline;
        }
        
        for (int i = 0; i < count; i++) {
            int read_so_far = 0;
            while(read_so_far < rows_to_read) {
                int got = jpeg_read_scanlines(&cds[i], &row_ptrs[i][read_so_far], rows_to_read - read_so_far);
                if (got == 0) break;
                read_so_far += got;
            }
        }
        
        int total_pixels = rows_to_read * rs;
        if (blendMode == 0) { // Average
            for (int x = 0; x < total_pixels; x++) {
                int sum = 0;
                for (int i = 0; i < count; i++) sum += row_bufs[i][x];
                out_buf[x] = (unsigned char)(sum / count);
            }
        } else if (blendMode == 1) { // Lighten
            for (int x = 0; x < total_pixels; x++) {
                unsigned char mx = row_bufs[0][x];
                for (int i = 1; i < count; i++) {
                    if (row_bufs[i][x] > mx) mx = row_bufs[i][x];
                }
                out_buf[x] = mx;
            }
        }
        
        int written_so_far = 0;
        while(written_so_far < rows_to_read) {
            int wrote = jpeg_write_scanlines(&cc, &out_ptrs[written_so_far], rows_to_read - written_so_far);
            if (wrote == 0) break;
            written_so_far += wrote;
        }
    }
    
    jpeg_finish_compress(&cc);
    jpeg_destroy_compress(&cc);
    fclose(outf);
    
    for (int i = 0; i < count; i++) {
        jpeg_finish_decompress(&cds[i]);
        jpeg_destroy_decompress(&cds[i]);
        fclose(infs[i]);
        free(row_bufs[i]);
    }
    free(out_buf);
    
    return JNI_TRUE;
}
