package com.susinstantswap.client;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Thin green groove indicators on both sides of the player-inventory
 * rows.  Hovering any groove and holding E swaps that entire row with
 * the hotbar.  Rows are detected dynamically from the menu slot layout.
 *
 * Detection strategy (v2): instead of grouping by Y-coordinate (fragile
 * with modded screens), we look up each containerSlot range (9-17,
 * 18-26, 27-35) directly in the menu.  This works for any screen that
 * includes the player's main-inventory slots regardless of layout.
 */
public class RowArrowWidget {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Maximum number of player-inventory rows (always 3 in vanilla). */
    public static final int MAX_ROWS = 3;
    /** Actual number of rows detected in the current screen. */
    public static int rowCount = 0;

    private static final int TRIGGER_OVERLAP = 1;
    private static final int TRIGGER_H       = 17;

    private static final int GROOVE_W      = 1;
    private static final int GROOVE_HEIGHT = 14;
    private static final int GROOVE_Y_OFF  = 1;

    private static final int GROOVE_DEFAULT = 0x70003300;
    private static final int GROOVE_HOVERED = 0xC000FF00;

    public static boolean visible    = false;
    public static int     hoveredRow = -1;

    /** Y position (screen coords) for each detected row. */
    private static final int[] rowY          = new int[MAX_ROWS];
    /** Left edge (screen X) of the leftmost slot in each row. */
    private static final int[] rowSlotLeft   = new int[MAX_ROWS];
    /** Right edge (screen X) of the rightmost slot in each row + 18. */
    private static final int[] rowSlotRight  = new int[MAX_ROWS];
    /** Menu slot indices for each row, sorted left→right (col 0..8). */
    private static final int[][] rowSlots    = new int[MAX_ROWS][9];
    /** The row index (0-2) that each player-inventory containerSlot (9-35) maps to, or -1. */
    private static final int[] containerSlotToRow = new int[36];
    /** The column index (0-8) within the row for each containerSlot, or -1. */
    private static final int[] containerSlotToCol = new int[36];

    private static int panelLeft, panelRight;
    private static boolean rowsDetected = false;

    /**
     * Detects player-inventory rows in the current container screen.
     *
     * Strategy: for each of the three standard containerSlot ranges
     * (9-17, 18-26, 27-35), look up the corresponding Slot in the menu.
     * If all 9 slots of a range exist, that counts as one row.
     *
     * This is layout-agnostic — it works regardless of how the mod
     * arranges the slots visually, because we only need the Slot
     * objects to read their x/y for rendering and their menu index
     * for swapping.
     */
    public static void detectRows(AbstractContainerScreen<?> screen, LocalPlayer player) {
        rowsDetected = false;
        rowCount = 0;

        panelLeft  = screen.getGuiLeft();
        int top    = screen.getGuiTop();
        panelRight = panelLeft + screen.getXSize();

        Inventory playerInv = player.getInventory();

        // Build a map: containerSlot → Slot, for all player-inventory slots
        Map<Integer, Slot> csToSlot = new HashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            if (isPlayerInventorySlot(slot, playerInv)
                    && slot.getContainerSlot() >= 9
                    && slot.getContainerSlot() < 36) {
                csToSlot.put(slot.getContainerSlot(), slot);
            }
        }

        if (debugLog()) {
            LOGGER.info("[RowSwap] detectRows: screen={} playerInvSlots={} panelLeft={} panelRight={}",
                    screen.getClass().getSimpleName(), csToSlot.size(), panelLeft, panelRight);
        }

        // Clear mappings
        for (int i = 0; i < 36; i++) {
            containerSlotToRow[i] = -1;
            containerSlotToCol[i] = -1;
        }

