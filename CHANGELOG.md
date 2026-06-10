# Changelog (forge-26.1)

本文档记录 Sus-InstantSwap 在 `forge-26.1` 分支上的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [2.0.1] - 2025-06-10

### Added
- 零帧鼠标重定位，消除交换时的光标闪烁
- 支持空槽位交换物品（Empty slot swap）
- EditBox 文本输入保护

### Changed
- 移除 TooltipSuppressMixin，不再压制物品提示框显示
- 清理 InstantSwapClient 代码冗余逻辑

### Fixed
- 修复自身槽位交换导致物品丢失的问题（self-swap）
- 使用 `instanceof PauseScreen` 替代 `isPauseScreen()`，允许关闭 Ponder 等模组暂停界面
- SWAP_KEY 现在模拟原版背包键行为（keyPressed(ESC) 关闭界面）

## [2.0.0] - 2025-06-03

### Added
- MC 26.1 完整功能对等：E 键交换、GUI 交换、长按模式、创造模式交换、鼠标重定位、tooltip 抑制、装备槽保护
- 分类翻译支持
- Mixin 按键拦截适配 MC 26.1

### Changed
- 统一所有平台的元数据/语言/配置文本，与 NeoForge 1.21.1 基线对齐

### Fixed
- 重新添加 `inMenuContext` 追踪
- 修复创造模式快捷栏客户端预测
- 添加 `emptySlotSwapEnabled` 配置开关

## [1.3.0] - 2025-05-25

### Added
- 空槽位交换功能
- EditBox 文本输入保护机制

### Changed
- SWAP_KEY 重构为模拟原版背包键行为

### Fixed
- 使用 `instanceof PauseScreen` 替代 `isPauseScreen()`，兼容 Ponder 等模组暂停界面

## [1.2.1] - 2025-05-20

### Fixed
- 修复按键无法关闭模组界面的问题
- 声明模组为纯客户端（`side=CLIENT` in `[[mods]]`）

## [1.2.0] - 2025-05-18

### Fixed
- 修复配置重置问题
- 更新元数据

## [1.1.0] - 2025-05-10

### Added
- 首次移植到 Forge 26.1.2，使用 ForgeGradle 7.0.17+
- 长短按模式：支持长按和短按触发不同行为
- 完整的配置系统
- 模组 LOGO 图标