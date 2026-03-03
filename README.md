# Sony JPG Cookbook - In-Camera LUT Support (BETA)
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/jbuchanan)

**⚠️ BETA STATUS:** This project is currently in early beta. While it is stable and produces high-quality results, it is a "proof of concept" running on 2014-era hardware. Please read the **Known Limitations** below before use.

Sony JPG Cookbook turns your older Sony Alpha camera into a modern film-simulation powerhouse. This app brings professional color grading directly to your camera hardware, allowing you to apply custom 3D LUTs (`.cube` files) to your photos the moment you press the shutter. 

## ✨ Features
* **Custom LUT Support:** Load your own `.cube` files directly from your SD card.
* **Auto-Processing Engine:** The app automatically detects new photos and processes them in the background. 
* **True Color Trilinear Interpolation:** High-fidelity color math prevents banding in gradients.
* **Dual Quality Modes:** Toggle between `PROXY (1.5MP)` for speed and `HIGH (6.0MP)` for quality.
* **Non-Destructive:** Original files are untouched. Graded copies are saved to a `/GRADED/` folder.

## 🚧 Known Limitations (The "Beta" Reality)
* **Processing Speed:** Because the camera uses a legacy processor, the "High" quality mode can take 15–20 seconds to process a single image. The app uses a "chunking" method to prevent the camera from crashing due to low memory.
* **JPEG Only:** This version is optimized for JPEGs. RAW files are ignored by the processor.
* **Battery Drain:** Heavy background processing will impact battery life more than standard shooting.
* **EXIF Data:** Currently, processed images do not retain original EXIF metadata (ISO, Shutter Speed, etc.). This is planned for a future update.

## 📖 How to Use

**1. Prep your SD Card**
- Create a folder named `LUTS` on the absolute root of your SD card. Drop your favorite standard `.cube` files into this folder. 

**2. Select your Recipe**
- Open the app. Press **DOWN** on your control wheel to highlight **Recipe**, then spin the wheel to select your LUT.
* *Note: Wait for the status to say `READY TO SHOOT`. The shutter is locked while the LUT is preloading.*

**3. Choose your Size**
- Press **DOWN** to highlight **SIZE**. Spin the dial to choose between `PROXY (1.5MP)` for speed or `HIGH (6.0MP)` for quality.

**4. Shoot!**
- Take a picture normally. The app will automatically see the new photo and begin `PROCESSING`. Once it says `SUCCESS`, your photo is in the `GRADED` folder!

## 📷 Supported Cameras
Compatible with PMCA cameras (Android 2.3.7 / API 10) including: **a5100, a6000, a6300, a6500, a7S II, a7R II, RX100 III/IV/V.**

## 🚀 Installation
1. Download the [pmca-gui installer](https://github.com/ma1co/Sony-PMCA-RE/releases).
2. Download the latest `.apk` from our [Releases](../../releases) page.
3. Connect camera via USB (MTP or Mass Storage mode) and use **pmca-gui** to install.
