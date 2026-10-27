# Camera2Magic Magisk Module  
  - [x] `c2m manager`，一个管理器，主要负责选择媒体和解码服务  
  - [x] 内置了一张保护图案，作为用户未选择媒体时的兜底  
  - [x] 一个开关，控制是否覆盖相机流
  - [x] 自定义图片（视频）  
  - [x] 一个新的应用图标，以区别LSP项目  
  - [x] 目前不支持 raw10 格式拍照替换，鉴于使用场景的可能性，以后也不会支持 ：）
  
**开发中， alpha-1 即将发布，加入tg频道了解开发进度，以及观看兼容性测试视频**  
![tg](qr_tmp_s.jpg)

## 设备兼容性测试  
| device | os | offline verification | real-device testing |
| --- | --- | --- | --- |
| oneplus 8t | Coloros 13/14 | pass | pass |
| oneplus 8t | LineageOS 23/24 | pass | pass |
| redmi note9 pro | LunarisAOSP (A16) | pass | not test yet |
| POCO F5 | Project Infinity X (A17) | pass | not test yet |

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
