Camera2 Magic：一个虚拟摄像头软件？支持 android 10 +  
  
## 进度  
  
- hook camera1/2 api  
  - [x] 初步检测目标应用的工作模式(普通，扫码，人脸检测)
  - [x] 选择本地媒体视频解码  
    - [x] 视频解码
      - [x] 双缓冲 (Ping-Pong Mechanism)
      - [x] 使用GPU转码nv21  
      - [x] ~~cpu转码nv21~~ (低性能，不再需要)  
    - [x] 音频解码 初步的音频支持
  - [ ] 使用网络视频流  
  - [x] 替换预览画面  
    - [x] 修正`preview surface`绘制与`视觉宽高`保持一致  
    - [x] 裁切图像适配 `preview surface` ratio，尽可能不会拉伸变形  
    - [x] 适配目标应用实时切换 ratio    
  - [x] 生成 `nv21 byte[]`   
    - [x] nv21数据模拟camera数据源，控制面板看到nv21数据的预览图应该是横着  
    - [x] `camera1 api` 拍照 使用当前 nv21 bytes数据(默认)   
    - [x] 强制将 `nv21 data`转换为`视觉正向`（默认）
    - [ ] 使用指定图片替换拍照数据 ？ 
  - [ ] 已知问题：Stride!=Width情况下部分分辨率边缘出现绿线（例如：1080x1920），将在下一次更新修复  
  
  
- 向目标应用注入浮动窗口，用来调试控制(需要开启目标应用的悬浮窗功能)  
  - [x] 浮动窗口框架  
  - [x] 以低分辨率和帧率预览 nv21 bytes  
  - [ ] 完成其他功能菜单
  
- 模块自身UI  
  - [x] 主界面  
    - [x] 申请媒体权限
    - [x] 点击空白缩略图选择媒体文件/长按缩略图删除  
  - [x] 功能开关   
    - [x] 模块临时开关  
    - [x] 播放音频开关  
    - [x] 打印日志开关（错误日志依然会打印） 
    - [x] 注入浮动面板开关  
    - [x] ~~强制nv21数据竖屏~~ (不再需要)  
    
- 文档  
  - [ ] 详细的文档
    
    
**请不要用于非法用途**  
  
需要 root，lsposed，在lsposed manager中开启模块，勾选作用域，然后杀掉目标应用的进程，再重新打开。  
  
视频文件需要放在本地公共存储目录，大部分拥有相机功能的app都能访问。如： `DCIM`、`MOVIES`等。  
  
**视频**   
  
测试用机器: oneplus 8T (colorOS port 16.0, [刷机群tg](http://t.me/coloros_port_sm8250))  
  
https://github.com/user-attachments/assets/3ad33666-85a7-453d-a761-1f46ede016d9



