#ifndef PROCESS_KERNEL_H
#define PROCESS_KERNEL_H

#include <stdint.h>

#define CLAMP(x) ((x) < 0 ? 0 : ((x) > 255 ? 255 : (x)))

// === KERNEL_UI_METADATA ===
// shadowToe, 0, 2
// rollOff, 0, 5
// colorChrome, 0, 2
// chromeBlue, 0, 2
// subtractiveSat, 0, 2
// halation, 0, 2
// vignette, 0, 5
// grain, 0, 5
// grainSize, 0, 2
// diffusion, 0, 2
// === END_METADATA ===

// --- SHARED HELPERS ---
inline long long get_vig_coef(int vignette, long long max_dist_sq) {
    int s_vig = vignette * 12;
    return ((long long)((s_vig * 256) / 100) << 24) / (max_dist_sq > 0 ? max_dist_sq : 1);
}

inline void generate_rolloff_lut(uint8_t* lut, int rollOff) {
    int s_roll = rollOff * 20;
    for (int i = 0; i < 256; i++) {
        int r_t = (i > 200) ? i - ((i - 200) * (i - 200) * s_roll) / 11000 : i;
        lut[i] = (uint8_t)CLAMP(r_t);
    }
}

inline uint32_t fast_rand(uint32_t* state) {
    // Xorshift for high-frequency "salt" noise
    uint32_t x = *state; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *state = x; return x;
}

