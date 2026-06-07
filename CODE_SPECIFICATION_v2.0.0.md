# Sus-InstantSwap v2.0.0 — 代码级移植说明书

> **基线**: NeoForge 1.21.1 · `neoforge-1.21.1` 分支（工作区最新代码）· 2026-06-01
>
> 本文档对 `Sus_InstantSwap_NF1.21.1` 项目进行**逐文件、逐方法、逐行代码**的详细解析，
> 并以移植到 Forge / Fabric 为目标，标注每处代码的平台依赖关系和移植要点。

---

## 零、功能清单

Sus-InstantSwap v2.0.0 实现以下完整功能：

### 核心交换功能

| # | 功能 | 说明 | 触发方式 |
|---|------|------|---------|
| F1 | **长按交换** | 按住物品栏键(E)超过阈值（默认200ms）后松手，将鼠标悬停的物品与当前热键栏选中槽位交换 | 长按E→松手 |
| F2 | **短按共存** | 短按E键（未达阈值即松手）正常打开/关闭背包，完全符合原版行为 | 短按E（≤200ms） |
| F3 | **创造模式交换** | 在创造模式物品栏中支持4种交换场景 | 同F1 |

### 创造模式四个交换分支

| 分支 | 场景 | csi范围 | 机制 |
|------|------|---------|------|
| CONTAINER | 创造标签页物品格 | — | `handleCreativeModeItemAdd` 直接操作客户端+服务端 |
| CREATIVE_EQUIP | 物品栏页的护甲/副手槽 | 5-8 (护甲), 45 (副手) | `handleInventoryMouseClick` + SWAP + 护甲类型校验 |
| SlotWrapper | 创造标签页的热键栏槽位 | 36-44 (w.target.index) | 双向 `safeSet` + `handleCreativeModeItemAdd` |
| REGULAR | 普通热键栏槽位(兜底) | 0-8 | 双向 `safeSet` + `handleCreativeModeItemAdd` |

**⚠️ 创造模式特殊处理**：
- `cs.isInventoryOpen()` 区分物品栏页(true)和标签页(false)，防止标签页快捷栏被误判为装备槽
- `handleCreativeModeItemAdd` 的 slotIdx 参数必须是**菜单索引(36+)**，不能是物品栏索引(0-35)
- `EquipmentSlot` 枚举必须用常量(`HEAD/CHEST/LEGS/FEET`)，不能用 `values()[n]`

### 交互增强功能

| # | 功能 | 配置项 | 默认值 | 说明 |
|---|------|--------|--------|------|
| F4 | **鼠标重定位** | `mouseReposition` | ✅ true | 右键打开容器时，鼠标自动移到界面右下角；物品栏界面不触发 |
| F5 | **右键交互追踪** | — | — | 通过 `PlayerInteractEvent` 区分右键打开容器和热键打开，仅右键触发的容器执行鼠标重定位 |
| F6 | **交换音效** | `soundEnabled` | ✅ true | 交换成功时播放 `ITEM_PICKUP` 音效（音量0.8） |
| F7 | **tooltip抑制** | — | — | 鼠标重定位后抑制2帧tooltip，防止意外弹出 |

### GUI交换功能

| # | 功能 | 配置项 | 默认值 | 说明 |
|---|------|--------|--------|------|
| F8 | **GUI界面中交换** | `guiSwapEnabled` | ❌ false | 在容器界面中，直接按交换键执行交换并关闭界面 |
| F9 | **双键位支持** | — | — | `SWAP_IN_GUI_KEY` 未绑定时用E键触发；绑定后用专属键触发 |
| F10 | **空位交换** | `emptySlotSwapEnabled` | ❌ false | 悬停空槽位时也可执行交换（将手持物品放入空位） |

### 兼容与保护

| # | 功能 | 说明 |
|---|------|------|
| F11 | **Curios槽位类型验证** | 通过 `Slot.mayPlace()` 客户端预检，防止戒指槽接受非戒指物品的"假交换" |
| F12 | **模组容器2-tick延迟关闭** | 非原版容器(Curios等)延迟2 tick关闭，防止第三方模组同步延迟导致数据不一致 |
| F13 | **EditBox文本保护** | 输入框有焦点时消费E键click事件，防止打字时意外关闭界面 |
| F14 | **双Mixins防护** | `KeyClickMixin` + `ScreenKeyMixin` 同时拦截E键，防止长按期间界面闪烁 |
| F15 | **延迟关闭机制** | 所有关闭统一走 `closePendingTicks` 倒计时(0=idle, 1=原版, 2=模组容器)，避免直接 `closeContainer()` 的时序问题 |

### 配置与调试

| # | 功能 | 配置项 | 默认值 | 说明 |
|---|------|--------|--------|------|
| F16 | **模组总开关** | `modEnabled` | ✅ true | 关闭后E键完全恢复原版行为 |
| F17 | **长按阈值可调** | `holdThresholdMs` | 200ms | 范围 50-1000ms，滑块调节 |
| F18 | **内置配置界面** | — | — | NeoForge `ConfigurationScreen` / Forge `ForgeConfigScreen` |
| F19 | **中英文双语** | — | — | 所有配置项和tooltip都有中英文翻译 |
| F20 | **调试日志** | `debug` | ❌ false | 输出详细的交换过程日志到游戏Log |

### 状态机架构

```
玩家按下 E 键
  │
  ├─→ KeyClickMixin.onClick()
  │     ├─ 已按住 → ci.cancel() [阻止原版关闭]
  │     └─ 首次按下 → inventoryKeyHeld=true, pressStartNanos=now
  │
  ├─→ ScreenKeyMixin.onKeyPressed()
  │     ├─ inventoryKeyHeld=true → return false [阻塞关闭]
  │     └─ inventoryKeyHeld=false → tryPerformGuiSwap() 或 放行
  │
  └─→ onClientTick (每帧心跳)
        │
        ├─ closePendingTicks > 0 → 倒计时 → 归零时 closeContainer()
        │
        ├─ IDLE: E键按住 + 屏幕是容器 → WATCHING
        │
        ├─ WATCHING:
        │     ├─ 松手/屏幕关 → IDLE (短按)
        │     └─ ≥holdThresholdMs → LONG_PRESS
        │
        └─ LONG_PRESS:
              └─ 松手 → performSwap() → 延迟关闭
```

---

## 目录

