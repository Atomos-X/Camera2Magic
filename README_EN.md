# Camera2 Magic: A Virtual Camera Software (Supports Android 10+)

**PLEASE DO NOT USE THIS SOFTWARE FOR ILLEGAL PURPOSES.**    

![img](document/x.jpg)    

## Documentation    
- Click [workflow.md](document/workflow.md) to view.    

## Usage Precautions    
**Development & Testing Device: OnePlus 8T LPDDR4 (ColorOS Port 16.0)**    
- Video files must be placed in local public storage directories, such as `DCIM`, `Movies`, etc.    
- The module must be granted permission to **read media/files**.    
- The target application (e.g., TikTok) must be granted permission to **read media/files**.    
- The device must be **Rooted** with the **LSPosed Framework** installed. Enable the module in LSPosed Manager and select the target scope (e.g., `TikTok`, `Telegram`, etc.).    
- After installing or updating the module, you must **Force Stop** the hooked application and reopen it for changes to take effect.    
- Open this module, tap the thumbnail area to trigger the system media picker: select a video file and confirm.    
- Enable audio or other features as needed.    
- If you intend to use the floating panel, ensure the hooked application has been granted the **"Display over other apps" (Floating Window)** permission.    
- Open the hooked application and use its camera function; you should see the preview screen replaced by your selected video.    
- If it doesn't work as expected, please enable the "Print Logs" feature. Use `adb logcat | grep "VCX"` for debugging.    

## Development Progress    

### Hook Camera1/2 API    
- [x] Initial detection of the target app's working mode (Standard, QR Scanning, Face Detection)    
    - [x] Hook all working modes (Default)    
- [x] Local media video decoding    
    - [x] FFmpeg demuxer: Initial work for network video stream support completed    
    - [x] AMediaCodec Hardware Decoding (Smooth 4K@60fps HEVC on Snapdragon 865/SM8250)    
        - [x] Double Buffering (Ping-Pong Mechanism)    
        - [x] GPU-accelerated transcoding to NV21    
    - [x] Initial audio decoding support    
- [ ] Network video stream support    
- [x] Preview screen replacement    
    - [x] Corrected `Preview Surface` rendering to match the visual aspect ratio    
    - [x] Image cropping to adapt to `Preview Surface` ratio (minimizing stretching/distortion)    
    - [x] Real-time adaptation to target app's ratio switching    
- [x] `NV21 byte[]` generation    
    - [x] Camera1 API: Capture photos using current NV21 data (Default)    
    - [x] Force conversion of NV21 data to "Visual Upright" orientation (Default)    
- [ ] Use specific images to replace capture/photo data?    

### Module UI    
- [x] Main Interface    
    - [x] Media permission requests    
    - [x] Tap empty thumbnail to select media / Long-press thumbnail to delete    
- [x] Feature Toggles    
    - [x] Temporary module master switch    
    - [x] Audio playback toggle    
    - [x] Log printing toggle (Error logs are still printed)    
    - [x] Floating panel injection toggle    

### Known Issues    
- [x] Fixed: Green lines on video edges.    
- [x] Fixed: Camera1 API failing to correctly stop decoding/playback threads after photo capture or video recording.    
- [x] Fixed: Camera2 API failing to correctly hook the camera `close` signal when switching menus in certain apps (e.g., `TikTok: POST <-> TEMPLATES`), leading to unexpected thread behavior.    
- [ ] Ongoing: If camera permission is granted at runtime, 4K video may experience audio desync or pixelation on older devices. This can be temporarily resolved by toggling the front/rear camera; a permanent fix is pending in a future update.    

### Floating Window (Injected into target app for Debugging)    
- [x] Floating window framework    
- [x] Low-resolution and low-frame-rate preview of NV21 bytes    
- [ ] Complete other feature menus    

### Documentation
- [x] Detailed documentation completed.    
