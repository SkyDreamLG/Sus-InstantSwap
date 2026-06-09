# Changelog

## [2.1.0] — 2026-06-09

### Added

- **Row Swap Grooves** — thin green groove indicators on both sides of player-inventory rows.
  - Hover any groove + hold E → entire row (9 slots) swaps with the hotbar at once.
  - Works on **all** container screens (inventory, chest, furnace, crafting table, etc.), not just the survival inventory.
  - Dynamic slot detection adapts to any menu layout automatically.
- **Hover sound** — a crisp tick plays when moving the mouse across groove boundaries, respecting the existing Sound config toggle.
- New config option: **"整行交换"** (`rowSwapEnabled`, default ON).

### Fixed

- Creative inventory row swap was broken by `SlotWrapper.index` returning incorrect values in NeoForge. Fixed by building a `containerSlot → menuIndex` mapping during slot detection.
- **Container swap validation — backpack ghost item fix.** Swapping an "in-use" item (e.g. a backpack held in mainhand while its UI is open) now correctly rejects the swap, preventing a ghost item from appearing in the mainhand slot.
  - Added bidirectional `Slot.mayPickup(Player)` checks in `performSwap()`, `containerSwap()`, and `performRowSwap()` — both the hovered slot AND the hotbar slot are now validated, matching vanilla's `AbstractContainerMenu.doClick()` behavior.
  - Added `mayPlace()` validation for row swap hotbar items (was previously missing).
  - New helper method `findMenuSlot()` for locating menu Slot objects by container slot index.
- **Code cleanup:**
  - Replaced `LOGGER.info()` with `debugLog()` in `isContainerOpener()` (was logging every slot on every swap).
  - Removed unused `hotbarMenuSlot()` method and `slotClicked` access transformer entry.

## [2.0.0] — 2026-05-25

### Added

- **Long press swap** — hold inventory key (E) ≥ threshold → swap hovered item with hotbar. Short press still opens/closes inventory.
- **GUI swap** — optional keybind for swapping items directly within container screens without closing them.
- **Creative mode swap** — 4-branch logic handling creative tabs, equipment slots, SlotWrapper, and regular hotbar slots.
- **Dual keybind** — separate "Swap in GUI" key alongside the inventory key.
- **Config screen** — built-in NeoForge configuration GUI with sliders and toggles.
- **Mouse reposition** — cursor auto-moves to bottom-right corner when opening containers.
- **Tooltip suppression** — prevents tooltip flicker during swaps.

### Changed

- Removed `backpackPriority` config option.
- Forge-style config dual-layer sync (spec ↔ runtime) adopted.
- Key mapping intercept uses Mixin injection on `KeyMapping.click()` / `KeyMapping.set()` to prevent long-press screen flicker.

### Fixed

- Creative equipment slot guard — armour type mismatch no longer silently fails.
- 2-tick close delay for modded containers (Curios etc.) to prevent ghost swaps.
- `Slot.mayPlace()` client-side pre-check for Curios compatibility.
- EditBox text protection — typing in text fields no longer closes the screen.

## [2.0.1] — 2026-06-02

### Fixed

- Self-swap guard applied to all container types (was only InventoryScreen).
- Zero-frame mouse reposition via `@Accessor` on mouse handler fields.
- Version number cleanup in `neoforge.mods.toml` for NF 1.21.1 and NF 26.1.
