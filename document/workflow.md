1. Native生命周期/线程架构流程图   
    ```mermaid
    flowchart TD
    A[Java / Hook 层<br/>获取 Surface] --> B[Native registerSurfaceIfNew]
    B --> C[保存 ANativeWindow<br/>SurfaceInfo 宽高]
    C --> D[初始化 EGL / GL Context]
    D --> E[初始化解码与转码组件<br/>MediaCodec / FFmpeg / GL资源]
    E --> F[启动工作线程]
    
        F --> T1[Demuxer Thread]
        F --> T2[Video Decode Thread]
        F --> T3[Audio Decode Thread]
        F --> T4[Render Thread]
        F --> T5[NV21 Transcode Thread]
    
        T1 -->|video packets| QV[ThreadSafeQueue<VideoPacket>]
        T1 -->|audio packets| QA[ThreadSafeQueue<AudioPacket>]
    
        QV -->|背压控制| T2
        QA -->|背压控制| T3
    ```

2. Demuxer线程（背压）  
    ```mermaid
    flowchart TD
        D0[Demuxer Thread Loop]
        D0 --> D1[AMediaExtractor / FFmpeg demux]
        D1 --> D2{Packet Type?}
        D2 -->|Video| D3[push VideoPacket -> VideoQueue]
        D2 -->|Audio| D4[push AudioPacket -> AudioQueue]
    
        D3 --> D5{Queue Full?}
        D4 --> D5
        D5 -->|Yes| D6[阻塞 / wait<br/>背压生效]
        D5 -->|No| D0
    ```
3. `视频解码` -> `OES` -> `RGBA Texture` 管线    
    ```mermaid
    flowchart TD
        V0[Video Decode Thread]
        V0 --> V1[MediaCodec dequeueInputBuffer]
        V1 --> V2[queue VideoPacket]
        V2 --> V3[MediaCodec dequeueOutputBuffer]
    
        V3 --> V4[SurfaceTexture / OES External Texture]
        V4 --> V5[Texture Copier Shader]
        V5 --> V6[RGBA Texture]
    
        V6 --> V7[Apply Video Rotation Matrix<br/> sensor / display / metadata]
    ```
4. 视频双缓冲，GPU异步`NV21`转码    
    ```mermaid
    flowchart LR
        R0[Decoded RGBA Texture]
    
        R0 -->|Frame N| R1[draw_frame_rgba<br/>Render Thread]
        R0 -->|Frame N-1| R2[GPU NV21 Transcode]
    
        R2 --> P0[TripleBuffered PBO]
        P0 --> P1[glReadPixels Async]
        P1 --> P2[Map PBO]
        P2 --> P3[NV21 Output Buffer]
    ```
    ```mathematica
    Frame N:
      - RGBA → 屏幕（同步 draw）
    Frame N-1:
      - RGBA → NV21（GPU 异步，不阻塞渲染）
    ```
5. Render Thread (屏幕显示)
    ```mermaid
    flowchart TD
        S0[Render Thread Loop]
        S0 --> S1[wait for new RGBA frame]
        S1 --> S2[bind EGLSurface]
        S2 --> S3[draw_frame_rgba]
        S3 --> S4[eglSwapBuffers]
        S4 --> S0
    ```
6. Audio Thread (音频)
    ```mermaid
    flowchart TD
        A0[Audio Decode Thread]
        A0 --> A1[dequeue AudioPacket]
        A1 --> A2[MediaCodec / FFmpeg decode]
        A2 --> A3[AAudio / AudioTrack write]
    ```