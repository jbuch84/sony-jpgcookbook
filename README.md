# Sony JPG Cookbook - In-Camera LUT Support
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/jbuchanan)

Turn your older Sony Alpha camera into a modern film-simulation powerhouse. This app brings professional color grading directly to your camera hardware, allowing you to apply custom 3D LUTs (`.cube` files) to your photos the moment you press the shutter. 

No more waiting to get home to edit—shoot with your favorite film looks right in the field!

## ✨ Features
* **Custom LUT Support:** Load your own `.cube` files directly from your SD card.
* **Auto-Processing Engine:** Just take a picture! The app automatically detects new photos and processes them in the background. 
* **True Color Trilinear Interpolation:** Uses advanced color math to blend pixels smoothly, preventing the ugly color banding common in other mobile image processors.
* **Dual Quality Modes:** Change your output size on the fly.
  * **PROXY (1.5MP):** Lightning-fast processing, perfect for quick social media sharing to your phone.
  * **HIGH (6.0MP):** High-resolution output utilizing a custom memory-chunking engine to safely bypass the camera's strict RAM limits.
* **Non-Destructive Workflow:** Your original RAWs/JPEGs are left completely untouched. The app creates a brand new copy of your photo in a dedicated `GRADED` folder on your SD card, preserving the exact original filename (e.g., `DSC01234.JPG`) so it's easy to match them up later.

## 📖 How to Use

**1. Prep your SD Card**
Create a folder named `LUTS` on the absolute root of your SD card. Drop your favorite standard `.cube` files into this folder. 

**2. Select your Recipe**
Open the app on your camera. 
* Press the **DOWN** button on your control wheel to cycle through the menu options (Shutter, Aperture, ISO, Exposure, Recipe, Size).
* When **Recipe** is highlighted green, spin the control wheel left or right to select your LUT.
* *Note: The Status bar will briefly flash `PRELOADING...` while it loads the color math into the camera's RAM. You cannot take a photo until the status says `READY TO SHOOT`.*

**3. Choose your Size**
Press **DOWN** until **SIZE** is highlighted. Spin the dial to choose between `PROXY (1.5MP)` for speed or `HIGH (6.0MP)` for quality.

**4. Shoot!**
Take a picture normally. The app will automatically see the new photo, lock it in, and begin `PROCESSING`. Once it says `SUCCESS`, your newly graded photo is waiting for you in the `GRADED` folder on your SD card!

## 📷 Supported Cameras
This app supports Sony cameras compatible with the PMCA framework (Android 2.3.7 / API 10). This includes popular models like the **a5100, a6000, a6300, a6500, a7S II, a7R II**, and various **RX100** models.

*(Note: This app is currently optimized specifically for JPEGs).*

## 🚀 Installation

1. **Prerequisites:**
   * Download the [pmca-gui installer](https://github.com/ma1co/Sony-PMCA-RE/releases) by ma1co. This is the tool required to flash custom APKs onto Sony hardware.

2. **Download the APK:** * Go to the [Releases](../../releases) page of this repository and download the latest `.apk` file.

3. **Install to Camera:**
   * Connect your camera to your computer via USB (ensure your camera's USB Connection setting is set to **MTP** or **Mass Storage** mode).
   * Open **pmca-gui**, go to the **Install app** tab, select your downloaded APK, and click **Install**.
