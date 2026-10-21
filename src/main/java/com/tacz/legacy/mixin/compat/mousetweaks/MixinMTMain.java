package com.tacz.legacy.mixin.compat.mousetweaks;

import com.tacz.legacy.api.item.IAmmoBox;
import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import yalter.mousetweaks.Main;
import yalter.mousetweaks.impl.IGuiScreenHandler;

import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import yalter.mousetweaks.MTConfig;
import yalter.mousetweaks.impl.IMouseState;
import yalter.mousetweaks.impl.MouseButton;
import yalter.mousetweaks.util.MTLog;

@Mixin(Main.class)
public abstract class MixinMTMain {
    @Shadow(remap = false) private static GuiScreen oldGuiScreen;
    @Shadow(remap = false) private static IGuiScreenHandler handler;
    @Shadow(remap = false)
    private static IGuiScreenHandler findHandler(GuiScreen currentScreen) {
        return null;
    }
    @Shadow(remap = false) private static IMouseState mouseState;
    @Shadow(remap = false) private static boolean disableForThisContainer;
    @Shadow(remap = false) private static boolean disableWheelForThisContainer;
    @Shadow(remap = false) private static boolean oldRMBDown;
    @Shadow(remap = false) private static Slot firstRightClickedSlot;
    @Shadow(remap = false) private static Slot oldSelectedSlot;
    @Shadow(remap = false)
    private static boolean areStacksCompatible(ItemStack a, ItemStack b) {
        return false;
    }

    @Shadow(remap = false)
    private static void handleWheel(Slot selectedSlot) {
    }

    /**
     * @author Surreal (Bundles), ported for ammo box compatibility
     * @reason Prevent Mouse Tweaks from synthesizing its own clickSlot() calls
     * on top of our custom ContainerMixin slotClick handling for ammo boxes.
     */
    @Overwrite(remap = false)
    private static void onUpdateInGui(GuiScreen currentScreen) {
        if (oldGuiScreen != currentScreen) {
            oldGuiScreen = currentScreen;
            MTLog.logger.debug("You have just opened " + currentScreen.getClass().getSimpleName() + ".");
            handler = findHandler(currentScreen);
            mouseState.clear();
            if (handler == null) {
                disableForThisContainer = true;
                MTLog.logger.debug("No valid handler found; MT is disabled.");
                return;
            }

            disableForThisContainer = handler.isMouseTweaksDisabled();
            disableWheelForThisContainer = handler.isWheelTweakDisabled();
            MTLog.logger.debug("Handler: " + handler.getClass().getSimpleName() + "; MT is " + (disableForThisContainer ? "disabled" : "enabled") + "; wheel tweak is " + (disableWheelForThisContainer ? "disabled" : "enabled") + ".");
        }

        if (MTConfig.rmbTweak || MTConfig.lmbTweakWithItem || MTConfig.lmbTweakWithoutItem || MTConfig.wheelTweak) {
            if (!disableForThisContainer) {
                Slot selectedSlot = handler.getSlotUnderMouse();
                ItemStack targetStack;
                ItemStack stackOnMouse;
                if (mouseState.isButtonPressed(MouseButton.RIGHT)) {
                    if (!oldRMBDown) {
                        firstRightClickedSlot = selectedSlot;
                    }

                    if (firstRightClickedSlot != null) {
                        targetStack = firstRightClickedSlot.getStack();
                        stackOnMouse = Minecraft.getMinecraft().player.inventory.getItemStack();

                        if (tacz$allowMouseTweaks(targetStack, stackOnMouse) && MTConfig.rmbTweak && handler.disableRMBDraggingFunctionality() && (firstRightClickedSlot != selectedSlot || oldSelectedSlot == selectedSlot) && !handler.isIgnored(firstRightClickedSlot) && !handler.isCraftingOutput(firstRightClickedSlot)) {
                            if (!stackOnMouse.isEmpty() && areStacksCompatible(stackOnMouse, targetStack) && firstRightClickedSlot.isItemValid(stackOnMouse)) {
                                handler.clickSlot(firstRightClickedSlot, MouseButton.RIGHT, false);
                            }
                        }
                    }

                } else {
                    firstRightClickedSlot = null;
                }

                if (oldSelectedSlot != selectedSlot) {
                    oldSelectedSlot = selectedSlot;
                    if (selectedSlot == null) {
                        return;
                    }

                    if (firstRightClickedSlot == selectedSlot) {
                        firstRightClickedSlot = null;
                    }

                    MTLog.logger.debug("You have selected a new slot, it's slot number is " + selectedSlot.slotNumber);
                    targetStack = selectedSlot.getStack().copy();
                    stackOnMouse = Minecraft.getMinecraft().player.inventory.getItemStack().copy();
                    boolean shiftIsDown = Keyboard.isKeyDown(42) || Keyboard.isKeyDown(54);
                    if (tacz$allowMouseTweaks(targetStack, stackOnMouse) && mouseState.isButtonPressed(MouseButton.RIGHT)) {
                        if (MTConfig.rmbTweak && !handler.isIgnored(selectedSlot) && !handler.isCraftingOutput(selectedSlot) && !stackOnMouse.isEmpty() && areStacksCompatible(stackOnMouse, targetStack) && selectedSlot.isItemValid(stackOnMouse)) {
                            handler.clickSlot(selectedSlot, MouseButton.RIGHT, false);
                        }
                    } else if (tacz$allowMouseTweaks(targetStack, stackOnMouse) && mouseState.isButtonPressed(MouseButton.LEFT)) {
                        if (!stackOnMouse.isEmpty()) {
                            if (MTConfig.lmbTweakWithItem && !handler.isIgnored(selectedSlot) && !targetStack.isEmpty() && areStacksCompatible(stackOnMouse, targetStack)) {
                                if (shiftIsDown) {
                                    handler.clickSlot(selectedSlot, MouseButton.LEFT, true);
                                } else if (stackOnMouse.getCount() + targetStack.getCount() <= stackOnMouse.getMaxStackSize()) {
                                    handler.clickSlot(selectedSlot, MouseButton.LEFT, false);
                                    if (!handler.isCraftingOutput(selectedSlot)) {
                                        handler.clickSlot(selectedSlot, MouseButton.LEFT, false);
                                    }
                                }
                            }
                        } else if (MTConfig.lmbTweakWithoutItem && !targetStack.isEmpty() && shiftIsDown && !handler.isIgnored(selectedSlot)) {
                            handler.clickSlot(selectedSlot, MouseButton.LEFT, true);
                        }
                    }
                }

                ItemStack wheelTargetStack = selectedSlot != null ? selectedSlot.getStack() : ItemStack.EMPTY;
                ItemStack wheelStackOnMouse = Minecraft.getMinecraft().player.inventory.getItemStack();
                if (tacz$allowMouseTweaks(wheelTargetStack, wheelStackOnMouse)) {
                    handleWheel(selectedSlot);
                }
            }
        }
    }

    @Unique
    private static boolean tacz$allowMouseTweaks(ItemStack stack1, ItemStack stack2) {
        // 名称表示「两个栈都不是弹药盒时才允许 Mouse Tweaks 介入」；弹药盒由本模组自定义的槽位逻辑处理。
        return !(stack1.getItem() instanceof IAmmoBox) && !(stack2.getItem() instanceof IAmmoBox);
    }
}