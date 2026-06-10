# Changelog (fabric-1.20.1)

本文档记录 Sus-InstantSwap 在 `fabric-1.20.1` 分支上的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [2.0.1] - 2025-06-10

### Added
- 零帧鼠标重定位（通过 Accessor 接口实现），消除交换时的光标闪烁
- 支持与空槽位交换物品（Empty slot swap）
- EditBox 文本输入保护，输入时不会误触发交换

### Changed
- 移除 TooltipSuppressMixin，不再压制物品提示框显示
- 重构 InstantSwapClient 代码，清理冗余逻辑

### Fixed
- 修复自身槽位交换导致物品丢失的问题（self-swap）
- 使用 `instanceof PauseScreen` 替代 `isPauseScreen()`，允许关闭 Ponder 等模组暂停界面
- SWAP_KEY 现在模拟原版背包键行为（keyPressed(ESC) 关闭界面）

## [2.0.0] - 2025-06-03

### Added
- 滑块配置控件：支持在滑块内显示文本标签

### Changed
- 统一所有平台的元数据/语言/配置文本，与 NeoForge 1.21.1 基线对齐
- 滑块背景渲染改用 `fill` 替代 `blitNineSliced`，确保渲染可靠性
- 阈值按钮样式替换

### Fixed
- 修复 `pack.mcmeta` 文件内容错误
- 修复 `build.gradle` 中模组命名格式
- 修复 `positionCursorIfEnabled` 在 EditBox 获得焦点时的行为
- 仅在界面初始化时进行鼠标重定位，不再在 tick 循环中执行

## [1.3.0] - 2025-05-25

### Added
- 空槽位交换功能
- EditBox 文本输入保护机制

### Changed
- 整体代码清理与优化
- SWAP_KEY 重构为模拟原版背包键行为

### Fixed
- 使用 `instanceof PauseScreen` 替代 `isPauseScreen()`，兼容 Ponder 等模组暂停界面
- 简化 `simulateVanillaInventoryKey` 实现
- 改用 `keyPressed(ESC)` 方式关闭界面
- 使用 `removed()` 方式实现 `simulateVanillaInventoryKey`

## [1.2.1] - 2025-05-20

### Fixed
- 修复按键无法关闭模组界面的问题

### Changed
- 更新 Fabric 1.20.1 描述与元数据

## [1.2.0] - 2025-05-18

### Fixed
- 修复合成网格界面关闭问题
- 统一多语言翻译文本

## [1.1.1] - 2025-05-15

### Added
- Cloth Config + ModMenu 游戏内配置支持

### Changed
- 全面功能修复
- 移除 Cloth Config 依赖（改用内置配置系统）
- 按键绑定修复

## [1.1.0] - 2025-05-10

### Added
- 从 v1.0.3 源代码升级至独立 Fabric 1.20.1 分支
- 阿里云镜像加速 LWJGL 3.3.1 下载
- 长短按模式：支持长按和短按触发不同行为
- 完整的配置系统
- 模组 LOGO 图标

### Changed
- Fabric 1.20.1 版本独立为单独分支