// ==========================================
// FILM DIFFUSION PRE-PASS
//
// Simulates the lower spatial resolution of film's color dye layers
// compared to the sharp silver-halide luminance layer. A bi-directional
// IIR low-pass is applied to Cb and Cr only — luma stays razor-sharp.
//
// This causes color edges to "bloom" into each other organically, giving
// skin tones a silky quality and eliminating the clinical "digital grid"
// look of a modern sensor — without any visible blur.
//
// SOFT (1): ~3px effective chroma blur radius (forward + backward IIR)
// RICH (2): Stronger chroma blur + very gentle 4% forward luma neighbor
//           blend that breaks the perfect pixel grid of the sensor.
//
// chroma_work: caller-allocated buffer of (width * 2) int16_t.
// Memory cost: ~24KB at 6000px width. Allocated once per image, not per row.
// ==========================================
inline void apply_film_diffusion(
    uint8_t* row, int width, bool is_yuv, int diffusion,
    int16_t* chroma_work)
{
    if (diffusion == 0 || width < 4) return;

    // IIR alpha: higher = more memory = longer blur tail.
    // Forward + backward pass makes blur symmetric (zero phase shift).
    // SOFT: alpha=210  => half-life ~2.8px per direction => ~3px effective radius
    // RICH: alpha=220  => half-life ~4.2px per direction => ~4px effective radius
    const int alpha     = (diffusion == 1) ? 210 : 220;
    const int inv_alpha = 256 - alpha;
    int acc;

    if (is_yuv) {
        // --- YCbCr path: row bytes are [Y, Cb, Cr, Y, Cb, Cr, ...] ---
        // Extract signed chroma to working buffer
        for (int x = 0; x < width; x++) {
            chroma_work[x * 2]     = (int16_t)((int)row[x * 3 + 1] - 128);
            chroma_work[x * 2 + 1] = (int16_t)((int)row[x * 3 + 2] - 128);
        }

        // === Cb: forward IIR (left to right) ===
        acc = chroma_work[0];
        for (int x = 1; x < width; x++) {
            acc = (acc * alpha + (int)chroma_work[x * 2] * inv_alpha) >> 8;
            chroma_work[x * 2] = (int16_t)acc;
        }
        // === Cb: backward IIR (right to left) — makes the blur symmetric ===
        acc = chroma_work[(width - 1) * 2];
        for (int x = width - 2; x >= 0; x--) {
            acc = (acc * alpha + (int)chroma_work[x * 2] * inv_alpha) >> 8;
            chroma_work[x * 2] = (int16_t)acc;
        }

        // === Cr: forward IIR ===
        acc = chroma_work[1];
        for (int x = 1; x < width; x++) {
            acc = (acc * alpha + (int)chroma_work[x * 2 + 1] * inv_alpha) >> 8;
            chroma_work[x * 2 + 1] = (int16_t)acc;
        }
        // === Cr: backward IIR ===
        acc = chroma_work[(width - 1) * 2 + 1];
        for (int x = width - 2; x >= 0; x--) {
            acc = (acc * alpha + (int)chroma_work[x * 2 + 1] * inv_alpha) >> 8;
            chroma_work[x * 2 + 1] = (int16_t)acc;
        }

        // Write blurred chroma back. Luma (row[x*3]) is completely untouched.
        for (int x = 0; x < width; x++) {
            row[x * 3 + 1] = (uint8_t)CLAMP(128 + (int)chroma_work[x * 2]);
            row[x * 3 + 2] = (uint8_t)CLAMP(128 + (int)chroma_work[x * 2 + 1]);
        }

        // RICH mode: add a very gentle forward luma neighbor blend.
        // At 4% blend ratio this is nearly imperceptible as "blur" but
        // perceptually removes the clinical "perfect pixel" edge of the sensor.
        if (diffusion >= 2) {
            int prev_y = (int)row[0];
            for (int x = 1; x < width; x++) {
                int cur_y = (int)row[x * 3];
                int new_y = (cur_y * 246 + prev_y * 10) >> 8;
                prev_y = cur_y;     // save original before overwriting
                row[x * 3] = (uint8_t)new_y;
            }
        }

    } else {
        // --- RGB path: convert to YCbCr, blur Cb/Cr, reconstruct ---
        //
        // By blurring chroma BEFORE the LUT runs, we shift each pixel's
        // position in 3D RGB space slightly toward its neighbors' hues.
        // The trilinear LUT interpolation then operates on these "dye-blurred"
        // positions, causing the LUT's color response to bleed at color
        // transitions — exactly how film dye layers respond to light.
        //
        // BT.601 forward coefficients (integer approximation, full range):
        //   Cb = (-43*R - 85*G + 128*B) >> 8
        //   Cr = (128*R - 107*G - 21*B) >> 8
        for (int x = 0; x < width; x++) {
            int r = row[x * 3], g = row[x * 3 + 1], b = row[x * 3 + 2];
            chroma_work[x * 2]     = (int16_t)((-43 * r - 85 * g + 128 * b) >> 8);
            chroma_work[x * 2 + 1] = (int16_t)((128 * r - 107 * g - 21 * b) >> 8);
        }

        // === Cb: forward + backward IIR ===
        acc = chroma_work[0];
        for (int x = 1; x < width; x++) {
            acc = (acc * alpha + (int)chroma_work[x * 2] * inv_alpha) >> 8;
            chroma_work[x * 2] = (int16_t)acc;
        }
        acc = chroma_work[(width - 1) * 2];
        for (int x = width - 2; x >= 0; x--) {
            acc = (acc * alpha + (int)chroma_work[x * 2] * inv_alpha) >> 8;
            chroma_work[x * 2] = (int16_t)acc;
        }

        // === Cr: forward + backward IIR ===
        acc = chroma_work[1];
        for (int x = 1; x < width; x++) {
            acc = (acc * alpha + (int)chroma_work[x * 2 + 1] * inv_alpha) >> 8;
            chroma_work[x * 2 + 1] = (int16_t)acc;
        }
        acc = chroma_work[(width - 1) * 2 + 1];
        for (int x = width - 2; x >= 0; x--) {
            acc = (acc * alpha + (int)chroma_work[x * 2 + 1] * inv_alpha) >> 8;
            chroma_work[x * 2 + 1] = (int16_t)acc;
        }

        // Reconstruct RGB: preserve original per-pixel luma, apply blurred chroma.
        // BT.601 inverse: R = Y + 1.402*Cr,  G = Y - 0.344*Cb - 0.714*Cr,  B = Y + 1.772*Cb
        for (int x = 0; x < width; x++) {
            int r = row[x * 3], g = row[x * 3 + 1], b = row[x * 3 + 2];
            int y  = (77 * r + 150 * g + 29 * b) >> 8;
            int cb = (int)chroma_work[x * 2];
            int cr = (int)chroma_work[x * 2 + 1];
            row[x * 3]     = (uint8_t)CLAMP(y + ((359 * cr) >> 8));
            row[x * 3 + 1] = (uint8_t)CLAMP(y - ((88  * cb) >> 8) - ((183 * cr) >> 8));
            row[x * 3 + 2] = (uint8_t)CLAMP(y + ((453 * cb) >> 8));
        }

        // RICH mode: 4% forward luma neighbor blend, luma-preserving.
        // Blends each pixel's color slightly with its left neighbor while
        // rescaling to maintain the original brightness — creates organic
        // micro-texture at color edges without darkening the image.
        if (diffusion >= 2) {
            int prev_r = (int)row[0], prev_g = (int)row[1], prev_b = (int)row[2];
            for (int x = 1; x < width; x++) {
                int cur_r = (int)row[x * 3], cur_g = (int)row[x * 3 + 1], cur_b = (int)row[x * 3 + 2];
                int orig_y = (77 * cur_r + 150 * cur_g + 29 * cur_b) >> 8;
                // 96% self + 4% left neighbor
                int blen_r = (cur_r * 246 + prev_r * 10) >> 8;
                int blen_g = (cur_g * 246 + prev_g * 10) >> 8;
                int blen_b = (cur_b * 246 + prev_b * 10) >> 8;
                // Luma-preserving rescale: keep brightness, only change texture
                int blen_y = (77 * blen_r + 150 * blen_g + 29 * blen_b) >> 8;
                if (blen_y > 0 && blen_y != orig_y) {
                    int sc = (orig_y << 8) / blen_y;
                    blen_r = CLAMP((blen_r * sc) >> 8);
                    blen_g = CLAMP((blen_g * sc) >> 8);
                    blen_b = CLAMP((blen_b * sc) >> 8);
                }
                prev_r = cur_r; prev_g = cur_g; prev_b = cur_b;
                row[x * 3] = (uint8_t)blen_r; row[x * 3 + 1] = (uint8_t)blen_g; row[x * 3 + 2] = (uint8_t)blen_b;
            }
        }
    }
}

