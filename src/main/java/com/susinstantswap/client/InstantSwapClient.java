package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.SusInstantSwapMod;
import com.susinstantswap.config.SwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Sus-InstantSwap Forge 1.20.1 核心客户端逻辑。
 *
 * 从 Fabric 1.20.1 v1.1.1 移植到 Forge，适配差异：
 *   - Fabric ClientTickEvents → Forge TickEvent.ClientTickEvent
 *   - Fabric KeyBindingHelper → 直接 KeyMapping + RegisterKeyMappingsEvent
 *   - Gson 配置 → ForgeConfigSpec（配置访问改为 .get()）
 *   - 保留 remap 安全模式：unwrapSlot / isPlayerInventorySlot / getBoundKey
 *   - 保留双保险按键检测：KeyMapping.isDown() + GLFW 物理轮询
 *   - 添加 tooltip 抑制（鼠标重定位时防止闪现）
 */
@OnlyIn(Dist.CLIENT)
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_KEY;
    private static KeyMapping SWAP_IN_GUI_KEY;

    // ── 状态机 ──
    enum SwapState { IDLE, OPEN }
    private static SwapState state = SwapState.IDLE;
    private static long pressStartTime;
    private static boolean longPressConfirmed;

    // ── 边缘检测（替代 Fabric 的 InputEvent.Key，使用 tick 轮询检测按键上下沿）──
    private static boolean prevDown;
    private static boolean prevGuiSwapDown;

    // ── tooltip 抑制 ──
    private static boolean suppressNextTooltip;
    private static int suppressTooltipFrames;

    private static boolean configLogged;

    // ── 反射缓存（hoveredSlot 字段）──
    private static Field hoveredSlotField;
    private static boolean hoveredSlotResolved;

    // ── 改键支持 ──
    private static boolean boundKeyLogged;

    // ═══════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════

    public static void init() {
        LOGGER.info("[SusInstantSwap] v1.2.0 初始化客户端交换逻辑 (Forge 1.21.1)...");

        SWAP_KEY = new KeyMapping(
                "key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap"
        );

        SWAP_IN_GUI_KEY = new KeyMapping(
                "key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                "key.categories.susinstantswap"
        );

        MinecraftForge.EVENT_BUS.register(InstantSwapClient.class);
        LOGGER.info("[SusInstantSwap] 按键绑定和事件已注册");
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_KEY);
        event.register(SWAP_IN_GUI_KEY);
    }

    // ═══════════════════════════════════════════════════════════
    // 每帧 Tick（合并 Fabric 的 ClientTickEvents.END_CLIENT_TICK）
    // 包含：按键边缘检测 + OPEN 状态追踪 + tooltip 抑制帧计数
    // ═══════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        // ── tooltip 抑制帧计数 ──
        if (suppressTooltipFrames > 0) {
            suppressTooltipFrames--;
            if (suppressTooltipFrames == 0) {
                suppressNextTooltip = false;
            }
        }

        // ── 首次日志 ──
        if (!configLogged) {
            configLogged = true;
            // 防御性同步：确保 Runtime 已从 Spec 加载（belt-and-suspenders）
            SusInstantSwapMod.CONFIG.syncToRuntime();
            LOGGER.info("[SusInstantSwap] 配置: longPressMode={}, holdThresholdMs={}, soundEnabled={}, mouseReposition={}, guiSwapEnabled={}, debug={}",
                    SwapConfig.longPressModeRuntime, SwapConfig.holdThresholdMsRuntime,
                    SwapConfig.soundEnabledRuntime, SwapConfig.mouseRepositionRuntime, SwapConfig.guiSwapEnabledRuntime, SwapConfig.debugRuntime);
        }

        // ── 早期退出 ──
        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            prevDown = false;
            prevGuiSwapDown = false;
            return;
        }

        boolean creative = mc.gameMode.hasInfiniteItems();

        // ── 按键检测：双保险（isDown + GLFW 轮询）──
        boolean down = isSwapKeyDown();

        // ══ 边缘检测：上升沿 = PRESS，下降沿 = RELEASE ══
        boolean pressed = down && !prevDown;
        boolean released = !down && prevDown;
        prevDown = down;

        // ── GUI 键边缘检测（仅当 GUI 键已单独指定时）──
        if (SwapConfig.guiSwapEnabledRuntime && !SWAP_IN_GUI_KEY.isUnbound()) {
            boolean guiDown = isGuiSwapKeyDown();
            boolean guiPressed = guiDown && !prevGuiSwapDown;
            prevGuiSwapDown = guiDown;
            if (guiPressed && mc.screen instanceof AbstractContainerScreen) {
                if (performGuiSwap(mc, creative)) {
                    debugLog("GUI 交换完成（专用键）");
                    state = SwapState.IDLE;
                    prevDown = isSwapKeyDown(); // 重新同步
                    return;
                }
            }
        }

        if (pressed) {
            handleKeyPress(mc, creative);
        }
        if (released) {
            handleKeyRelease(mc, creative);
        }

        // ══ OPEN 状态下的 Tick 操作 ══
        if (state == SwapState.OPEN) {
            handleOpenTick(mc, creative);
        }
    }

    // ── 按键按下处理 ──

    private static void handleKeyPress(Minecraft mc, boolean creative) {
        // GUI 交换（跟随即时交换键）：在任意容器界面上按下交换键时尝试
        if (SwapConfig.guiSwapEnabledRuntime && SWAP_IN_GUI_KEY.isUnbound()
                && mc.screen instanceof AbstractContainerScreen) {
            if (performGuiSwap(mc, creative)) {
                debugLog("GUI 交换完成（跟随即时键）+ 关闭界面");
                state = SwapState.IDLE;
                mc.player.closeContainer();
                return;
            }
        }

        if (state != SwapState.IDLE) return;
        if (!canInteract(mc)) return;

        // 有界面打开 → 模拟原版物品栏键（E键）行为
        if (mc.screen != null) {
            // 排除聊天和暂停界面（不应被干扰）
            if (mc.screen instanceof ChatScreen || mc.screen.isPauseScreen()) {
                return;
            }
            simulateVanillaInventoryKey(mc);
            debugLog("按键按下 → 模拟原版E键");
            return;
        }

        // ── 游戏中：打开物品栏，进入 OPEN 状态 ──
        openInventoryAndPositionCursor(mc, creative);
        state = SwapState.OPEN;
        pressStartTime = System.currentTimeMillis();
        longPressConfirmed = false;
        debugLog("按键按下 → 打开" + (creative ? "创造模式" : "生存模式") + "物品栏");
    }

    // ── 按键释放处理 ──

    private static void handleKeyRelease(Minecraft mc, boolean creative) {
        if (state != SwapState.OPEN) return;

        long elapsed = System.currentTimeMillis() - pressStartTime;

        if (!SwapConfig.longPressModeRuntime || elapsed >= SwapConfig.holdThresholdMsRuntime) {
            // 经典模式：松手即交换
            // 长按模式 + 达到阈值：长按交换
            performSwapAndClose(mc, creative);
            debugLog("松手 → 交换完成 (" + elapsed + "ms)");
        }
        // 长按模式 + 未达阈值：物品栏保持打开（由下一个按键关闭）
        state = SwapState.IDLE;
    }

    // ── OPEN 状态下 Tick 操作 ──

    private static void handleOpenTick(Minecraft mc, boolean creative) {
        // 外部关闭（ESC/死亡等）
        if (mc.screen == null) {
            debugLog("外部关闭 → 重置状态");
            state = SwapState.IDLE;
            return;
        }

        if (!SwapConfig.longPressModeRuntime) {
            // 经典模式：OPEN 状态下不做额外操作
            return;
        }

        // ── 长按模式：阈值追踪 ──
        long elapsed = System.currentTimeMillis() - pressStartTime;

        // 达到阈值 → 标记确认（不在此时交换，等松手）
        if (elapsed >= SwapConfig.holdThresholdMsRuntime && isSwapKeyDown()) {
            longPressConfirmed = true;
        }

        // 键已物理松开（OS 拦截兜底：可能没触发 RELEASE 边缘）→ 判定是否长按交换
        if (!isSwapKeyDown()) {
            if (longPressConfirmed) {
                performSwapAndClose(mc, creative);
                debugLog("长按交换完成（Tick OS 拦截兜底，" + elapsed + "ms）");
            }
            state = SwapState.IDLE;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Tooltip 抑制（鼠标重定位时防止闪现）
    // ═══════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onRenderTooltip(RenderTooltipEvent.Pre event) {
        if (suppressNextTooltip) {
            event.setCanceled(true);
            suppressNextTooltip = false;
            suppressTooltipFrames = 0;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════

    private static boolean isVanillaInventoryKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return false;
        return SWAP_KEY.same(mc.options.keyInventory);
    }

    /**
     * 模拟原版物品栏键（E键）行为。
     * <p>
     * 先让当前屏幕处理按键（如 EMI 配方界面会在此关闭自己并返回物品栏），
     * 如果屏幕未消费按键，则关闭容器/物品栏。
     * 此方法与 vanilla 中按下物品栏键的行为完全一致。
     */
    private static void simulateVanillaInventoryKey(Minecraft mc) {
        InputConstants.Key invKey = mc.options.keyInventory.getKey();
        boolean handled = mc.screen.keyPressed(invKey.getValue(), 0, 0);
        if (!handled) {
            mc.player.closeContainer();
        }
    }

    private static boolean canInteract(Minecraft mc) {
        GameType mode = mc.gameMode.getPlayerMode();
        return mode == GameType.SURVIVAL || mode == GameType.CREATIVE || mode == GameType.ADVENTURE;
    }

    private static boolean isInventoryScreen(Screen screen) {
        return screen instanceof InventoryScreen
                || screen instanceof CreativeModeInventoryScreen;
    }

    private static Screen createInventoryScreen(Minecraft mc, boolean creative) {
        if (creative) {
            // MC 1.20.1: (Player, FeatureFlagSet, boolean)
            // boolean: false = 显示创造标签页, true = 显示生存背包标签页
            return new CreativeModeInventoryScreen(
                    mc.player, mc.player.connection.enabledFeatures(), false);
        }
        return new InventoryScreen(mc.player);
    }

    /**
     * 读取 KeyMapping 的实际运行时绑定键（尊重改键）。
     *
     * 使用 MC 公开 API，完全不用反射：
     *   KeyMapping.saveString() → 当前绑定键名 (如 "key.keyboard.r")
     *   InputConstants.getKey() → 解析回 InputConstants.Key 对象
     *
     * 若解析失败（极端情况），降级到 getDefaultKey()。
     */
    private static InputConstants.Key getBoundKey() {
        try {
            String keyName = SWAP_KEY.saveString();
            InputConstants.Key result = InputConstants.getKey(keyName);
            if (!boundKeyLogged) {
                boundKeyLogged = true;
                LOGGER.info("[SusInstantSwap] 当前绑定键: {} (默认: {})",
                        keyName, SWAP_KEY.getDefaultKey().getName());
            }
            return result;
        } catch (Exception e) {
            LOGGER.warn("[SusInstantSwap] getBoundKey 解析失败: {}", e.getMessage());
            return SWAP_KEY.getDefaultKey();
        }
    }

    /**
     * 检测交换键是否当前按下（双保险检测）。
     *
     * 第1层：KeyMapping.isDown() — 标准改键检测
     *   对非修饰键（R/F/G 等）准确可靠。
     * 第2层：GLFW 物理轮询 — OS 拦截兜底
     *   Windows Alt 键会被 OS 拦截（菜单栏激活），
     *   导致 KeyMapping.isDown() 可能返回 false。
     *   此时直接用 GLFW 读物理键盘状态。
     */
    private static boolean isSwapKeyDown() {
        if (SWAP_KEY == null) return false;

        // 第1层：标准改键检测
        if (SWAP_KEY.isDown()) return true;

        // 第2层：GLFW 物理轮询 — 处理 OS 拦截修饰键（如 Windows Alt）
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().getWindow();
        InputConstants.Key key = getBoundKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return GLFW.glfwGetKey(handle, key.getValue()) == GLFW.GLFW_PRESS;
    }

    /** 检测 GUI 交换键是否物理按下。与 isSwapKeyDown 相同的双保险逻辑。 */
    private static boolean isGuiSwapKeyDown() {
        if (SWAP_IN_GUI_KEY == null) return false;
        if (SWAP_IN_GUI_KEY.isDown()) return true;
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().getWindow();
        InputConstants.Key key = getGuiBoundKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return GLFW.glfwGetKey(handle, key.getValue()) == GLFW.GLFW_PRESS;
    }

    /** 读取 GUI 交换键运行时绑定（尊重改键） */
    private static InputConstants.Key getGuiBoundKey() {
        try {
            return InputConstants.getKey(SWAP_IN_GUI_KEY.saveString());
        } catch (Exception e) {
            return SWAP_IN_GUI_KEY.getDefaultKey();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // GUI 交换（在物品栏界面中直接交换并关闭界面）
    // ═══════════════════════════════════════════════════════════

    private static boolean performGuiSwap(Minecraft mc, boolean creative) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;

        Slot hovered = getHoveredSlot(screen);
        if (hovered == null || !hovered.hasItem()) {
            debugLog("GUI交换: 悬停槽位无物品, 跳过");
            return false;
        }

        int hoveredIndex = hovered.index;
        int selectedHotbar = mc.player.getInventory().selected;

        // ── 创造模式物品栏 ──
        if (screen instanceof CreativeModeInventoryScreen) {
            if (!creative) return false;
            if (performGuiCreativeSwap(mc, (CreativeModeInventoryScreen) screen)) {
                playSwapSound(mc);
                if (mc.player != null) mc.player.closeContainer();
                return true;
            }
            return false;
        }

        // ── 生存/冒险物品栏：仅允许背包+快捷栏（9-44）──
        if (screen instanceof InventoryScreen) {
            int hotbarMenuSlot = selectedHotbar + 36;
            if (!isAllowedMenuSlot(hoveredIndex) || hoveredIndex == hotbarMenuSlot) {
                debugLog("GUI交换: 不允许的槽位 (悬停=" + hoveredIndex + ")");
                return false;
            }
            if (performGuiContainerSwap(screen, hoveredIndex, selectedHotbar)) {
                playSwapSound(mc);
                if (mc.player != null) mc.player.closeContainer();
                LOGGER.info("[SusInstantSwap] GUI交换: 槽位{} <-> 快捷栏{}", hoveredIndex, selectedHotbar);
                return true;
            }
            return false;
        }

        // 其他容器：不支持 GUI 交换
        return false;
    }

    private static boolean performGuiContainerSwap(AbstractContainerScreen<?> screen, int slotIndex, int hotbar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return false;

        int containerId = screen.getMenu().containerId;
        int stateId = screen.getMenu().getStateId();
        Int2ObjectOpenHashMap<ItemStack> changed = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, slotIndex, hotbar,
                ClickType.SWAP, ItemStack.EMPTY, changed);
        mc.getConnection().send(packet);
        return true;
    }

    private static boolean performGuiCreativeSwap(Minecraft mc, CreativeModeInventoryScreen screen) {
        if (mc.player == null || mc.gameMode == null) return false;

        Slot hovered = getHoveredSlot(screen);
        if (hovered == null || !hovered.hasItem()) return false;

        int selected = mc.player.getInventory().selected;
        int heldSlotIndex = selected + 36;

        // 路径A：创造标签页物品（不属于玩家背包）→ 拿取一个到快捷栏
        if (!isPlayerInventorySlot(hovered, mc)) {
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            if (!heldItem.isEmpty()) {
                int freeSlot = findFreeBackpackSlot(mc);
                if (freeSlot >= 0) {
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, freeSlot);
                }
            }
            ItemStack item = hovered.getItem().copyWithCount(1);
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            LOGGER.info("[SusInstantSwap] GUI交换(创造标签页) -> 快捷栏{}", selected);
            return true;
        }

        // 路径B：玩家背包/快捷栏 → 双向交换
        Slot realSlot = unwrapSlot(hovered);
        int realIndex = (realSlot != null) ? realSlot.index : hovered.index;

        if (realIndex >= 9 && realIndex <= 44 && realIndex != heldSlotIndex) {
            ItemStack targetItem = mc.player.inventoryMenu.getSlot(realIndex).getItem().copy();
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
            if (!heldItem.isEmpty()) {
                mc.gameMode.handleCreativeModeItemAdd(heldItem, realIndex);
            }
            LOGGER.info("[SusInstantSwap] GUI交换(创造背包): {} <-> 快捷栏{}", realIndex, selected);
            return true;
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // 鼠标重定位
    // ═══════════════════════════════════════════════════════════

    private static void openInventoryAndPositionCursor(Minecraft mc, boolean creative) {
        Screen screen = createInventoryScreen(mc, creative);
        if (SwapConfig.mouseRepositionRuntime) {
            preMoveCursorToWindowCorner(mc);
        }
        mc.setScreen(screen);
        if (SwapConfig.mouseRepositionRuntime) {
            positionCursorToUIBottomRight(mc, screen);
        }
    }

    private static void preMoveCursorToWindowCorner(Minecraft mc) {
        long handle = mc.getWindow().getWindow();
        GLFW.glfwSetCursorPos(handle,
                mc.getWindow().getWidth() - 10,
                mc.getWindow().getHeight() - 10);
    }

    private static void positionCursorToUIBottomRight(Minecraft mc, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            preMoveCursorToWindowCorner(mc);
            return;
        }

        // 物品栏背景尺寸：生存 176×166，创造 195×136
        int imageW = (screen instanceof CreativeModeInventoryScreen) ? 195 : 176;
        int imageH = (screen instanceof CreativeModeInventoryScreen) ? 136 : 166;

        // 物品栏 UI 居中，计算右下角在缩放坐标系中的位置
        int guiRight = (screen.width + imageW) / 2;
        int guiBottom = (screen.height + imageH) / 2;

        long handle = mc.getWindow().getWindow();
        double guiScale = mc.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(handle,
                (int) (guiRight * guiScale) - 4,
                (int) (guiBottom * guiScale) - 4);

        // 抑制接下来 2 帧的 tooltip 渲染
        suppressNextTooltip = true;
        suppressTooltipFrames = 2;
    }

    // ═══════════════════════════════════════════════════════════
    // 悬停槽位反射（remap 安全）
    // ═══════════════════════════════════════════════════════════

    private static Slot getHoveredSlot(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> cs)) return null;

        if (!hoveredSlotResolved) {
            hoveredSlotResolved = true;
            for (String name : new String[]{"hoveredSlot", "focusedSlot"}) {
                try {
                    hoveredSlotField = AbstractContainerScreen.class.getDeclaredField(name);
                    hoveredSlotField.setAccessible(true);
                    LOGGER.info("[SusInstantSwap] hoveredSlot 字段: {}", name);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (hoveredSlotField == null) {
                for (Field f : AbstractContainerScreen.class.getDeclaredFields()) {
                    if (!Slot.class.isAssignableFrom(f.getType())) continue;
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    hoveredSlotField = f;
                    LOGGER.info("[SusInstantSwap] hoveredSlot 字段(类型匹配): {}", f.getName());
                    break;
                }
            }
        }
        if (hoveredSlotField == null) return null;
        try {
            return (Slot) hoveredSlotField.get(cs);
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 交换执行
    // ═══════════════════════════════════════════════════════════

    private static void performSwapAndClose(Minecraft mc, boolean creative) {
        if (creative) {
            performCreativeSwap(mc);
        } else {
            performSurvivalSwap(mc);
        }
        if (mc.player != null) {
            mc.player.closeContainer();
        }
    }

    // ── 生存模式交换 ──

    private static boolean isAllowedMenuSlot(int menuSlot) {
        return menuSlot >= 9 && menuSlot <= 44;
    }

    private static void performSurvivalSwap(Minecraft mc) {
        if (!(mc.screen instanceof InventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: 非物品栏屏幕");
            return;
        }

        Slot hovered = getHoveredSlot(mc.screen);
        if (hovered == null || !hovered.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: 悬停槽位无物品");
            return;
        }

        int slotIndex = hovered.index;
        int hotbar = mc.player.getInventory().selected;
        int hotbarMenuSlot = hotbar + 36;

        if (!isAllowedMenuSlot(slotIndex) || slotIndex == hotbarMenuSlot) {
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;
        int stateId = mc.player.inventoryMenu.getStateId();
        Int2ObjectOpenHashMap<ItemStack> changed = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, slotIndex, hotbar,
                ClickType.SWAP, ItemStack.EMPTY, changed);

        if (mc.getConnection() != null) {
            mc.getConnection().send(packet);
            playSwapSound(mc);
            LOGGER.info("[SusInstantSwap] 生存交换: 槽位{} <-> 快捷栏{}", slotIndex, hotbar);
        }
    }

    // ── 创造模式交换 ──

    /**
     * 创造模式交换（remap 安全的实现）。
     *
     * 使用 isPlayerInventorySlot() 替代容器引用比较（避免 CONTAINER 常量依赖）。
     * 使用 unwrapSlot() 替代 SlotWrapper instanceof（避免内部类 remap 问题）。
     */
    private static void performCreativeSwap(Minecraft mc) {
        if (!(mc.screen instanceof CreativeModeInventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: 非创造模式物品栏");
            return;
        }
        if (mc.gameMode == null) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: gameMode=null");
            return;
        }

        Slot hovered = getHoveredSlot(mc.screen);
        if (hovered == null || !hovered.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: 悬停槽位无物品");
            return;
        }

        int selected = mc.player.getInventory().selected;
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;

        // ── 路径A：创造标签页物品（不属于玩家背包）→ 拿取一个到快捷栏 ──
        if (!isPlayerInventorySlot(hovered, mc)) {
            // 先把手持物品移入背包空位
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            if (!heldItem.isEmpty()) {
                int freeSlot = findFreeBackpackSlot(mc);
                if (freeSlot >= 0) {
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, freeSlot);
                }
            }
            ItemStack item = hovered.getItem().copyWithCount(1);
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            didSwap = true;
            LOGGER.info("[SusInstantSwap] 创造交换(标签页) -> 快捷栏{}", selected);
            playSwapSound(mc);
            return;
        }

        // ── 解包 SlotWrapper → 获取被包裹的真实槽位 ──
        Slot realSlot = unwrapSlot(hovered);
        int realIndex = (realSlot != null) ? realSlot.index : hovered.index;

        // ── 路径B：玩家背包/快捷栏 → 双向交换 ──
        if (realIndex >= 9 && realIndex <= 44 && realIndex != heldSlotIndex) {
            ItemStack targetItem = mc.player.inventoryMenu.getSlot(realIndex).getItem().copy();
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
            if (!heldItem.isEmpty()) {
                mc.gameMode.handleCreativeModeItemAdd(heldItem, realIndex);
            }
            didSwap = true;
            LOGGER.info("[SusInstantSwap] 创造交换(背包): {} <-> 快捷栏{}", realIndex, selected);
        }

        if (didSwap) {
            playSwapSound(mc);
        }
    }

    /**
     * 判断槽位是否属于玩家背包（remap 安全）。
     * 通过 slot.container == player.getInventory() 替代对
     * CreativeModeInventoryScreen.CONTAINER 的直接引用比较。
     */
    private static boolean isPlayerInventorySlot(Slot slot, Minecraft mc) {
        return slot.container == mc.player.getInventory();
    }

    /**
     * 通用 Slot 解包（remap 安全）。
     * 遍历 slot 类中所有非 static 的 Slot 类型字段，返回被包装的真实槽位。
     * 非包装类返回 null。
     *
     * 替代 instanceof CreativeModeInventoryScreen.SlotWrapper，
     * 避免 Loom remap 导致内部类名称变化。
     */
    private static Slot unwrapSlot(Slot slot) {
        if (slot.getClass() == Slot.class) return null;
        try {
            for (Field field : slot.getClass().getDeclaredFields()) {
                if (Slot.class.isAssignableFrom(field.getType())
                        && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return (Slot) field.get(slot);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int findFreeBackpackSlot(Minecraft mc) {
        for (int menuSlot = 9; menuSlot <= 35; menuSlot++) {
            if (mc.player.inventoryMenu.getSlot(menuSlot).getItem().isEmpty()) {
                return menuSlot;
            }
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════════════
    // 声音 & 调试
    // ═══════════════════════════════════════════════════════════

    private static void playSwapSound(Minecraft mc) {
        if (!SwapConfig.soundEnabledRuntime || mc.player == null) return;
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (SwapConfig.debugRuntime) {
            LOGGER.info("[SusInstantSwap] {}", msg);
        }
    }
}