        // Detect each row by containerSlot range
        int idx = 0;
        for (int row = 0; row < MAX_ROWS; row++) {
            int startCS = 9 + row * 9; // 9, 18, 27
            int endCS = startCS + 9;    // 18, 27, 36

            // Check if all 9 slots in this row exist
            boolean complete = true;
            for (int cs = startCS; cs < endCS; cs++) {
                if (!csToSlot.containsKey(cs)) {
                    complete = false;
                    break;
                }
            }
            if (!complete) {
                if (debugLog()) {
                    LOGGER.info("[RowSwap]   row[{}]: containerSlot {}-{} → INCOMPLETE, skip",
                            row, startCS, endCS - 1);
                }
                continue;
            }

            // Collect slots for this row, sorted by x position (left→right)
            Slot[] rowSlotArr = new Slot[9];
            for (int c = 0; c < 9; c++) {
                rowSlotArr[c] = csToSlot.get(startCS + c);
            }

            // Sort by x position to ensure left-to-right ordering
            Integer[] order = {0, 1, 2, 3, 4, 5, 6, 7, 8};
            java.util.Arrays.sort(order, (a, b) -> Integer.compare(rowSlotArr[a].x, rowSlotArr[b].x));

            int minX = Integer.MAX_VALUE, maxX = 0;
            int y = rowSlotArr[0].y;
            for (int c = 0; c < 9; c++) {
                Slot s = rowSlotArr[order[c]];
                int cs = startCS + order[c];
                rowSlots[idx][c] = screen.getMenu().slots.indexOf(s);
                containerSlotToRow[cs] = idx;
                containerSlotToCol[cs] = c;
                if (s.x < minX) minX = s.x;
                if (s.x > maxX) maxX = s.x;
            }

            rowY[idx]         = top + y;
            rowSlotLeft[idx]  = panelLeft + minX;
            rowSlotRight[idx] = panelLeft + maxX + 18;
            idx++;

            if (debugLog()) {
                LOGGER.info("[RowSwap]   row[{}]: y={} slotL={} slotR={} slots=[{},{},{},{},{},{},{},{},{}]",
                        idx - 1, rowY[idx - 1], rowSlotLeft[idx - 1], rowSlotRight[idx - 1],
                        rowSlots[idx - 1][0], rowSlots[idx - 1][1], rowSlots[idx - 1][2],
                        rowSlots[idx - 1][3], rowSlots[idx - 1][4], rowSlots[idx - 1][5],
                        rowSlots[idx - 1][6], rowSlots[idx - 1][7], rowSlots[idx - 1][8]);
            }
        }

        rowCount = idx;
        rowsDetected = (idx > 0);

        if (debugLog()) {
            LOGGER.info("[RowSwap] detectRows result: detected={}/{} rowsDetected={}",
                    idx, MAX_ROWS, rowsDetected);
        }
    }

    /**
     * Check if a slot belongs to the player's inventory.
     * Uses both identity check (==) and instanceof as fallback
     * for mods that wrap the player inventory.
     */
    private static boolean isPlayerInventorySlot(Slot slot, Inventory playerInv) {
        // Fast path: direct identity
        if (slot.container == playerInv) return true;
        // Fallback: instanceof check for wrapped inventories
        if (slot.container instanceof Inventory) return true;
        return false;
    }

    /** Menu slot index for a given row + column. */
    public static int rowSlotIndex(int row, int col) {
        if (rowsDetected && row < rowCount) {
            return rowSlots[row][col];
        }
        // Fallback: standard menu layout (9 + row * 9 + col)
        return 9 + row * 9 + col;
    }

    private static boolean debugLog() {
        return com.susinstantswap.SusInstantSwapMod.CONFIG != null
            && com.susinstantswap.SusInstantSwapMod.CONFIG.debug.get();
    }

    private static boolean isHoveringRight(int row, double mx, double my) {
        int tx = rowSlotRight[row] - TRIGGER_OVERLAP;
        int ty = rowY[row];
        return mx >= tx && mx < panelRight + 2
            && my >= ty && my < ty + TRIGGER_H;
    }

    private static boolean isHoveringLeft(int row, double mx, double my) {
        int tx = panelLeft - 2;
        int tw = rowSlotLeft[row] - panelLeft + 1;
        int ty = rowY[row];
        return mx >= tx && mx < tx + tw
            && my >= ty && my < ty + TRIGGER_H;
    }

    public static void checkHover(double mouseX, double mouseY) {
        int prev = hoveredRow;
        hoveredRow = -1;
        if (!visible || !rowsDetected) {
            return;
        }
        for (int r = 0; r < rowCount; r++) {
            if (isHoveringLeft(r, mouseX, mouseY)
                    || isHoveringRight(r, mouseX, mouseY)) {
                hoveredRow = r;
                break;
            }
        }
        if (hoveredRow != prev && debugLog()) {
            LOGGER.info("[RowSwap] hover: {}→{}", prev, hoveredRow);
        }
        if (hoveredRow >= 0 && prev != hoveredRow) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null
                    && com.susinstantswap.SusInstantSwapMod.CONFIG != null
                    && com.susinstantswap.SusInstantSwapMod.CONFIG.soundEnabled.get()) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.3f, 1.8f);
            }
        }
    }

    public static void render(Minecraft mc, GuiGraphics g) {
        if (!visible || !rowsDetected) return;

        for (int r = 0; r < rowCount; r++) {
            boolean hovered = (hoveredRow == r);
            int color = hovered ? GROOVE_HOVERED : GROOVE_DEFAULT;
            int gy = rowY[r] + GROOVE_Y_OFF;

            g.fill(rowSlotRight[r], gy,
                   rowSlotRight[r] + GROOVE_W, gy + GROOVE_HEIGHT, color);

            int lx = rowSlotLeft[r] - 3;
            g.fill(lx, gy, lx + GROOVE_W, gy + GROOVE_HEIGHT, color);
        }
    }
}