// ==========================================
// PATH A: RGB + LUT + ANALOG PHYSICS
// ==========================================
inline void process_row_rgb(
    uint8_t* row, int width, int abs_y, long long cx, long long cy_center, long long vig_coef,
    int shadowToe, int rollOff, int colorChrome, int chromeBlue,
    int subtractiveSat, int halation, int vignette,
    int grain, int grainSize, uint32_t& seed,
    int opac_mapped, const int* map,
    const uint8_t* nativeLut, int nativeLutSize, int lutMax, int lutSize2)
{
    int s_roll   = rollOff * 20;
    int s_chrome = colorChrome * 40;
    int s_blue   = chromeBlue * 40;
    int s_sat    = subtractiveSat * 40;
    int s_grain  = grain * 20;
    if (grainSize == 1) s_grain = (s_grain * 3) >> 1;
    if (grainSize == 2) s_grain = (s_grain * 5) >> 2;

    long long dy = (long long)(abs_y - cy_center);
    long long d_sq = ((long long)(0 - cx) * (long long)(0 - cx)) + (dy * dy);
    long long d_sq_step = 1 - (2 * (long long)cx);

    for (int x = 0; x < width; x++) {
        int i = x * 3;
        int r = row[i], g = row[i+1], b = row[i+2];

        // --- LUT CALCS (Tetrahedral Trilinear Interpolation) ---
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

        const uint8_t* p    = &nativeLut[(x0 + y0*nativeLutSize + z0*lutSize2)*3];
        const uint8_t* p1_v = &nativeLut[v1*3];
        const uint8_t* p2_v = &nativeLut[v2*3];
        const uint8_t* p3_v = &nativeLut[(x1 + y1*nativeLutSize + z1*lutSize2)*3];

        int outR = r + ((((p[0]*w0 + p1_v[0]*w1 + p2_v[0]*w2 + p3_v[0]*w3) >> 7) - r) * opac_mapped >> 8);
        int outG = g + ((((p[1]*w0 + p1_v[1]*w1 + p2_v[1]*w2 + p3_v[1]*w3) >> 7) - g) * opac_mapped >> 8);
        int outB = b + ((((p[2]*w0 + p1_v[2]*w1 + p2_v[2]*w2 + p3_v[2]*w3) >> 7) - b) * opac_mapped >> 8);

        // --- FILM DENSITY & PHYSICS ---
        int currentY = (outR*77 + outG*150 + outB*29) >> 8;
        int targetY  = currentY;

        if (shadowToe > 0) {
            int lift = (shadowToe == 1) ? 35 : 55;
            if (targetY < lift) targetY += ((lift - targetY) * (lift - targetY)) / (shadowToe == 1 ? 140 : 180);
        }
        if (rollOff > 0 && targetY > 200) targetY -= ((targetY - 200) * (targetY - 200) * s_roll) / 11000;

        int cb_p = ((-38 * outR - 74 * outG + 112 * outB) >> 8);
        int cr_p = ((112 * outR - 94 * outG - 18 * outB) >> 8);
        int sat_p = (cb_p < 0 ? -cb_p : cb_p) + (cr_p < 0 ? -cr_p : cr_p);

        if (s_chrome > 0 && sat_p > 15) {
            int drop = ((sat_p - 15) * s_chrome) >> 8;
            if (targetY > 160) { int fade = 255 - ((targetY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) >> 8; }
            if (drop > (targetY >> 2)) drop = targetY >> 2;
            targetY -= drop;
        }
        if (s_blue > 0 && cb_p > 5 && cr_p < 25) {
            int drop = (cb_p * s_blue) >> 7;
            if (targetY > 160) { int fade = 255 - ((targetY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) >> 8; }
            if (targetY < 50) { int fade = (targetY * 5); if (fade > 255) fade = 255; drop = (drop * fade) >> 8; }
            if (drop > (targetY >> 2)) drop = targetY >> 2;
            targetY -= drop;
        }
        if (s_sat > 0 && sat_p > 20) {
            int density = ((sat_p - 20) * s_sat) >> 8;
            if (targetY > 200) { int fade = 255 - ((targetY - 200) * 4); if (fade < 0) fade = 0; density = (density * fade) >> 8; }
            if (density > (targetY >> 2)) density = targetY >> 2;
            targetY -= density;
        }

        if (targetY < 8) targetY = 8;
        if (targetY != currentY) {
            int r256 = (targetY * 256) / (currentY == 0 ? 1 : currentY);
            outR = (outR * r256) >> 8; outG = (outG * r256) >> 8; outB = (outB * r256) >> 8;
        }

        // --- FILM HALATION ---
        // Extended range: starts at 220 (was 245) for a gradual, realistic
        // warm red bloom that creeps in from specular highlights downward.
        // The lower threshold means halation is visible on bright windows,
        // chrome, and skin highlights — not just blown-out clipping.
        if (halation > 0 && targetY > 220) {
            int hl = (targetY - 220) * (halation == 1 ? 3 : 7);
            outR += hl >> 1;    // warm red lift
            outG -= hl >> 5;    // imperceptible green drop
            outB -= hl >> 3;    // blue rolls off (shifts toward warm)
        }

        if (vignette > 0) {
            int v_m = 256 - (int)((d_sq * vig_coef) >> 24);
            if (v_m < 0) v_m = 0;
            outR = (outR * v_m) >> 8; outG = (outG * v_m) >> 8; outB = (outB * v_m) >> 8;
        }

        // --- GRAIN (ORGANIC JITTERED FBM WITH ANALOG PER-CHANNEL CHARACTER) ---
        if (s_grain > 0) {
            // Salt (sharp high-frequency noise)
            uint32_t salt_raw = fast_rand(&seed);
            int salt = (int)(salt_raw & 0xFF) - 128;
            int noise = salt;

            if (grainSize > 0) {
                // Clump (low-frequency organic grouping via spatial hash)
                uint32_t bx = (grainSize == 1) ? (x >> 1) : ((x * 21845) >> 16);
                uint32_t by = (grainSize == 1) ? (abs_y >> 1) : ((abs_y * 21845) >> 16);
                uint32_t h = (bx * 1274126177U) ^ (by * 2654435761U) ^ seed;
                h = (h ^ (h >> 13)) * 374761393U;
                int clump = (int)(h & 0xFF) - 128;
                // Mixing: 40% sharp salt, 60% soft clump
                noise = (salt * 100 + clump * 150) >> 8;
            }

            int mask = (targetY < 128) ? targetY : 255 - targetY;
            if (targetY < 64) mask = (mask * targetY) >> 6;
            int gv = (noise * mask * s_grain) >> 15;

            // Film per-channel grain character:
            //   Blue-sensitive emulsion layer is thickest and grainiest,
            //   especially in shadow regions (classic analog trait).
            //   Red-sensitive layer is thinnest and calmest.
            //   This creates the subtle color variation in shadows that
            //   makes grain look genuinely analog rather than digital noise.
            if (targetY < 128 && gv != 0) {
                int abs_gv    = gv < 0 ? -gv : gv;
                int shadow_w  = (128 - targetY) >> 4;              // 0..8 range
                int var       = (abs_gv * shadow_w + 4) >> 3;      // small differential
                int sign      = (gv >= 0) ? 1 : -1;
                outR += gv - sign * (var >> 3);    // Red: slightly calmer in shadows
                outG += gv;                         // Green: neutral reference
                outB += gv + sign * (var >> 2);    // Blue: slightly grainier in shadows
            } else {
                outR += gv; outG += gv; outB += gv;
            }
        }

        row[i] = (uint8_t)CLAMP(outR); row[i+1] = (uint8_t)CLAMP(outG); row[i+2] = (uint8_t)CLAMP(outB);
        d_sq += d_sq_step; d_sq_step += 2;
    }
}

// ==========================================
// PATH B: THE YUV EXPRESSWAY
// ==========================================
inline void process_row_yuv(
    uint8_t* row, int width, int abs_y, long long cx, long long cy_center, long long vig_coef,
    int shadowToe, int rollOff, int colorChrome, int chromeBlue,
    int subtractiveSat, int halation, int vignette,
    int grain, int grainSize, uint32_t& seed,
    const uint8_t* rolloff_lut)
{
    int s_chrome = colorChrome * 40;
    int s_blue   = chromeBlue * 40;
    int s_sat    = subtractiveSat * 40;
    int s_grain  = grain * 20;
    if (grainSize == 1) s_grain = (s_grain * 3) >> 1;
    if (grainSize == 2) s_grain = (s_grain * 5) >> 2;

    long long dy = (long long)(abs_y - cy_center);
    long long d_sq = ((long long)(0 - cx) * (long long)(0 - cx)) + (dy * dy);
    long long d_sq_step = 1 - (2 * (long long)cx);

    for (int x = 0; x < width; x++) {
        int i = x * 3;
        int oldY = row[i];
        int outY = oldY;

        if (shadowToe > 0) {
            int lift = (shadowToe == 1) ? 35 : 55;
            if (outY < lift) outY += ((lift - outY) * (lift - outY)) / (shadowToe == 1 ? 140 : 180);
        }
        if (rollOff > 0) outY = rolloff_lut[outY];
        if (vignette > 0) {
            int v_m = 256 - (int)((d_sq * vig_coef) >> 24);
            if (v_m < 0) v_m = 0;
            outY = (outY * v_m) >> 8;
        }

        int cb = row[i+1] - 128, cr = row[i+2] - 128;
        int sat = (cb >= 0 ? cb : -cb) + (cr >= 0 ? cr : -cr);

        if (s_chrome > 0 && sat > 15) {
            int drop = ((sat - 15) * s_chrome) >> 8;
            if (outY > 160) { int fade = 255 - ((outY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) >> 8; }
            if (drop > (outY >> 2)) drop = outY >> 2;
            outY -= drop;
        }
        if (s_blue > 0 && cb > 5 && cr < 25) {
            int drop = (cb * s_blue) >> 7;
            if (outY > 160) { int fade = 255 - ((outY - 160) * 3); if (fade < 0) fade = 0; drop = (drop * fade) >> 8; }
            if (outY < 50) { int fade = (outY * 5); if (fade > 255) fade = 255; drop = (drop * fade) >> 8; }
            if (drop > (outY >> 2)) drop = outY >> 2;
            outY -= drop; cr -= (drop >> 1);
        }
        if (s_sat > 0 && sat > 20) {
            int density = ((sat - 20) * s_sat) >> 8;
            if (outY > 200) { int fade = 255 - ((outY - 200) * 4); if (fade < 0) fade = 0; density = (density * fade) >> 8; }
            if (density > (outY >> 2)) density = outY >> 2;
            outY -= density;
        }

        if (outY < 8) outY = 8;

        // --- FILM HALATION (YUV path) ---
        // Extended range: starts at 220 (was 245), gradual warm chroma bloom.
        if (halation > 0 && outY > 220) {
            int push = (outY - 220) * (halation == 1 ? 2 : 5);
            cr += push >> 1;    // shift chroma toward warm/red
            cb -= push >> 3;    // slight blue rolloff in hot highlights
        }

        if (oldY != outY) {
            int r256 = (outY * 256) / (oldY == 0 ? 1 : oldY);
            cb = (cb * r256) >> 8; cr = (cr * r256) >> 8;
        }

        // --- GRAIN (ORGANIC FBM) ---
        if (s_grain > 0) {
            uint32_t salt_raw = fast_rand(&seed);
            int salt = (int)(salt_raw & 0xFF) - 128;
            int noise = salt;

            if (grainSize > 0) {
                uint32_t bx = (grainSize == 1) ? (x >> 1) : ((x * 21845) >> 16);
                uint32_t by = (grainSize == 1) ? (abs_y >> 1) : ((abs_y * 21845) >> 16);
                uint32_t h = (bx * 1274126177U) ^ (by * 2654435761U) ^ seed;
                h = (h ^ (h >> 13)) * 374761393U;
                int clump = (int)(h & 0xFF) - 128;
                noise = (salt * 100 + clump * 150) >> 8;
            }

            int mask = (outY < 128) ? outY : 255 - outY;
            if (outY < 64) mask = (mask * outY) >> 6;
            outY += (noise * mask * s_grain) >> 15;
        }

        row[i] = (uint8_t)CLAMP(outY); row[i+1] = (uint8_t)CLAMP(128+cb); row[i+2] = (uint8_t)CLAMP(128+cr);
        d_sq += d_sq_step; d_sq_step += 2;
    }
}

#endif
