# Su's Instant Swap / 探囊取物

**按住 Alt 键实现物品瞬间交换的客户端模组。**

支持生存、创造、冒险全部游戏模式。

---

## 🎮 核心功能

### v1.1.0 新特性（NeoForge 1.21.1）

#### ⚡ 长短按模式（默认开启）

**短按（< 200ms）**：切换物品栏显示/隐藏  
**长按（≥ 200ms）**：立即交换手持物品与悬停槽位物品

这是 v1.1.0 的核心改进，让 Alt 键在不同按压时长下执行不同操作，提升游戏体验。

**关闭后**：仅保留长按交换功能（兼容 v1.0.3 的行为）

#### ⚙️ 配置系统

新增游戏内配置支持（需安装 [Configured](https://www.curseforge.com/minecraft/mc-mods/configured) 模组）：

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| **长短按模式** | 开启：短按切换物品栏/长按交换<br>关闭：仅长按交换 | ✅ 开启 |
| **长按判定阈值** | 按下超过此时长视为长按（50-1000ms） | 200ms |
| **交换音效** | 交换物品时播放音效 | ✅ 开启 |
| **鼠标重定位** | 打开物品栏时自动将鼠标移动到界面右下角 | ✅ 开启 |
| **调试日志** | 输出调试信息到游戏日志 | ❌ 关闭 |

配置界面支持中英文自动切换。

---

### v1.0.3 版本

**功能**：仅支持长按交换（相当于 v1.1.0 关闭"长短按模式"的行为）

- 长按 Alt 键打开物品栏
- 松开时将悬停槽位的物品与手持物品瞬间交换
- 无配置文件，无长短按区分

适合只需要简单交换功能的玩家。

---

## 📦 支持平台

| Loader   | Minecraft | 最新版本 | 状态 |
|----------|-----------|---------|------|
| NeoForge | 1.21.1   | v1.1.0 | ✅ Stable |
| Fabric   | 1.21.1   | v1.0.3 | ✅ Stable |
| Fabric   | 1.20.1   | v1.0.3 | ✅ Stable |

> **注意**：v1.1.0 的新特性（长短按模式、配置系统）目前仅支持 NeoForge 1.21.1。Fabric 版本的更新正在计划中。

---

## 📥 安装

1. 从 [Releases](https://github.com/SuCrispy/Sus-InstantSwap/releases) 下载对应平台和版本的 JAR 文件
2. 放入 Minecraft 的 `mods/` 文件夹
3. （可选）安装 [Configured](https://www.curseforge.com/minecraft/mc-mods/configured) 模组以使用游戏内配置界面（仅 v1.1.0）

### NeoForge 1.21.1（v1.1.0 最新版）

| Loader   | MC      | 版本    | 文件                                        |
|----------|---------|--------|---------------------------------------------|
| NeoForge | 1.21.1  | v1.1.0 | `Sus_InstantSwap-1.1.0-NF1.21.jar`   |

### 其他版本（v1.0.3）

| Loader   | MC      | 版本    | 文件                                        |
|----------|---------|--------|---------------------------------------------|
| Fabric   | 1.21.1  | v1.0.3 | `Sus_InstantSwap-Fabric1.21-1.0.3.jar` |
| Fabric   | 1.20.1  | v1.0.3 | `Sus_InstantSwap-Fabric1.20-1.0.3.jar` |

---

## 🔧 构建

每个平台是独立的 Gradle 项目：

```bash
# NeoForge 1.21.1 (JDK 21)
cd Sus_InstantSwap_NF1.21
./gradlew build

# Fabric 1.21.1 (JDK 21)
cd Sus_InstantSwap_Fabric1.21
./gradlew build

# Fabric 1.20.1 (JDK 17)
cd Sus_InstantSwap_Fabric1.20.1
./gradlew build
```

---

## 📄 许可证

LGPL-3.0 — 详见 [LICENSE](LICENSE)。
