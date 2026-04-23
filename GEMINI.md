# Project Context & Task Tracker

##  foundational Mandates
- **Role:** Principal Software Engineer for JPEG.CAM (Android API 10) & Camera-Recipe-Hub (React/Supabase).
- **Owner:** Non-programmer; explain in plain language first, then code. No technical assumptions.
- **Constraints:**
    - Android API 10 (Gingerbread) ONLY. No modern Java/Android features.
    - C++11 NDK: No NEON SIMD. Scalar optimizations only.
    - Memory: Strict 24MB Dalvik heap. Use `libjpeg-turbo` for image manipulation.
    - Synchronization: `process_kernel.h` must remain identical between `jpegcam` and `camera-recipe-hub`.

## Status & Task Tracker

### In-Flight Tasks
- [x] **Diptych Refactor:** Encapsulated logic into `DiptychManager.java`.
- [x] **Multi-Core Engine:** Implemented persistent worker pool with 4 threads and 32-row chunks.
- [ ] **Fix In-Camera Review:** Address "Decode Error" / "Memory Error" when viewing photos on camera LCD.
- [ ] **Optimize Diptych Resolution:** Balance memory safety with output quality (currently 1/4 scale).

### Issues & Observations
- **Review Error:** photos are valid on PC but fail in-camera review. Likely due to Sony's media scanner database or header incompatibilities.
- **Diptych Preview:** User reports preview after first pic never comes up.
- **Diptych Stitch:** User reports stitch never happens.

### Recently Completed
- Removed 1-byte dummy file write (was corrupting media scanner).
- Implemented "Instant Preview" in `DiptychManager` by decoding original photo.
- Fixed UI layering (Overlay at index 0).
- Fixed Shutter Lock (isProcessing cleared after first shot).