1. [一、项目总览](#一项目总览)
2. [二、文件清单与依赖关系](#二文件清单与依赖关系)
3. [三、susinstantswap.mixins.json — Mixin 配置](#三susinstantswapmixinsjson--mixin-配置)
4. [四、accesstransformer.cfg — 访问转换器](#四accesstransformercfg--访问转换器)
5. [五、neoforge.mods.toml — Mod 元数据](#五neoforgemodstoml--mod-元数据)
6. [六、SwapConfig.java — 配置定义](#六swapconfigjava--配置定义)
7. [七、SwapKeyState.java — 共享状态](#七swapkeystatejava--共享状态)
8. [八、KeyClickMixin.java — 按键拦截 Mixin](#八keyclickmixinjava--按键拦截-mixin)
9. [九、ScreenKeyMixin.java — GUI 按键拦截 Mixin](#九screenkeymixinjava--gui-按键拦截-mixin)
10. [十、InstantSwapClient.java — 核心逻辑](#十instantswapclientjava--核心逻辑)
    - [10.1 字段与初始化](#101-字段与初始化)
    - [10.2 onClientTick — 主状态机](#102-onclienttick--主状态机)
    - [10.3 onKeyInput — 按键事件处理](#103-onkeyinput--按键事件处理)
    - [10.4 performSwap — 交换分发核心](#104-performswap--交换分发核心)
    - [10.5 creativeSwap — 创造模式交换 (重点)](#105-creativeswap--创造模式交换-重点)
    - [10.6 按键检测方法组](#106-按键检测方法组)
    - [10.7 辅助方法组](#107-辅助方法组)
11. [十一、SusInstantSwapMod.java — Mod 入口](#十一susinstantswapmodjava--mod-入口)
12. [十二、语言文件](#十二语言文件)
13. [十三、平台差异完整对照表](#十三平台差异完整对照表)
14. [十四、移植步骤指南](#十四移植步骤指南)
15. [十五、关键陷阱汇总](#十五关键陷阱汇总)

---

## 一、项目总览

| 属性 | 值 |
|------|-----|
| ModId | `susinstantswap` |
| 版本 | 2.0.0 |
| MC | 1.21.1 |
| 模组加载器 | NeoForge 21.1.230 |
| ModDevGradle | 1.0.0 |
| Java | 21 |
| License | LGPL-3.0 |
| Side | **CLIENT only** |
| 源文件 | 6 个 Java 文件 (~460 行) |
| Mixin | 2 个，仅客户端 |
| Access Transformer | 1 个文件 |

**核心功能**：按住物品栏键(E)将悬停物品与快捷栏交换。与原版共存——短按打开背包，长按执行交换。

**设计理念**：不注册独立热键，通过 Mixin 拦截原版 `keyInventory`，实现"短按→原版开背包 / 长按→模组交换"的共存逻辑。

---

## 二、文件清单与依赖关系

```
src/main/
├── java/com/susinstantswap/
│   ├── SusInstantSwapMod.java          [① Mod入口：初始化配置+客户端]
│   ├── client/
│   │   ├── InstantSwapClient.java      [② 核心逻辑：状态机+交换+UI]
│   │   └── SwapKeyState.java           [③ volatile 共享状态字段]
│   ├── config/
│   │   └── SwapConfig.java             [④ NeoForge ModConfigSpec 配置]
│   └── mixin/
│       ├── KeyClickMixin.java          [⑤ 注入 KeyMapping.click/set]
│       └── ScreenKeyMixin.java         [⑥ 注入 Screen.keyPressed]
│
└── resources/
    ├── susinstantswap.mixins.json       [Mixin 声明]
    ├── META-INF/
    │   ├── neoforge.mods.toml           [Mod 元数据]
    │   └── accesstransformer.cfg        [AT 开放内部类]
    └── assets/susinstantswap/lang/
        ├── en_us.json                   [英文翻译]
        └── zh_cn.json                   [中文翻译]
```

**调用关系图**：

```
SusInstantSwapMod (构造器)
  ├─ new SwapConfig(builder) → CONFIG
  ├─ modContainer.registerConfig(CLIENT, CONFIG_SPEC)
  ├─ modContainer.registerExtensionPoint(IConfigScreenFactory)
  ├─ modEventBus.register(this)
  └─ InstantSwapClient.init(CONFIG)
        ├─ NeoForge.EVENT_BUS.register(InstantSwapClient.class)
        └─ 创建 SWAP_IN_GUI_KEY

SusInstantSwapMod.onRegisterKeyMappings()
  └─ InstantSwapClient.registerKey(event)

InstantSwapClient (static, 全事件驱动)
  ├─ onClientTick(ClientTickEvent.Post)  ← 每帧心跳：状态机 + 延迟关闭
  ├─ onKeyInput(InputEvent.Key)          ← 按键：GUI交换 + 文本保护
  ├─ onRightClickBlock/Entity            ← 交互追踪：区分右键开容器
  ├─ onScreenInitPost(ScreenEvent.Init.Post) ← 屏幕打开：鼠标重定位
  ├─ onRenderTooltip(RenderTooltipEvent.Pre) ← 抑制tooltip闪烁
  ├─ tryPerformGuiSwap()                 ← ScreenKeyMixin 入口
  │
  ├─ performSwap()                       ← 交换分发枢纽
  │   ├─ creativeSwap()                  ← 创造模式四分支
  │   └─ containerSwap()                 ← 生存模式 SWAP 发包
  │
  └─ Key Detection (isSwapKey, isInventoryKeyPhysicallyDown, ...)
```

---

## 三、susinstantswap.mixins.json — Mixin 配置

```json
{
    "required": true, "minVersion": "0.8",
    "package": "com.susinstantswap.mixin",
    "compatibilityLevel": "JAVA_21",
    "mixins": [],
    "client": ["KeyClickMixin", "ScreenKeyMixin"],
    "injectors": { "defaultRequire": 1 }
}
```

| 字段 | 值 | 说明 |
|------|-----|------|
| `package` | `com.susinstantswap.mixin` | 与 Java 包路径一致 |
| `compatibilityLevel` | `JAVA_21` | 编译目标 Java 版本 |
| `mixins` | `[]` | 空：纯客户端 Mod |
| `client` | `["KeyClickMixin","ScreenKeyMixin"]` | 两个客户端 Mixin |

> **[移植]**：Forge 完全相同。Fabric 也支持此格式。1.20.1 改为 `JAVA_17`。

---

## 四、accesstransformer.cfg — 访问转换器

```properties
public net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen CONTAINER
public-f net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper
public net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper target
public net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ClickType;)V
```

逐行说明：

| AT 条目 | 用途 | 代码引用位置 |
|---------|------|-------------|
| `CONTAINER` → public | 判断悬停槽是否在创造标签页物品格 | `creativeSwap()`：`hs.container == CreativeModeInventoryScreen.CONTAINER` |
| `SlotWrapper` → public-f | 使内部类可 `instanceof` 判断 | `creativeSwap()`：`hs instanceof CreativeModeInventoryScreen.SlotWrapper w` |
| `SlotWrapper.target` → public | 读取 `w.target`（原始 Slot）| `creativeSwap()` SlotWrapper 分支：`w.target.index` |
| `slotClicked()` → public | 调用创造模式槽位点击处理 | **当前代码未实际使用** |

> **[移植]**：
> - **Forge 1.21.1/1.20.1**：AT 语法完全相同，直接复制
> - **Fabric**：无 AT，需要反射替代：
>   - `CONTAINER`：用 `hs.container instanceof SimpleContainer` 类型判断
>   - `SlotWrapper`：用 `hs.getClass().getSimpleName().equals("SlotWrapper")`
>   - `w.target`：用 `getDeclaredField("target")` 反射（Loom 会自动 remap 字段名字符串 → ✅ 安全）
>   - 内部类名 `SlotWrapper` 不会被 Loom remap，不能用于 `Class.forName("...$SlotWrapper")` ❌

---

## 五、neoforge.mods.toml — Mod 元数据

```toml
modLoader="javafml"
loaderVersion="[1,)"
license="LGPL-3.0"

[[mods]]
modId="susinstantswap"
version="2.0.0"
displayName="Su's Instant Swap"
logoFile="assets/susinstantswap/icon.png"
authors="Su_Crispy"
description="Hold the inventory key to swap items with your hotbar. Coexists with vanilla."

[[mixins]]
config="susinstantswap.mixins.json"

[[dependencies.susinstantswap]]
modId="neoforge"
type="required"
versionRange="[21.0,21.2)"
ordering="NONE"
side="CLIENT"

[[dependencies.susinstantswap]]
modId="minecraft"
type="required"
versionRange="[1.21,1.21.2)"
ordering="NONE"
side="CLIENT"
```

> **[移植]**：
> - **Forge**：`mods.toml` 格式相同，依赖 `modId="forge"`
> - **Fabric**：完全不同的 `fabric.mod.json`，`environment: "client"`

---

## 六、SwapConfig.java — 配置定义

```java
package com.susinstantswap.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class SwapConfig {
    public final ModConfigSpec.BooleanValue modEnabled;
    public final ModConfigSpec.IntValue holdThresholdMs;
    public final ModConfigSpec.BooleanValue soundEnabled;
    public final ModConfigSpec.BooleanValue mouseReposition;
    public final ModConfigSpec.BooleanValue guiSwapEnabled;
    public final ModConfigSpec.BooleanValue emptySlotSwapEnabled;
    public final ModConfigSpec.BooleanValue debug;

    public SwapConfig(ModConfigSpec.Builder builder) {
        builder.comment("Su's Instant Swap Configuration", "", "Changes take effect immediately.");
        modEnabled = builder.translation("config.susinstantswap.modEnabled")
                .comment("Master switch — disable to turn off all mod functionality")
                .define("modEnabled", true);
        holdThresholdMs = builder.translation("config.susinstantswap.holdThresholdMs")
                .comment("Long Press Threshold (ms). Range: 50~1000")
                .defineInRange("holdThresholdMs", 200, 50, 1000);
        soundEnabled = builder.translation("config.susinstantswap.soundEnabled")
                .comment("Swap Sound").define("soundEnabled", true);
        mouseReposition = builder.translation("config.susinstantswap.mouseReposition")
                .comment("Open container or inventory, the mouse will automatically move to the bottom-right corner")
                .define("mouseReposition", true);
        guiSwapEnabled = builder.translation("config.susinstantswap.guiSwapEnabled")
                .comment("In container screens, press the GUI swap key to directly swap items and close the screen")
                .define("guiSwapEnabled", false);
        emptySlotSwapEnabled = builder.translation("config.susinstantswap.emptySlotSwapEnabled")
                .comment("Also swap empty slots").define("emptySlotSwapEnabled", false);
        debug = builder.translation("config.susinstantswap.debug")
                .comment("Debug Logging").define("debug", false);
    }
}
```

**配置项全表**：

| 字段 | 类型 | 默认值 | 范围 | TOML Key |
|------|------|--------|------|----------|
| `modEnabled` | `BooleanValue` | `true` | — | `modEnabled` |
| `holdThresholdMs` | `IntValue` | `200` | 50-1000 | `holdThresholdMs` |
| `soundEnabled` | `BooleanValue` | `true` | — | `soundEnabled` |
| `mouseReposition` | `BooleanValue` | `true` | — | `mouseReposition` |
| `guiSwapEnabled` | `BooleanValue` | `false` | — | `guiSwapEnabled` |
| `emptySlotSwapEnabled` | `BooleanValue` | `false` | — | `emptySlotSwapEnabled` |
| `debug` | `BooleanValue` | `false` | — | `debug` |

**每个配置项都有 `translation()` 指向 lang 文件，实现中英文双语。**

> **[移植]**：
> - **Forge**：`ForgeConfigSpec`（包名 `net.minecraftforge.common.ForgeConfigSpec`），API 几乎相同。⚠️ 使用双层配置需遵循正确的同步模式
> - **Fabric**：无 `ModConfigSpec`，需改为 Gson JSON 配置 POJO

---

## 七、SwapKeyState.java — 共享状态

```java
package com.susinstantswap.client;

/**
 * Shared state between mixins and InstantSwapClient.
 */
public final class SwapKeyState {
    public static volatile boolean inventoryKeyHeld = false;
    public static volatile long pressStartNanos = 0;
    public static volatile boolean longPressConfirmed = false;
    /** Swap completed — countdown to close (0=idle, 1=vanilla/creative, 2=mod containers to prevent fake swap). */
    public static volatile int closePendingTicks = 0;
    /** Master switch — when false, the entire mod is disabled. Synced from config. */
    public static volatile boolean modEnabled = true;
    private SwapKeyState() {}
}
```

**字段解读**：

| 字段 | 类型 | 初始 | 写入者 | 读取者 | 语义 |
|------|------|------|--------|--------|------|
| `inventoryKeyHeld` | `boolean` | `false` | `KeyClickMixin` | `onClientTick`, `ScreenKeyMixin` | E 键当前是否按住 |
| `pressStartNanos` | `long` | `0` | `KeyClickMixin`, `onClientTick`(IDLE) | `onClientTick`(WATCHING) | 按下时刻(纳秒) |
| `longPressConfirmed` | `boolean` | `false` | `onClientTick`(WATCHING) | `onClientTick`(IDLE) | 长按阈值是否已达成 |
| `closePendingTicks` | `int` | `0` | `performSwap`/`creativeSwap`/LONG_PRESS 兜底 | `onClientTick` | 延迟关闭倒计时：0=idle, 1=原版, 2=模组容器 |
| `modEnabled` | `boolean` | `true` | `onClientTick` (每帧从 config 同步) | `KeyClickMixin`, `ScreenKeyMixin` | 总开关 |

**设计关键**：
- 全部 `volatile`：Mixin 回调可能在不同线程触发
- `closePendingTicks` 语义：`0`=空闲, `1`=原版物品栏(1 tick 后关闭), `2`=模组容器(2 tick 后关闭，防 Curios 等第三方模组同步延迟)
- 私有构造器：纯静态工具类，防止实例化

> **[移植]**：**所有平台通用**，无需任何修改。不依赖任何 Mod 加载器 API。

---

## 八、KeyClickMixin.java — 按键拦截 Mixin

```java
package com.susinstantswap.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.client.SwapKeyState;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KeyMapping.class, remap = false)
public abstract class KeyClickMixin {

    /** Intercept KeyMapping.click() — fires when Minecraft detects a new key press. */
    @Inject(method = "click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V",
            at = @At("HEAD"), cancellable = true)
    private static void onClick(InputConstants.Key key, CallbackInfo ci) {
        if (!SwapKeyState.modEnabled) return;              // [1] 总开关
        if (!InstantSwapClient.isSwapKey(key)) return;      // [2] 匹配 E 键
        if (SwapKeyState.inventoryKeyHeld) {                // [3] 已按住 → 拦截重复 click
            ci.cancel();
            return;
        }
        SwapKeyState.inventoryKeyHeld = true;               // [4] 首次按下
        SwapKeyState.pressStartNanos = System.nanoTime();
        SwapKeyState.longPressConfirmed = false;
    }

    /** Intercept KeyMapping.set() — fires on press AND release. */
    @Inject(method = "set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V",
            at = @At("HEAD"))
    private static void onSet(InputConstants.Key key, boolean pressed, CallbackInfo ci) {
        if (!SwapKeyState.modEnabled) return;               // [1]
        if (pressed || !InstantSwapClient.isSwapKey(key)) return; // [2] 仅处理释放
        SwapKeyState.inventoryKeyHeld = false;              // [3] 重置状态
        SwapKeyState.longPressConfirmed = false;
    }
}
```

**两个注入点**：

| 注入方法 | 触发时机 | 作用 |
|----------|---------|------|
| `KeyMapping.click()` | 按键首次按下 & 每帧重复 | **按下**：首次记录状态；**重复**：`ci.cancel()` 阻止原版关闭 |
| `KeyMapping.set(_, false)` | 按键释放 | 重置 `inventoryKeyHeld` 和 `longPressConfirmed` |

**为什么拦截 `click()` 而不是用 `isDown()`？** `KeyMapping.click()` 是原版处理"按下→开/关物品栏"的入口。通过在此拦截，可以区分短按（放行）和长按（先持续取消，松手后由状态机执行交换）。

> **[移植]**：Forge 完全兼容。Fabric 的 Mixin 语法相同。`remap = false` 意味着方法签名不会被 Loom remap，需确认目标版本的方法名一致（`click`/`set` 在 Mojang 和 Yarn 下可能不同，但 `remap=false` 使用原始名，Fabric 的 Loom 会对 Mojang 映射代码中的方法引用自动 remap → ✅）。

---

## 九、ScreenKeyMixin.java — GUI 按键拦截 Mixin

```java
package com.susinstantswap.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.client.SwapKeyState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts Screen.keyPressed() for the inventory key on any
 * AbstractContainerScreen.
 *
 * Two scenarios:
 *   - REPEAT (inventoryKeyHeld already true): Block close to prevent flicker
 *     during long press. The screen stays open while the key is held.
 *   - FRESH press (inventoryKeyHeld false): Try GUI swap first.
 *     If not, let vanilla handle normally (E closes the screen).
 */
@Mixin(value = AbstractContainerScreen.class, remap = false)
public class ScreenKeyMixin {

    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers,
                              CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;                            // [1]
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;

        InputConstants.Key pressed = InputConstants.getKey(keyCode, scanCode);
        if (!pressed.equals(mc.options.keyInventory.getKey())) return;   // [2] 仅 E 键

        if (SwapKeyState.inventoryKeyHeld) {                             // [3] REPEAT → block
            cir.setReturnValue(false);
            return;
        }

        // [4] Fresh press → try GUI swap, else let vanilla close
        if (InstantSwapClient.tryPerformGuiSwap(
                (AbstractContainerScreen<?>) (Object) this)) {
            cir.setReturnValue(true);  // consumed
        }
    }
}
```

**与 KeyClickMixin 的"双保险"关系**：

```
E键按下
  ├─→ KeyClickMixin.onClick()  → inventoryKeyHeld=true, 首次记录
  └─→ ScreenKeyMixin.onKeyPressed()
        ├─ inventoryKeyHeld=true  → cir.setReturnValue(false) [阻止关闭]
        └─ inventoryKeyHeld=false → tryPerformGuiSwap() 或 放行
```

两个 Mixin 同时拦截 E 键——KeyClickMixin 在更底层（`KeyMapping.click()`），ScreenKeyMixin 在屏幕层（`Screen.keyPressed()`），确保长按期间**无论如何都不会闪烁关闭**。

> **[移植]**：Forge 完全兼容。Fabric 需确认 `keyPressed(III)Z` 在目标映射下的方法名。

---

## 十、InstantSwapClient.java — 核心逻辑

项目核心文件，包含所有业务逻辑。

---

### 10.1 字段与初始化

```java
public class InstantSwapClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_IN_GUI_KEY;       // 界面内交换键（默认未绑定）
    private static SwapConfig config;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged = false;
    private static boolean suppressNextTooltip;
    private static int suppressTooltipFrames;
    private static boolean screenOpenedByInteract = false;  // 右键交互标志
```

**状态机三态**：

```
IDLE ──(E键按+屏幕开)──→ WATCHING ──(≥200ms)──→ LONG_PRESS ──(松E)──→ IDLE + swap
  ↑                       │                        │
  └──(松手/屏幕关)─────────┘                        └──(屏幕关)──→ IDLE
```

### init()

```java
public static void init(SwapConfig cfg) {
    config = cfg;
    SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
            "key.categories.susinstantswap");
    NeoForge.EVENT_BUS.register(InstantSwapClient.class);  // ⚠️ NeoForge 特有
}
```

> **[移植]**：
> - **Forge**：`MinecraftForge.EVENT_BUS.register(InstantSwapClient.class)` 或 `@Mod.EventBusSubscriber`
> - **Fabric**：`ClientTickEvents.END_CLIENT_TICK.register(InstantSwapClient::onClientTick)` 等独立注册

### registerKey()

```java
public static void registerKey(RegisterKeyMappingsEvent event) {
    event.register(SWAP_IN_GUI_KEY);
}
```

---

### 10.2 onClientTick — 主状态机

每帧执行，是模组的心跳。

```
tooltip 帧计数
    ↓
首次配置日志
    ↓
modEnabled 同步 → false 则 return
    ↓
player/gameMode 空值防护
    ↓
【E】延迟关闭倒计时 (closePendingTicks)
    ↓
【F】IDLE → WATCHING: E键按 + 容器界面 → 记录开始时间
    ↓
【G】WATCHING: 屏幕关 → IDLE | 松E → IDLE | ≥阈值 → LONG_PRESS
    ↓
【H】LONG_PRESS: 松E → performSwap() → IDLE
```

#### [E] 延迟关闭机制 ⚠️ 核心设计

```java
if (SwapKeyState.closePendingTicks > 0) {
    SwapKeyState.closePendingTicks--;
    if (SwapKeyState.closePendingTicks == 0) {
        if (mc.screen instanceof AbstractContainerScreen) {
            mc.player.closeContainer();  // 唯一直接关闭点
        }
    }
    state = SwapState.IDLE;
}
```

**为什么延迟关闭？** 交换时发送 `ServerboundContainerClickPacket` 给服务端，服务端处理需要时间。立即关闭会导致客户端/服务端数据不一致。

**设计决策**：
- `closePendingTicks = 1`：原版 Inventory/Creative — 1 tick (~50ms)
- `closePendingTicks = 2`：模组容器（Curios 等）— 2 tick (~100ms)，防止第三方模组同步延迟导致的"假交换"
- 所有关闭操作统一走这个倒计时，**不使用直接 `closeContainer()`**（除了此处倒计时归零时）

#### [F] IDLE → WATCHING

```java
if (state == SwapState.IDLE) {
    if (SwapKeyState.inventoryKeyHeld && !SwapKeyState.longPressConfirmed) {
        if (mc.screen instanceof AbstractContainerScreen) {
            SwapKeyState.pressStartNanos = System.nanoTime();
            positionCursorIfEnabled(mc, mc.screen);
            state = SwapState.WATCHING;
        }
    }
    return;
}
```

触发条件：E 键已按下 + 长按未确认 + 屏幕是容器界面（`AbstractContainerScreen`）。

#### [G] WATCHING → 超时检测

```java
if (state == SwapState.WATCHING) {
    if (mc.screen == null) { state = SwapState.IDLE; return; }           // 屏幕关闭
    if (!isInventoryKeyPhysicallyDown(mc)) { state = SwapState.IDLE; return; } // 松手
    if ((System.nanoTime() - SwapKeyState.pressStartNanos)
            >= config.holdThresholdMs.get() * 1_000_000L) {              // 达到阈值
        SwapKeyState.longPressConfirmed = true;
        state = SwapState.LONG_PRESS;
    }
    return;
}
```

三个退出条件：屏幕关、松手(短按)、达到阈值(长按确认)。

#### [H] LONG_PRESS → 松手执行 ⚠️ 反复修改区域

```java
if (state == SwapState.LONG_PRESS) {
    if (mc.screen == null) { state = SwapState.IDLE; return; }
    if (!isInventoryKeyPhysicallyDown(mc) || !SwapKeyState.inventoryKeyHeld) {
        boolean swapped = performSwap(mc);
        if (!swapped) {
            // 交换失败也要关闭——防止界面卡住
            int closeDelay = (mc.screen instanceof AbstractContainerScreen<?> s
                    && isVanillaInventory(s)) ? 1 : 2;
            SwapKeyState.closePendingTicks = closeDelay;
        }
        state = SwapState.IDLE;
    }
}
```

**关键**：交换成功 `performSwap()` 内部已设 `closePendingTicks`；交换失败 **必须在此设**，否则界面不会自动关闭（2026-05-31 修复的 bug）。

---

### 10.3 onKeyInput — 按键事件处理

```java
@SubscribeEvent
public static void onKeyInput(InputEvent.Key event) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.gameMode == null) return;

    int action = event.getAction();
    if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return; // [1] 过滤 REPEAT

    boolean keyDown = (action == GLFW.GLFW_PRESS);
    boolean isInventoryKey = isInventoryKeyEvent(mc, event);
    boolean isGuiSwapKey = SWAP_IN_GUI_KEY.isUnbound() ? false : isGuiSwapKeyEvent(event);

    // [2] EditBox 保护：消费 E 键 click 防止关闭界面
    if (keyDown && isInventoryKey && mc.screen != null && hasEditBoxFocus(mc.screen)) {
        while (mc.options.keyInventory.consumeClick()) {}
        if (mc.screen instanceof AbstractContainerScreen) {
            if (mc.player.containerMenu.getSlot(0).hasItem()) return;
        } else return;
    }

    // [3] GUI 交换
    if (keyDown && config.guiSwapEnabled.get()) {
        if ((isGuiSwapKey || (isInventoryKey && SWAP_IN_GUI_KEY.isUnbound()))
                && mc.screen instanceof AbstractContainerScreen) {
            performSwap(mc);
        }
    }
}
```

| 行 | 功能 | 说明 |
|-----|------|------|
| [1] | 过滤事件类型 | 忽略 REPEAT(值=2)，只处理 PRESS(1) 和 RELEASE(0) |
| [2] | EditBox 保护 | 输入框有焦点时 `consumeClick()` 循环消耗 E 键 pending click |
| [3] | GUI 交换 | `guiSwapEnabled=true` 时，按 E 键（或 GUI 交换键）直接交换 |

**GUI 交换的触发条件**：
- `guiSwapEnabled=true`
- `SWAP_IN_GUI_KEY` 未绑定 → E 键触发；已绑定 → 只能用专属键触发

> **[移植]**：
> - **Forge**：`InputEvent.Key` 完全相同
> - **Fabric**：无 `InputEvent.Key`，用 Mixin `Keyboard.onKey` 替代

### tryPerformGuiSwap — ScreenKeyMixin 入口

```java
public static boolean tryPerformGuiSwap(AbstractContainerScreen<?> screen) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.gameMode == null) return false;
    if (!config.guiSwapEnabled.get() || !SWAP_IN_GUI_KEY.isUnbound()) return false;
    return performSwap(mc);
}
```

由 `ScreenKeyMixin` 在 `keyPressed()` 中调用。仅在 `guiSwapEnabled=true` 且 GUI 交换键未绑定时生效。返回值决定是否消费按键。

---

### 10.4 performSwap — 交换分发核心

```java
private static boolean performSwap(Minecraft mc) {
    // [1] 前置检查
    if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
    Slot hs = screen.getSlotUnderMouse();
    if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled.get())) return false;

    int sel = mc.player.getInventory().selected;

    // [2] 双方都空 → 不交换（创造/生存共用）
    if (!hs.hasItem() && mc.player.getInventory().getItem(sel).isEmpty()) return false;

    // [3] 创造模式 → 分流 creativeSwap
    if (screen instanceof CreativeModeInventoryScreen cs) {
        if (creativeSwap(mc, cs, sel)) { playSwapSound(mc); return true; }
        return false;
    }

    // [4] 生存物品栏 → 限定操作背包+热键栏
    if (screen instanceof InventoryScreen
            && (!isPlayerInventorySlot(hs) || hs.index == hotbarMenuSlot(sel)))
        return false;

    // [5] 槽位类型校验（Curios 兼容）
    ItemStack hand = mc.player.getInventory().getItem(sel);
    if (!hand.isEmpty() && !hs.mayPlace(hand)) return false;

    // [6] 标准 SWAP 发包
    int closeDelay = isVanillaInventory(screen) ? 1 : 2;
    if (containerSwap(screen, hs.index, sel)) {
        playSwapSound(mc);
        SwapKeyState.closePendingTicks = closeDelay;
        return true;
    }
    return false;
}
```

**过滤链（按执行顺序）**：

| 步骤 | 检查条件 | 返回 false 的场景 |
|------|----------|------------------|
| 1 | 屏幕类型 | 非 `AbstractContainerScreen` |
| 2 | 悬停槽位 | 鼠标不在有效槽位上 + 不是空槽位交换模式 |
| 3 | 双方都空 | 悬停槽和手持物品栏都为空 |
| 4 | 创造模式 | 分流到 `creativeSwap()` |
| 5 | 生存物品栏装备保护 | `InventoryScreen` 中悬停护甲槽(5-8)/副手槽(45) |
| 6 | `hs.mayPlace()` | **Curios 戒指槽拒绝非戒指物品** |
| 7 | `containerSwap()` | 发包成功/失败 |

**步骤 5 详解**：
```java
if (screen instanceof InventoryScreen
        && (!isPlayerInventorySlot(hs) || hs.index == hotbarMenuSlot(sel)))
    return false;
```
- `!isPlayerInventorySlot(hs)`：物品栏中装备槽的 `slot.container` 仍为 `player.getInventory()`，但 `hs.index=5-8`(护甲)或 `45`(副手)不应在生存物品栏界面操作
- `hs.index == hotbarMenuSlot(sel)`：悬停槽 = 当前热键栏槽位（自己换自己）

**步骤 6 详解**（`hs.mayPlace` — 客户端预检）：
```java
ItemStack hand = mc.player.getInventory().getItem(sel);
if (!hand.isEmpty() && !hs.mayPlace(hand)) return false;
```
针对 Curios 的兼容修复。Curios 的戒指槽覆盖 `Slot.mayPlace()` 拒绝不符合类型的物品。过去的代码不加检查直接发包，服务端拒绝但客户端已播放音效+关闭界面。

**containerSwap()**：
```java
private static boolean containerSwap(AbstractContainerScreen<?> s, int slotIdx, int hotbar) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.getConnection() == null) return false;
    Int2ObjectOpenHashMap<ItemStack> cs = new Int2ObjectOpenHashMap<>();
    mc.getConnection().send(new ServerboundContainerClickPacket(
            s.getMenu().containerId, s.getMenu().getStateId(),
            slotIdx, hotbar, ClickType.SWAP, ItemStack.EMPTY, cs));
    return true;
}
```
- `slotIdx`：菜单槽位索引（`hs.index`）
- `hotbar`：热键栏编号 0-8
- `ClickType.SWAP`：模拟按数字键
- 返回值恒 `true`（只检查网络连接存在性）

> **[移植]**：
> - `ServerboundContainerClickPacket` 签名：MC 1.20.1/1.21.1 相同；MC 26.1 改为 `ContainerInput`
> - `screen.getSlotUnderMouse()`：所有版本存在（`AbstractContainerScreen` 受保护方法）

---

### 10.5 creativeSwap — 创造模式交换 (重点)

创造模式有完全独立的交换逻辑，按以下**严格顺序**执行四个分支：

```
1. CONTAINER（创造标签页物品格）
       ↓
2. CREATIVE_EQUIP（csi=5-8/45 护甲/副手）⚠️ 必须在 SlotWrapper 之前
       ↓
3. SlotWrapper（创造模式热键栏包装槽）
       ↓
4. REGULAR（普通热键栏槽位，兜底）
```

**⚠️ 分支顺序至关重要**：CREATIVE_EQUIP 的护甲/副手槽也是 `SlotWrapper` 对象，SlotWrapper 在前会被误拦。特别是 Forge 的 `SlotWrapper.getContainerSlot()` 未被覆写（返回屏幕位置0-8，与装备 csi 5-8 重叠），必须在 `creativeSwap()` 中将 **SlotWrapper 分支放在 CREATIVE_EQUIP 之前**（但这里 NeoForge 的顺序是 CREATIVE_EQUIP 在前，因为 NeoForge 覆写了 `getContainerSlot()` 返回目标索引 36-44，不会与 5-8 重叠。**移植到 Forge 时需互换顺序**）。

```java
private static boolean creativeSwap(Minecraft mc, CreativeModeInventoryScreen cs, int sel) {
    if (mc.gameMode == null) return false;
    Slot hs = cs.getSlotUnderMouse();
    if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled.get())) return false;

    int hotbarSize = hotbarSize(mc);
    int menuHotbarStart = 36;        // 热键栏菜单索引起点
    int heldIdx = menuHotbarStart + sel;
    ItemStack handStack = mc.player.getInventory().getItem(sel);
```

#### 分支 1：CONTAINER

```java
    if (hs.container == CreativeModeInventoryScreen.CONTAINER) {
        ItemStack held = handStack.copy();
        ItemStack item = hs.getItem().copyWithCount(1);  // 从标签页拿 1 个

        if (!held.isEmpty()) {
            int f = freeSlot(mc);  // 找空闲槽放旧手持
            if (f >= 0 && f < mc.player.getInventory().items.size()) {
                mc.player.getInventory().items.set(f, held.copy());
                mc.gameMode.handleCreativeModeItemAdd(held.copy(), menuHotbarStart + f);
            }
        }
        if (sel < mc.player.getInventory().items.size()) {
            mc.player.getInventory().items.set(sel, item);
            mc.gameMode.handleCreativeModeItemAdd(item, heldIdx);  // ⚠️ 必须菜单索引
        }
        SwapKeyState.closePendingTicks = 1;
        return true;
    }
```

**`handleCreativeModeItemAdd` 索引规则**：

| 传入索引 | 效果 |
|---------|------|
| `36+sel` (菜单索引) | ✅ 正确写入热键栏对应槽 |
| 0-35 (物品栏索引) | ❌ 写入错误的装备槽 |
| 36-40 (装备栏索引) | ❌ 服务端拒绝 |

#### 分支 2：CREATIVE_EQUIP（装备 + 副手）⚠️ isInventoryOpen 守卫

```java
    int csi = hs.getContainerSlot();
    if (cs.isInventoryOpen() && (csi == 45 || (csi >= 5 && csi <= 8))) {
        // 护甲类型验证
        if (csi <= 8 && !handStack.isEmpty()) {
            EquipmentSlot expected = csi == 5 ? EquipmentSlot.HEAD :
                                    csi == 6 ? EquipmentSlot.CHEST :
                                    csi == 7 ? EquipmentSlot.LEGS : EquipmentSlot.FEET;
            EquipmentSlot actual = mc.player.getEquipmentSlotForItem(handStack);
            if (!actual.isArmor() || actual != expected) return false;
        }
        mc.gameMode.handleInventoryMouseClick(
            cs.getMenu().containerId, csi, sel, ClickType.SWAP, mc.player);
        SwapKeyState.closePendingTicks = 1;
        return true;
    }
```

**`cs.isInventoryOpen()` 守卫**（2026-06-01 新增）：
- `true`：当前在**物品栏/生存物品栏页**（显示玩家模型、装备槽）
- `false`：当前在**创造模式标签页**（物品网格）→ 装备槽不可见，不处理

**`EquipmentSlot` 枚举常量映射**：
- `csi=5` → `EquipmentSlot.HEAD`
- `csi=6` → `EquipmentSlot.CHEST`
- `csi=7` → `EquipmentSlot.LEGS`
- `csi=8` → `EquipmentSlot.FEET`
- `csi=45` → 副手（无需类型校验）

⚠️ **不能用 `EquipmentSlot.values()[n]`**：数组顺序为 `[MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD]`，与直觉完全相反。

#### 分支 3：SlotWrapper

```java
    if (hs instanceof CreativeModeInventoryScreen.SlotWrapper w) {
        int t = w.target.index;  // 菜单索引 (36-44)
        if (isPlayerInventorySlot(w) && t != heldIdx) {
            ItemStack ti = cs.getMenu().getSlot(t).getItem().copy();
            ItemStack hi = cs.getMenu().getSlot(heldIdx).getItem().copy();
            int invIdx = t >= menuHotbarStart ? t - menuHotbarStart : t;  // 转物品栏索引

            safeSet(mc, sel, ti);
            mc.gameMode.handleCreativeModeItemAdd(ti, heldIdx);  // 菜单索引
            safeSet(mc, invIdx, hi);
            mc.gameMode.handleCreativeModeItemAdd(hi, t);        // 菜单索引
            SwapKeyState.closePendingTicks = 1;
            return true;
        }
        return false;
    }
```

**索引转换**：
- `w.target.index`：菜单索引（36-44 = 热键栏 0-8）
- `invIdx`：物品栏索引（`t - 36`），用于 `safeSet` 写入客户端列表
- `handleCreativeModeItemAdd(_, menuIndex)`：**必须菜单索引**同步服务端

#### 分支 4：REGULAR（兜底）

```java
    int c2 = hs.getContainerSlot();
    if (c2 >= 0 && c2 < hotbarSize && c2 != sel) {
        ItemStack hi = handStack.copy();
        ItemStack oi = mc.player.getInventory().getItem(c2).copy();
        safeSet(mc, sel, oi);
        mc.gameMode.handleCreativeModeItemAdd(oi, heldIdx);
        safeSet(mc, c2, hi);
        mc.gameMode.handleCreativeModeItemAdd(hi, menuHotbarStart + c2);
        SwapKeyState.closePendingTicks = 1;
        return true;
    }
    return false;
}
```

处理创造模式下悬停在非包装的热键栏槽位（`csi` 在 0-8 且不等于当前 sel）。

> **[移植 — creativeSwap 整体]**：
> - **Forge**：⚠️ `SlotWrapper.getContainerSlot()` 未被覆写（返回屏幕位置 0-8），而 `CREATIVE_EQUIP` 中 `csi >= 5 && csi <= 8` 与屏幕位置 5-8 重叠。Forge 版本在 CREATIVE_EQUIP 分支中增加 `swTarget==null` 守卫来排除 SlotWrapper 对象，保持与 NF 相同的分支顺序。
> - **Fabric**：无 AT，`CONTAINER → SimpleContainer`，`SlotWrapper → getSimpleName()`，`w.target → getDeclaredField("target")`

---

### 10.6 按键检测方法组

```java
// ── Public：供 Mixin 调用 ──
public static boolean isSwapKey(InputConstants.Key key) {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.options == null) return false;
    InputConstants.Key invKey = mc.options.keyInventory.getKey();
    return invKey.getType() == key.getType() && invKey.getValue() == key.getValue();
}

// ── GLFW 物理按键检测 ──
private static boolean isInventoryKeyPhysicallyDown(Minecraft mc) {
    InputConstants.Key key = mc.options.keyInventory.getKey();
    if (key.getType() != InputConstants.Type.KEYSYM) return false;
    return GLFW.glfwGetKey(mc.getWindow().getWindow(), key.getValue()) == GLFW.GLFW_PRESS;
}

// ── InputEvent 匹配 ──
private static boolean isInventoryKeyEvent(Minecraft mc, InputEvent.Key event) {
    InputConstants.Key ik = mc.options.keyInventory.getKey();
    return ik.getType() == InputConstants.Type.KEYSYM && event.getKey() == ik.getValue();
}

private static boolean isGuiSwapKeyEvent(InputEvent.Key event) {
    if (SWAP_IN_GUI_KEY.isUnbound()) return false;
    InputConstants.Key bk = SWAP_IN_GUI_KEY.getKey();
    return bk.getType() == InputConstants.Type.KEYSYM && event.getKey() == bk.getValue();
}
```

**方法用途总结**：

| 方法 | 用途 | 调用者 |
|------|------|--------|
| `isSwapKey()` | 判断 InputConstants.Key 是否为 E 键 | `KeyClickMixin` |
| `isInventoryKeyPhysicallyDown()` | GLFW 级别检测 E 键物理状态（忽略焦点） | `onClientTick` 状态机 |
| `isInventoryKeyEvent()` | InputEvent.Key 匹配 E 键 | `onKeyInput` |
| `isGuiSwapKeyEvent()` | InputEvent.Key 匹配 GUI 交换键 | `onKeyInput` |

> **[移植]**：
> - **Forge**：完全相同
> - **Fabric**：`isInventoryKeyEvent`/`isGuiSwapKeyEvent` 无 `InputEvent.Key` → 在 Mixin `Keyboard.onKey` 中手动构建匹配
> - Forge 1.20.1：`mc.getWindow().getWindow()` → `mc.getWindow().getHandle()`

---

### 10.7 辅助方法组

```java
// 槽位验证
private static boolean isPlayerInventorySlot(Slot slot) {
    return slot.container == Minecraft.getInstance().player.getInventory();
}

// 界面类型
private static boolean isVanillaInventory(AbstractContainerScreen<?> screen) {
    return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
}

// 索引计算
private static int hotbarMenuSlot(int sel) {
    return 36 + sel;  // 热键栏映射到菜单槽位 36-44
}

private static int hotbarSize(Minecraft mc) {
    return mc.player.getInventory().items.size() - 27;
}

// 空闲槽位
private static int freeSlot(Minecraft mc) {
    int size = mc.player.getInventory().items.size();
    int hbSize = hotbarSize(mc);
    int sel = mc.player.getInventory().selected;
    for (int i = 0; i < hbSize; i++)
        if (i != sel && mc.player.getInventory().items.get(i).isEmpty()) return i;
    for (int i = hbSize; i < size; i++)
        if (mc.player.getInventory().items.get(i).isEmpty()) return i;
    return -1;
}

// 安全写入
private static void safeSet(Minecraft mc, int idx, ItemStack stack) {
    if (idx >= 0 && idx < mc.player.getInventory().items.size())
        mc.player.getInventory().items.set(idx, stack);
}

// EditBox 检测
private static boolean hasEditBoxFocus(Screen s) {
    if (s == null) return false;
    if (s.getFocused() instanceof EditBox) return true;
    for (var c : s.children()) if (c instanceof EditBox) return true;
    String n = s.getClass().getName();
    return n.contains("BookEdit") || n.contains("SignEdit");
}

// 鼠标重定位
private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
    if (!config.mouseReposition.get() || !(screen instanceof AbstractContainerScreen<?> s)) return;
    positionCursorToUIBottomRight(s);
}

private static void positionCursorToUIBottomRight(AbstractContainerScreen<?> s) {
    Minecraft mc = Minecraft.getInstance();
    long h = mc.getWindow().getWindow();
    double gs = mc.getWindow().getGuiScale();
    GLFW.glfwSetCursorPos(h,
            (int) ((s.getGuiLeft() + s.getXSize()) * gs) - 5,
            (int) ((s.getGuiTop() + s.getYSize()) * gs) - 5);
    suppressNextTooltip = true;
    suppressTooltipFrames = 2;
}

// 音效
private static void playSwapSound(Minecraft mc) {
    if (!config.soundEnabled.get() || mc.player == null) return;
    mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
}

// 调试日志
private static void debugLog(String msg) {
    if (config.debug.get()) LOGGER.info("[SusInstantSwap] {}", msg);
}
```

> **[移植 — 辅助方法]**：
> - `isPlayerInventorySlot`/`isVanillaInventory`/`hotbarMenuSlot`/`hotbarSize`/`freeSlot`/`safeSet`：所有版本通用
> - `hasEditBoxFocus`：`EditBox` 类路径、`BookEdit`/`SignEdit` 在不同版本可能不同
> - `playSwapSound`：MC 1.21 是 `playNotifySound`，1.20.1 是 `playSound`（参数不同）
> - `positionCursorToUIBottomRight`：Forge 1.20.1 `getWindow()` → `getHandle()`

---

## 十一、SusInstantSwapMod.java — Mod 入口

```java
@Mod(value = "susinstantswap", dist = Dist.CLIENT)  // NeoForge @Mod
public class SusInstantSwapMod {
    public static final String MOD_ID = "susinstantswap";
    private static final Logger LOGGER = LogUtils.getLogger();
    public static SwapConfig CONFIG;
    public static ModConfigSpec CONFIG_SPEC;

    public SusInstantSwapMod(IEventBus modEventBus, ModContainer modContainer) {  // NF 特有构造器
        LOGGER.info("[SusInstantSwap] v2.0");
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        CONFIG = new SwapConfig(b);
        CONFIG_SPEC = b.build();
        modContainer.registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);

        // 配置界面（NeoForge 内置）
        if (FMLEnvironment.dist == Dist.CLIENT)
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (c, s) -> new ConfigurationScreen(c, s));

        modEventBus.register(this);
        InstantSwapClient.init(CONFIG);
    }

    @SubscribeEvent
    public void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        InstantSwapClient.registerKey(event);
    }
}
```

> **[移植 — 完整平台对照]**：

| 代码 | NeoForge | Forge 1.21.1 | Forge 1.20.1 | Fabric |
|------|:--:|:--:|:--:|:--:|
| `@Mod(dist=Dist.CLIENT)` | ✅ | 几乎相同 | 几乎相同 | `ClientModInitializer` |
| 构造器 | `(IEventBus, ModContainer)` | `(FMLJavaModLoadingContext)` 或 `()` | 同 Forge 1.21 | `onInitializeClient()` |
| 配置注册 | `modContainer.registerConfig()` | `context.registerConfig()` | 相同 | Gson JSON |
| 配置界面 | `IConfigScreenFactory` | `ConfigScreenFactory` Record | 相同 | ModMenu API |
| 事件注册 | `modEventBus.register(this)` | `context.getModEventBus()` | 相同 | 各自独立注册 |

---

## 十二、语言文件

### en_us.json

```json
{
  "description.susinstantswap": "Hold the inventory key to swap items with your hotbar. Coexists with vanilla — short press toggles inventory, long press swaps.",
  "key.susinstantswap.swap_in_gui": "Swap in GUI",
  "key.categories.susinstantswap": "Su's Instant Swap",
  "config.susinstantswap.title": "Su's Instant Swap Config",
  "config.susinstantswap.modEnabled": "Enable Mod",
  "config.susinstantswap.modEnabled.tooltip": "Master switch — when off, the mod is completely disabled and E key behaves like vanilla.",
  "config.susinstantswap.holdThresholdMs": "Long Press Threshold (ms)",
  "config.susinstantswap.holdThresholdMs.tooltip": "Press duration above this is treated as long press. Range: 50-1000ms, default 200ms.",
  "config.susinstantswap.soundEnabled": "Swap Sound",
  "config.susinstantswap.soundEnabled.tooltip": "Play a sound when swapping items.",
  "config.susinstantswap.mouseReposition": "Mouse Reposition",
  "config.susinstantswap.mouseReposition.tooltip": "When opening a container or inventory, the mouse automatically moves to the bottom-right corner.",
  "config.susinstantswap.guiSwapEnabled": "GUI Swap",
  "config.susinstantswap.guiSwapEnabled.tooltip": "In container screens, press the GUI swap key to directly swap items and close the screen.",
  "config.susinstantswap.emptySlotSwapEnabled": "Empty Slot Swap",
  "config.susinstantswap.emptySlotSwapEnabled.tooltip": "Also perform the swap on empty slots.",
  "config.susinstantswap.debug": "Debug Logging",
  "config.susinstantswap.debug.tooltip": "Output debug messages to the game log."
}
```

### zh_cn.json

```json
{
  "description.susinstantswap": "按住物品栏键将物品与快捷栏交换。与原版共存——短按打开背包，长按执行交换。",
  "key.susinstantswap.swap_in_gui": "界面中更换",
  "key.categories.susinstantswap": "探囊取物",
  "config.susinstantswap.title": "探囊取物 - 配置",
  "config.susinstantswap.modEnabled": "启用模组",
  "config.susinstantswap.modEnabled.tooltip": "总开关 — 关闭后模组完全停用，E键恢复原版行为。",
  "config.susinstantswap.holdThresholdMs": "长按判定阈值（毫秒）",
  "config.susinstantswap.holdThresholdMs.tooltip": "按下超过此时长视为长按。范围：50-1000ms，默认200ms。",
  "config.susinstantswap.soundEnabled": "交换音效",
  "config.susinstantswap.soundEnabled.tooltip": "交换物品时播放音效。",
  "config.susinstantswap.mouseReposition": "鼠标重定位",
  "config.susinstantswap.mouseReposition.tooltip": "打开容器或物品栏时，鼠标将自动移到界面右下角。",
  "config.susinstantswap.guiSwapEnabled": "界面中更换",
  "config.susinstantswap.guiSwapEnabled.tooltip": "在容器界面中，可通过界面内交换按键直接交换物品并关闭界面。",
  "config.susinstantswap.emptySlotSwapEnabled": "空位交换",
  "config.susinstantswap.emptySlotSwapEnabled.tooltip": "悬停空槽位时也可执行交换。",
  "config.susinstantswap.debug": "调试日志",
  "config.susinstantswap.debug.tooltip": "输出调试信息到游戏日志。"
}
```

> **[移植]**：语言文件在所有版本通用。Fabric 路径 `assets/susinstantswap/lang/` 相同。

---

## 十三、平台差异完整对照表

### 13.1 事件系统

| 功能 | NeoForge 1.21.1 | Forge 1.21.1 | Forge 1.20.1 | Fabric 1.21.1 |
|------|:--:|:--:|:--:|:--:|
| Tick 事件 | `ClientTickEvent.Post` | ✅ 相同 | ✅ 相同 | `ClientTickEvents.END_CLIENT_TICK` |
| 按键事件 | `InputEvent.Key` | ✅ 相同 | ✅ 相同 | Mixin `Keyboard.onKey` |
| 屏幕事件 | `ScreenEvent.Init.Post` | ✅ 相同 | ✅ 相同 | Mixin `Screen.init` |
| 按键注册 | `RegisterKeyMappingsEvent` | ✅ 相同 | ✅ 相同 | `KeyBindingHelper.registerKeyBinding()` |
| 右键交互 | `PlayerInteractEvent` | ✅ 相同 | ✅ 相同 | Mixin |
| Tooltip | `RenderTooltipEvent.Pre` | ✅ 相同 | ✅ 相同 | Mixin |
| 事件注册 | `NeoForge.EVENT_BUS.register()` | `MinecraftForge.EVENT_BUS` | 相同 | 各自独立 |

### 13.2 配置系统

| 功能 | NeoForge | Forge | Fabric |
|------|:--:|:--:|:--:|
| 配置 API | `ModConfigSpec` (neoforge) | `ForgeConfigSpec` (minecraftforge) | Gson JSON |
| 注册方式 | `modContainer.registerConfig()` | `context.registerConfig()` | 手动 load/save |
| 动态读取 | `.get()` 自动 | `.get()` + 双层同步 ⚠️ | 读 POJO 字段 |
| 配置界面 | `IConfigScreenFactory` | `ConfigScreenFactory` Record | ModMenu API |

### 13.3 关键方法差异

| API | MC 1.20.1 | MC 1.21.1 | MC 26.1 |
|-----|-----------|-----------|---------|
| `ServerboundContainerClickPacket` | 标准构造 | 标准构造 | `ContainerInput` 替代 |
| `Window.getWindow()` | `getHandle()` ⚠️ | `getWindow()` | `handle()` |
| `playSound` (交换音效) | `playSound()` | `playNotifySound()` | 待确认 |
| `CreativeModeInventoryScreen` 构造 | 3参数 (Player, FeatureFlagSet, boolean) | 同 1.20.1 | 2参数 |
| `SlotWrapper.getContainerSlot()` | 返回屏幕位置 ⚠️ | 同 1.20.1 ⚠️ | 待确认 |

### 13.4 Access Transformer 替代

| 访问目标 | NeoForge AT | Forge AT | Fabric (无 AT) |
|---------|:--:|:--:|:--:|
| `CONTAINER` 字段 | AT ✅ | AT ✅ | `hs.container instanceof SimpleContainer` |
| `SlotWrapper` 类 | AT (public-f) ✅ | AT (public-f) ✅ | `hs.getClass().getSimpleName().equals("SlotWrapper")` |
| `w.target` 字段 | AT (public) ✅ | AT (public) ✅ | `getDeclaredField("target")` 反射 |
| `CreativeModeInventoryScreen` | AT ✅ | AT ✅ | 无额外需要 |

### 13.5 构建系统

| 配置 | NeoForge 1.21.1 | Forge 1.21.1 | Forge 1.20.1 | Fabric 1.21.1 |
|------|:--:|:--:|:--:|:--:|
| 插件 | `net.neoforged.moddev` | FG6+ / FG7 | FG6+ | fabric-loom |
| Java | 21 | 21 | 17 | 21 |
| Gradle | 8.14.4 | 8.x | 8.x | 8.10.2 |

---

## 十四、移植步骤指南

### 14.1 移植到 Forge 1.21.1

**直接可复用的文件**：
- `SwapKeyState.java` ✅ 直接复制
- `KeyClickMixin.java` ✅ 直接复制
- `ScreenKeyMixin.java` ✅ 直接复制
- Mixin JSON ✅ 直接复制
- AT 文件 ✅ 直接复制
- 语言文件 ✅ 直接复制

**需要修改的文件**：

| 文件 | 修改内容 |
|------|---------|
| `SwapConfig.java` | 包名 `net.neoforged.neoforge.common.ModConfigSpec` → `net.minecraftforge.common.ForgeConfigSpec` + 双层同步 |
| `InstantSwapClient.java` | `NeoForge.EVENT_BUS.register()` → `MinecraftForge.EVENT_BUS.register()` / `@Mod.EventBusSubscriber` |
| `SusInstantSwapMod.java` | 构造器 `(IEventBus, ModContainer)` → `(FMLJavaModLoadingContext)`；配置界面 `IConfigScreenFactory` → `ConfigScreenFactory` Record；双层配置 syncToRuntime |
| `creativeswap 分支顺序` | ⚠️ **Forge SlotWrapper.getContainerSlot() 未覆写，CREATIVE_EQUIP 加 swTarget==null 守卫** |

**Forge 配置双层同步代码**：
```java
// SusInstantSwapMod 构造器中
CONFIG_SPEC = builder.build();
context.registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);  // 先注册
CONFIG.syncToRuntime();  // 再同步（Forge 已将文件值写入 spec）
```

### 14.2 移植到 Forge 1.20.1

除 Forge 1.21.1 步骤外，额外：

- Java 17
- `mc.getWindow().getWindow()` → `mc.getWindow().getHandle()`
- `playNotifySound` → `playSound`（API 参数变化验证）
- `compatibilityLevel` → `JAVA_17`
- `CreativeModeInventoryScreen` 构造器验证（可能需要 AT `FeatureFlagSet` 参数）

### 14.3 移植到 Fabric 1.21.1

**需要重写的组件**：

| NF 组件 | Fabric 替代方案 |
|---------|----------------|
| `SusInstantSwapMod` 构造器 + `@Mod` | `ClientModInitializer.onInitializeClient()` |
| `NeoForge.EVENT_BUS` 事件 | `ClientTickEvents` + Mixin `Keyboard.onKey` + Mixin `Screen.init` |
| `InputEvent.Key` | Mixin `Keyboard.onKey` 中手动构建按键匹配 |
| `ModConfigSpec` | Gson JSON POJO（`load()`/`save()`） |
| `IConfigScreenFactory` | ModMenu API |
| AT（CONTAINER/SlotWrapper/target） | 反射替代（见 §十三.13.4） |
| `RegisterKeyMappingsEvent` | `KeyBindingHelper.registerKeyBinding()` |

**直接可复用的文件**：
- `SwapKeyState.java` ✅
- `SwapConfig.java` 的逻辑概念（需改为 JSON）
- `InstantSwapClient.java` 的核心逻辑（去掉事件注解，改用 Fabric 事件）
- Mixin 注入点相同（`KeyMapping.click/set`，`Screen.keyPressed`）
- Mixin JSON ✅
- 语言文件 ✅

---

## 十五、关键陷阱汇总

| # | 陷阱 | 影响平台 | 严重性 | 解决方案 |
|---|------|---------|--------|---------|
| 1 | `handleCreativeModeItemAdd` 索引 | 全部 | **高** | slotIdx 必须是菜单索引(36+)，不是物品栏索引(0-35) |
| 2 | `EquipmentSlot.values()` 顺序 | 全部 | **高** | 数组为 `[MAINHAND, OFFHAND, FEET...]`。必须用常量 `HEAD/CHEST/LEGS/FEET` |
| 3 | **Forge SlotWrapper csi 重叠** | **Forge** | **高** | `SlotWrapper.getContainerSlot()` 返回屏幕位置（0-8），与装备 csi 5-8 重叠。CREATIVE_EQUIP 需加 `swTarget==null` 守卫排除 SlotWrapper，但**保持 NF 相同的分支顺序** |
| 4 | Forge 双层配置同步 | Forge | **高** | 构建→注册→syncToRuntime，保存时 syncToSpec→save |
| 5 | LONG_PRESS 交换失败不关界面 | 全部 | **高** | `performSwap()` 返回 false 时必须设 `closePendingTicks` |
| 6 | `isInventoryOpen()` 守卫 | 全部 | 中 | CREATIVE_EQUIP 分支加入 `cs.isInventoryOpen()` 防止标签页误判 |
| 7 | 模组容器 2-tick 关闭 | 全部 | 中 | 非原版容器设 `closePendingTicks=2` |
| 8 | `hs.mayPlace()` 客户端预检 | 全部 | 中 | Curios 兼容：发包前检查槽位类型 |
| 9 | Fabric 无 AT | Fabric | 中 | `CONTAINER→SimpleContainer`, `SlotWrapper→getSimpleName()`, `target→反射` |
| 10 | Fabric Loom 内部类名 remap | Fabric | 中 | `getDeclaredField("target")` 自动 remap ✅；`Class.forName("...$SlotWrapper")` 不会 ❌ |
| 11 | Forge 构造器限制 | Forge | 中 | 只接受 `(FMLJavaModLoadingContext)` 和 `()` |
| 12 | MC 26.1 API 变更 | 26.1 | **高** | `ServerboundContainerClickPacket→ContainerInput`；无 `ClickType` |
| 13 | Forge 1.20.1 `getWindow()` | Forge 1.20 | 中 | `mc.getWindow().getWindow()` → `mc.getWindow().getHandle()` |
| 14 | 双 Mixin 防护 | 全部 | 低 | 确保 KeyClickMixin + ScreenKeyMixin 都注入，否则长按闪烁 |

---

*文档版本: v2.0 | 基线: NeoForge 1.21.1 neoforge-1.21.1 分支（工作区最新代码）| 生成时间: 2026-06-01*

*代码行数: ~460 行（6 个 Java 文件）*
