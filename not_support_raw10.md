## Why RAW10 Cannot Be Replaced, and Why Final Photos Cannot Be Replaced in Certain Stock Camera Apps

**Test environment:**
- Device: Pixel 3a (sargo)
- OS: Android 12L / SDK 32
- Build: SP2A.220505.008
- Camera package: `com.google.android.GoogleCamera`

The module works correctly on this device: both the preview and video recording are successfully replaced, but the final saved photo still shows the original scene.

### 1. Camera Session Stream Analysis

Using `dumpsys media.camera`, we inspected the stream configuration of a camera session in photo mode:

| Stream | Resolution | Format | Consumer | Frames produced |
|---|---|---|---|---:|
| 0 | 1024×768 | 0x22 IMPL_DEFINED | ImageReader-1024x768f22m11 | 288 |
| 1 | 1024×768 | 0x23 YCbCr_420_888 | ImageReader-1024x768f23m52 | 288 |
| 2 | 4032×3024 | 0x25 RAW10 | ImageReader-4032x3024f25m22 | 288 |
| 3 | 4032×3024 | 0x23 YCbCr_420_888 | ImageReader-4032x3024f23m22 | 0 |

Additionally, there is no BLOB (0x21) output stream in the entire Camera Session.

During the same period, all frames captured by the module belong to only three formats:

- `0x20` — RAW16
- `0x22` — IMPL_DEFINED
- `0x25` — RAW10

The JPEG Picture CALL counter remains at **0**, meaning `cs_blob_probe` is never invoked.

### 2. Why Does the JPEG BLOB Hook Never Get Triggered?

There are three important observations:

**① RAW10 is a continuously running stream, not a shutter-triggered output.**

The `Frames produced` count for RAW10 increases in sync with both preview streams (288 vs. 288). This strongly suggests that RAW10 is operating as a continuous stream or RAW ring buffer, rather than a one-shot output generated specifically when the shutter is pressed.

**② The high-resolution YUV stream is configured but produces no frames.**

The 4032×3024 YUV stream (Stream 3) exists in the session configuration, but its `Frames produced` count remains at 0.

Its Combined usage is `131075` (`0x20003`). However, the presence of a configured stream alone does not establish that it is actively participating in image processing.

**③ There is no JPEG BLOB output stream at the Camera3 level.**

No `BLOB (0x21)` stream exists in the session, and the JPEG Picture CALL counter remains at 0.

Taken together, these observations suggest that **the HAL primarily outputs RAW10 data, while Google Camera performs HDR+ multi-frame processing and JPEG encoding inside its own application process.**

In other words, JPEG generation bypasses the Camera3-level BLOB output pipeline entirely. As a result, our existing JPEG BLOB Hook has nothing to intercept.

### 3. Why Does the Thumbnail Look Correct While the Final Photo Shows the Original Scene?

The behavior observed by the user is:

1. Immediately after pressing the shutter, the UI displays a thumbnail containing the module's replacement image.
2. Once image processing is complete, the thumbnail is replaced by the final processed photo.
3. The final saved photo still shows the original scene.

This does not mean that the Camera HAL restores or overwrites the modified image. Instead, the two images come from different processing paths:

- **Postview / Preview path:** Uses frames that have already been replaced by the module. Therefore, the immediate shutter thumbnail looks correct.
- **Final Image path:** Google Camera processes RAW data through its own HDR+ pipeline and performs JPEG encoding internally. This final image never passes through the module's existing replacement logic.

Therefore, this is fundamentally a difference in image processing paths and display timing, rather than a replacement operation that gets reverted afterward.

### 4. What Makes Direct RAW10 Replacement Difficult?

In theory, RAW10 could be intercepted and replaced before it reaches Google Camera. However, this is considerably more complicated than replacing a JPEG BLOB.

RAW10 is not ordinary final image data. To ensure that the replacement image can correctly enter the subsequent HDR+ processing pipeline, several factors must be considered:

- **RAW data reconstruction:** Correctly handle RAW10 Bayer packing, bit depth, black level, and relevant sensor parameters.
- **DNG color reconstruction:** Properly handle the Color Filter Array, Color Matrix, white balance, and color space conversion.
- **Vendor-specific ISP and post-processing pipelines:** Some stock camera apps may rely on proprietary RAW + YUV processing workflows.
- **Metadata consistency:** Exposure, ISO, white balance, lens parameters, and other Capture Metadata must remain reasonably consistent with the replacement image.
- **HDR+ multi-frame processing:** If RAW10 is a continuously running ring buffer, frame synchronization, frame selection, and inter-frame consistency must also be addressed.

This is particularly important for stock camera applications, where RAW data may only be one input to a proprietary post-processing pipeline.

Even if RAW10 replacement succeeds, that does not guarantee that the final JPEG will be generated correctly.

### 5. An Alternative: Replace the Final Image After Processing Inside the App

If the goal is to ensure that the final saved photo is also replaced, another approach would be to bypass the Camera3 output streams entirely.

Instead, the replacement could target the image data inside the camera application itself, after HDR+ processing but before JPEG encoding or file saving.

However, this would mean returning to an application-process injection and hooking approach.

There are several significant drawbacks:

- Different camera applications have very different image processing architectures.
- Google Camera may rely on proprietary HDR+ and image processing components.
- Application updates can invalidate existing Hook points.
- Process injection is more likely to be detected by integrity checks or anti-injection mechanisms.
