# Camera2 Magic：一个虚拟摄像头？支持 android 10 +   

[ README_EN.md ](README_EN.md)  ENGLISH Version translated by Google

## RC1已发布，使用前必读：
  - **暂时无法支持32位系统，(Android 32-bit)**
  - **手机需要 `Root`， 且安装的`LSPosed Manager`为较新的官方内测版本（Modern api only)**
  - **使用时注意选择的图片或者视频，宿主能否正常访问，否则模块会回滚到未hook状态**
  - **手机必须支持`AMediaCodec`硬解码，模块不再支持软解码兼容**
  - **极有可能在一些少见的分辨率下工作出现异常，`nv21格式` 画面出现垂直的红绿交错条纹；**
  - **请勿在需要高稳定性画面的场景下使用本模块，如被封号，盖不负责！**
  - **本模块只在JAVA端 Hook Camera api，如能正常工作，请不要用于非法用途**

![img](document/x.jpg) 

### hook camera1/2 api
  - [x] 使用本地视频 hook   
    - [x] 使用 ffmpeg demuxer，完成一些网络视频流支持的初期工作   
    - [x] AMediaCodec 视频硬解码（sm8250大致流畅 4k@60fps HEVC）  
      - [x] 双缓冲 (Ping-Pong Mechanism)  
      - [x] 使用GPU转码nv21  
    - [x] 音频解码 初步的音频支持 
  - [x] 使用静态图片 hook
  - [ ] 使用网络视频流 hook 
  - [x] 替换预览画面  
    - [x] 修正`preview surface`绘制与`视觉宽高`保持一致  
    - [x] 裁切图像适配 `preview surface` ratio，尽可能不会拉伸变形  
    - [x] 适配目标应用实时切换 ratio    
  - [x] 同步生成 `nv21 byte[]`   
    - [x] `camera1 api` 拍照 使用当前 nv21 bytes数据（默认）   

### 模块自身 UI
  - [x] 主界面
    - [x] 申请媒体权限
    - [x] 点击空白缩略图选择媒体文件/长按缩略图删除
  - [x] 功能开关
    - [x] 模块临时开关
    - [x] 播放音频开关
    - [x] 打印日志开关（错误日志依然会打印）

### 文档  
  - [ ] **现有架构有较大改动，文档稍后更新**



