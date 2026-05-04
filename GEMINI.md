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
- [ ] **Sony ISP Calibration Mode** — Guided UI in jpegcam: automatically steps user through each Creative Style, displays the 512×512 HaldCLUT identity grid on screen, prompts to shoot it, captures result JPEG. Uploads per-Creative-Style HaldCLUTs to server as per-camera-model calibration profiles. These feed RecipeStudio as accurate Sony ISP baselines — better coverage than any physical color checker.
- [ ] **Fix In-Camera Review** (low priority) — "Decode Error" / "Memory Error" when viewing processed photos on camera LCD. Likely Sony media scanner database or header incompatibility. Photos are valid on PC.

### Issues & Observations
- **In-Camera Review Error:** photos are valid on PC but fail in-camera review. Likely due to Sony's media scanner database or header incompatibilities. Not blocking any current feature work.

### Recently Completed
- Diptych Refactor: encapsulated logic into `DiptychManager.java`.
- Multi-Core Engine: persistent worker pool with 4 threads and 32-row chunks.
- Removed 1-byte dummy file write (was corrupting media scanner).
- Implemented "Instant Preview" in `DiptychManager` by decoding original photo.
- Fixed UI layering (Overlay at index 0).
- Fixed Shutter Lock (isProcessing cleared after first shot).
- `.cam` bundle format — ZIP archive containing `recipe.json` + `lut.cube` + optional `grain.png`. jpegcam downloads and unpacks `.cam` files from camera-recipe-hub for recipe application.
