# 阅读Max

基于 [legado-with-MD3](https://github.com/HapeLee/legado-with-MD3) 二次开发的安卓阅读器。

在保留原版全部功能的基础上，新增了对 **.nex 格式**的完整支持。

## 什么是 .nex

`.nex` 是一种自定义的电子书格式，本质是一个 ZIP 压缩包：

    mybook.nex
    ├── manifest.json      书名、作者
    ├── book.json          章节列表
    ├── content/
    │   ├── ch1.html       每章正文
    │   └── ch2.html
    ├── assets/
    │   ├── img/           图片
    │   └── video/         视频
    └── style/main.css     样式

正文用 HTML 写，图片视频按相对路径引用。结构清晰，方便程序解析和转换。

## 新增功能

### 1. 导入 .nex 书籍

书架的「添加本地」和「智能扫描」都支持 `.nex` 文件。

### 2. 转 .nex

书架右上角三点菜单 → **转 .nex**，支持把下面几种格式转成 `.nex`：

- **EPUB** —— 自动读取目录、抽取图片视频、跳过封面页/目录页
- **DOCX** —— 解析段落、标题、图片、视频
- **TXT** —— 自动探测章节（第X章、Chapter X、卷X 等）

转换出来的 `.nex` 会存到「设置 → 高级 → 书籍保存位置」指定的目录，并自动加入书架。

### 3. 视频播放

`.nex` 里带视频的章节，阅读页右上角会出现 ▶ 悬浮按钮，点击后用系统播放器播放。

### 4. 图片显示

`.nex` 里的图片在阅读页正常显示。

## 安装

1. 在 [Releases](../../releases) 页面下载最新的 APK
2. 如果提示签名冲突，先卸载旧版再安装

## 使用

跟原版一致，额外多两个操作：

- **导入 .nex**：书架 → 三点菜单 → 添加本地 → 选 `.nex` 文件
- **转 .nex**：书架 → 三点菜单 → 转 .nex → 选 EPUB/DOCX/TXT

## 已知限制

- 不支持音频（.nex 里也不会生成音频）
- PDF 暂不支持转换
- 视频是跳出去用系统播放器播，不内嵌在正文里

## 免责声明

本项目仅供学习交流。请勿用于传播盗版内容。所有书籍内容由使用者自行负责。

## 致谢

- [legado](https://github.com/gedoor/legado) 原作者 gedoor
- [legado-with-MD3](https://github.com/HapeLee/legado-with-MD3) 作者 HapeLee