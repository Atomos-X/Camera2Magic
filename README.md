# Camera2Magic Magisk Module  
  - [x] 测试设备为`pixel 3a` SDM670/4G ram  
  - [x] `c2m manager`，一个管理器，主要负责选择媒体和解码服务  
  - [x] 完全使用GPU解码、转换以提升性能  
  - [x] 限制了图片/视频的最大分辨率不超过4k，通过配置文件中的值调整，UI不提供任何选项  
  - [x] 内置了一张保护图案，作为用户未选择媒体时的兜底  
  - [x] 一个开关，控制是否覆盖相机流  
  - [x] 自定义图片（视频）  
  - [x] 一个新的应用图标，以区别LSP项目  
  - [x] 管理器添加了一个导出日志功能，汇报bug时附上日志  
  - [x] 目前不支持 raw10 格式拍照替换，鉴于使用场景的可能性，以后也不会支持 ：）  
  
**开发中，加入tg频道了解详情**  
![tg](qr_tmp_s.jpg)

## 设备兼容性    
  - **不再支持Android13-，以及32位设备**
  - **C2M管理器 能力检测显示NOT SUPPORTED，说明硬件驱动无法支持目前的方案**
  - **没有MTK调试机器，即使有报告称在某些MTK设备上工作，请默认模块不支持MTK**

## 应用兼容性测试 
  - [x] com.oplus.camera
  - [x] org.lineageos.aperture
  - [x] org.telegram.messenger
  - [x] com.android.chrome
  - [x] com.whatsapp
  - [x] com.instagram.android
  - [x] com.zhiliaoapp.musically  
  ……

## 关于LSPOSED版Camera2Magic
  - 不提供任何预编译文件的源码，原因见下  
  - 请看issues置顶，如果某些功能没有，那就是设计如此，请勿许愿  
  - 由于某些原因导致预期以外的工作，请勿当永久有效  
  - 利用本项目源码（二进制文件）二次开发/反编译：请更换包名，应用名，和图标  

**任何非本仓库(fork)提供的`Camera2Magic`均与本人无关，任何利用本人代码以及预编译文件进行二次修改的行为本人毫不知情！！**
