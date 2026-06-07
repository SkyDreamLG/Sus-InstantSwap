# Changelog

本文档记录了 Su's Instant Swap 的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [2.1.0] - 2026-06-07

### 新增
- 背包模组按键自动识别与拦截（SophisticatedBackpacks、Traveler's Backpack），无需手动配置即可共存
- 运行时按键改键检测（checkForKeyRebind），自动刷新拦截目标键
- 游戏内浮窗提示系统（SwapToast），含冷却机制与多语言支持
- 独立的日志类（SwapLog），统一模组日志输出
- CI 自动构建与发布工作流

### 重构
- TooltipMixin 重命名为 TooltipSuppressMixin，语义更清晰
- InstantSwapClient 代码清理与整理
- ScreenKeyMixin 代码清理
- 日志系统重构，从分散的 LOGGER 调用迁移到 SwapLog

## [2.0.0] - 2025-12-27

### 新增
- 全程按键交换（E键），兼容原版物品栏键
- GUI 内交换支持多容器（箱子、工作台、熔炉等）
- 创造模式物品交换，含装备栏/副手支持
- 长按E键释放时自动关闭界面
- 空槽位交换开关（emptySlotSwapEnabled 配置项）
- pack.mcmeta 资源包元数据文件
- 全按键配置屏幕布局，匹配 1.20.1 风格
- MC 1.21 - 1.21.4 全平台功能对等（NeoForge / Forge / Fabric）

### 修复
- 生存模式下阻止盔甲/副手交换（与 NF26.1 对齐）
- 非原版容器（Curios 等）2 tick 关闭延迟，防止虚假交换
- 交换前检查 Slot.mayPlace，避免 Curios 槽位异常音效/关闭
- 创造模式装备槽位 mayPlace 校验
- GUI 交换时机优化，通过 ScreenKeyMixin 处理
- 创造模式装备检测使用 swTarget.index 作为真实 csi
- SlotWrapper 分支覆盖背包槽位 (9-35)
- 长按E键无交换时自动关闭屏幕
- 滑块背景使用 fill 替代 blitNineSliced，渲染更可靠
- EditBox 鼠标光标重定位保护
- ScreenKeyMixin 直接关闭，移除 closeOnRelease
- 所有平台元数据/语言/配置文本统一
- 版本范围修正为 [1.21,1.22)

### 重构
- 移除自定义交换按键，简化按键检测
- 新增 isInventoryOpen 守卫
- 移除死代码，简化比较逻辑，修正注释

## [1.3.0] - 2025-09-21

### 新增
- 空槽位交换支持
- EditBox 文本输入保护（输入时不触发交换）

### 修复
- 使用 `instanceof PauseScreen` 替代 `isPauseScreen()`，允许关闭 Ponder/Patchouli 等模组暂停界面

### 重构
- 代码清理与简化

## [1.2.1] - 2025-09-08

### 修复
- 修复交换键无法关闭模组界面的问题

## [1.2.0] - 2025-09-04

### 新增
- GUI 界面内物品交换功能
- Tooltip 抑制 Mixin（交换时隐藏提示框）
- 创造模式交换客户端预测
- 统一多语言翻译

### 修复
- 修复工作台合成网格关闭问题
- guiSwapEnabled 默认值改为 false

### 变更
- 模组版本号升级至 1.2.0

## [1.1.1] - 2025-08-24

### 修复
- 修复按键改键问题
- 移除 Cloth Config 依赖（Fabric 平台）
- Fabric 1.20.1: Cloth Config + ModMenu 游戏内配置全面修复

## [1.1.0] - 2025-08-18

### 新增
- 长短按模式（长按 E 键触发交换，短按正常开关背包）
- 配置系统（游戏内图形界面配置）
- 模组 LOGO
- 外部扩展接口（初步可扩展架构）
- 多平台支持：NeoForge 1.21、Forge 1.20.1/1.21.1/26.1、Fabric 1.20.1/1.21/26.1
- MC 26.1 版本迁移支持

### 变更
- 配置文件结构精简
- 调试日志移至底部

## [1.0.3] - 2025-08-01

### 变更
- Monorepo 结构：NeoForge 1.21 + Fabric 1.21 + Fabric 1.20.1
- JAR 命名规范化为统一格式：`Sus_InstantSwap-version-LoaderMC.jar`
- README 新增中文翻译

## [1.0.2] - 2025-07-30

### 修复
- 模组声明为纯客户端 (`dist=CLIENT`)

## [1.0.1] - 2025-07-28

### 修复
- 修复可移植性问题

## [1.0.0] - 2025-07-26

### 新增
- Su's Instant Swap 初始发布
- 基础物品快捷交换功能