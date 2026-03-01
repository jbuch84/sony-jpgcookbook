# Alpha OS Dashboard (Sony PMCA File Server)
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/jbuchanan)

A modern, lightning-fast web dashboard and file server for Sony Alpha cameras. 

Built on the PlayMemories Camera Apps (PMCA) OpenMemories framework, this app bypasses Sony's legacy restrictions to serve your photos directly to your phone or computer over local Wi-Fi. It features a responsive, dark-mode web UI, instant EXIF extraction, and batch downloading—all processed securely offline.

[Image of modern software architecture showing a mobile device connecting directly to a camera via local Wi-Fi]

## ✨ Features
* **Zero-Friction Web UI:** No dedicated mobile apps required. Just connect to the camera's Wi-Fi and open your browser to view a sleek, responsive gallery.
* **Lightning Fast Previews:** Uses the camera's embedded hardware thumbnails for instant "blur-up" loading, preventing the camera's processor from crashing under heavy loads.
* **Offline EXIF Extraction:** Click any photo to see exactly how you shot it (Aperture, Shutter Speed, ISO, Focal Length). The heavy binary math is processed locally by your browser, keeping the camera blazing fast.
* **Safe Batch Downloading:** Select multiple photos and download them to your device. The app uses a throttled sequential download queue to protect the decade-old camera hardware from network flooding.
* **Date Filters:** Instantly filter your SD card by *Today*, *This Week*, *This Month*, or *This Year*.

## 📷 Supported Cameras
This app supports Sony cameras compatible with the PMCA framework (Android 2.3.7 / API 10). This includes popular models like the **a5100, a6000, a6300, a6500, a7S II, a7R II**, and various **RX100** models.

*Note: This app is optimized specifically for JPEGs and MP4s. Sony RAW (`.arw`) files are intentionally filtered out to prevent browser memory crashes over slow Wi-Fi connections.*

## 🚀 Installation

1. **Prerequisites:**
   * Download the [pmca-gui installer](https://github.com/ma1co/Sony-PMCA-RE/releases) by ma1co. This is the tool required to flash custom APKs onto Sony hardware.

2. **Download the APK:** * Go to the [Releases](../../releases) page of this repository and download the latest `AlphaOS-Dashboard-v1.X.apk`.

3. **Install to Camera:**
   * Connect your camera to your computer via USB (ensure it is in **MTP** or **Mass Storage** mode).
   * Open **pmca-gui**, go to the **Install app** tab, select your downloaded APK, and click **Install**.

## 💻 Screenshots
<img width="827" height="775" alt="image" src="https://github.com/user-attachments/assets/859c9267-c0c8-4e08-afe2-932f8e893ed1" />
<img width="828" height="777" alt="image" src="https://github.com/user-attachments/assets/40a8356e-2745-46ec-9714-7600c552298a" />

