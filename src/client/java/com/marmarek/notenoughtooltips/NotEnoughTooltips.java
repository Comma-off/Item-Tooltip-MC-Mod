package com.marmarek.notenoughtooltips;

// input/keybinding classes not used in Mojang mappings here
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
// keybinding removed; open settings via Mod Menu or external integration
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public class NotEnoughTooltips implements ClientModInitializer {

    private static final int POPUP_DURATION_TICKS = 60;
    private static final float POPUP_TRANSPARENCY = 0.93f;
    private static final int TITLE_FALLBACK_COLOR = 0xFFFFFF;
    private static final int NAME_COLOR = 0x55FFFF;
    private static final int ENCHANTMENT_COLOR = 0x55FFFF;
    private static final int POTION_COLOR = 0xAAAACC;
    private static final int EFFECT_COLOR = 0xAAFFAA;

    private enum LineKind {
        TITLE,
        ENCHANTMENT,
        DURABILITY,
        DAMAGE,
        DEFENSE,
        TOUGHNESS,
        KNOCKBACK,
        POTION,
        EFFECT,
        SHULKER_ITEM,
        NOTE
    }

    private record TooltipLine(Component text, LineKind kind) {}

    private static ItemStack lastHeldItem = ItemStack.EMPTY;
    private static int displayTimer = 0;
    // keybinding removed

    @Override
    public void onInitializeClient() {
        // keybinding removed

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            ItemStack current = client.player.getMainHandItem();
            if (hasSignificantChange(current, lastHeldItem)) {
                lastHeldItem = current.copy();
                displayTimer = POPUP_DURATION_TICKS;
            }
            if (displayTimer > 0) displayTimer--;
        });

        HudElementRegistry.replaceElement(VanillaHudElements.HELD_ITEM_TOOLTIP, original -> (graphics, tickDelta) -> {
            if (!isDisplaying()) {
                original.extractRenderState(graphics, tickDelta);
            }
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath("notenoughtooltips", "held_item_popup"),
                (graphics, tickDelta) -> {
            if (displayTimer <= 0) return;
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return;
            ItemStack stack = client.player.getMainHandItem();
            if (stack.isEmpty()) return;

            // Show popup for tools/weapons/armor (skip blocks, food, etc.) or shulker boxes
            boolean isRelevant = isShulkerBox(stack) ||
                                 hasPotionData(stack) ||
                                 stack.isDamageableItem() ||
                                 getAttr(stack, Attributes.ATTACK_DAMAGE) > 0 ||
                                 getAttr(stack, Attributes.ATTACK_SPEED) != 0 ||
                                 getAttr(stack, Attributes.ARMOR) > 0 ||
                                 getAttr(stack, Attributes.ARMOR_TOUGHNESS) > 0;

            if (isRelevant) {
                renderPopup(graphics, tickDelta, stack, client);
            } else {
                displayTimer = 0;
            }
        });
    }

    private static void renderPopup(GuiGraphicsExtractor graphics, DeltaTracker tickDelta, ItemStack stack, Minecraft client) {
        int sw = graphics.guiWidth();
        int sh = graphics.guiHeight();

        List<TooltipLine> lines = new ArrayList<>();

        // Check if this is a shulker box
        boolean isShulkerBox = isShulkerBox(stack);
        
        if (isShulkerBox) {
            // Display shulker box contents
            lines.add(new TooltipLine(stack.getStyledHoverName(), LineKind.TITLE));
            
            List<ItemStack> shulkerItems = getShulkerBoxItems(stack);
            int maxDisplay = 10;
            int displayed = 0;
            
            if (shulkerItems.isEmpty()) {
                lines.add(new TooltipLine(Component.translatable("tooltip.notenoughtooltips.shulker.empty"), LineKind.NOTE));
            } else {
                for (ItemStack item : shulkerItems) {
                    if (displayed >= maxDisplay) break;
                    if (!item.isEmpty()) {
                        Component itemLine = Component.translatable(
                                "tooltip.notenoughtooltips.shulker.item",
                                item.getHoverName(),
                                item.getCount()
                        );
                        lines.add(new TooltipLine(itemLine, LineKind.SHULKER_ITEM));
                        displayed++;
                    }
                }
                
                int remaining = shulkerItems.size() - displayed;
                if (remaining > 0) {
                    lines.add(new TooltipLine(Component.translatable("tooltip.notenoughtooltips.shulker.more", remaining), LineKind.NOTE));
                }
            }
        } else {
            // Original behavior for non-shulker items
            // Item name
            lines.add(new TooltipLine(stack.getStyledHoverName(), LineKind.TITLE));

            addPotionLines(lines, stack);

            // Enchantments
            ItemEnchantments enchants = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (!enchants.isEmpty()) {
                for (var entry : enchants.entrySet()) {
                    Component enchText = Component.empty()
                            .append(entry.getKey().value().description().copy())
                            .append(" " + entry.getValue());
                    lines.add(new TooltipLine(enchText, LineKind.ENCHANTMENT));
                }
            }

            // Durability
            if (stack.isDamageableItem()) {
                int dur = stack.getMaxDamage() - stack.getDamageValue();
                int pct = stack.getMaxDamage() <= 0 ? 0 : (int) Math.round((dur * 100.0) / stack.getMaxDamage());
                lines.add(new TooltipLine(
                        Component.translatable("tooltip.notenoughtooltips.durability", dur, stack.getMaxDamage(), pct),
                        LineKind.DURABILITY
                ));
            }

            // Damage + Sharpness
            double dmg = getAttr(stack, Attributes.ATTACK_DAMAGE);
            if (dmg > 0) {
                int sharpLevel = 0;
                ItemEnchantments enchantsComponent = stack.get(DataComponents.ENCHANTMENTS);
                if (enchantsComponent != null) {
                    for (var entry : enchantsComponent.entrySet()) {
                        if (entry.getKey().is(Enchantments.SHARPNESS)) {
                            sharpLevel = entry.getValue();
                            break;
                        }
                    }
                }
                double sharpBonus = sharpLevel > 0 ? 1.0 + 0.5 * (sharpLevel - 1) : 0.0;
                double totalDmg = 1.0 + dmg + sharpBonus;

                Component dmgText = sharpBonus > 0
                        ? Component.translatable("tooltip.notenoughtooltips.damage.sharpness", formatOne(totalDmg), formatOne(sharpBonus))
                        : Component.translatable("tooltip.notenoughtooltips.damage", formatOne(totalDmg));
                lines.add(new TooltipLine(dmgText, LineKind.DAMAGE));
            }

            double armor = getAttr(stack, Attributes.ARMOR);
            if (armor > 0) {
                double armorIcons = armor / 2.0;
                lines.add(new TooltipLine(
                        Component.translatable("tooltip.notenoughtooltips.defense", formatOne(armor), formatOne(armorIcons)),
                        LineKind.DEFENSE
                ));
            }

            double toughness = getAttr(stack, Attributes.ARMOR_TOUGHNESS);
            if (toughness > 0) {
                lines.add(new TooltipLine(Component.translatable("tooltip.notenoughtooltips.toughness", formatOne(toughness)), LineKind.TOUGHNESS));
            }

            double kbRes = getAttr(stack, Attributes.KNOCKBACK_RESISTANCE);
            if (kbRes > 0) {
                lines.add(new TooltipLine(Component.translatable("tooltip.notenoughtooltips.knockback_res", Math.round(kbRes * 100.0)), LineKind.KNOCKBACK));
            }
        }

        // Compute dynamic sizes
        int padding = 6;
        int maxWidth = 0;
        for (TooltipLine line : lines) {
            int w = client.font.width(line.text());
            if (w > maxWidth) maxWidth = w;
        }
        int boxWidth = maxWidth + padding * 2;
        int lineHeight = client.font.lineHeight;
        int boxHeight = lines.size() * lineHeight + padding * 2;

        int maxTime = Math.max(1, POPUP_DURATION_TICKS);
        float alphaFactor = (float) displayTimer / (float) maxTime;
        float baseTrans = Math.max(0.0f, Math.min(1.0f, POPUP_TRANSPARENCY));
        int alpha = (int) (255 * alphaFactor * baseTrans);
        int alphaBG = ((int) (255 * baseTrans * alphaFactor) & 0xFF) << 24; // respect configured transparency

        int x = sw / 2 - boxWidth / 2;
        // Move tooltip higher (was 40, now 80 to avoid armor points)
        int y = sh - boxHeight - 80;
        if (y < 5) y = 5; // don't go off-screen at top

        // Background and border
        graphics.fill(x - 2, y - 2, x + boxWidth + 2, y + boxHeight + 2, alphaBG | 0x000000);
        graphics.fill(x, y, x + boxWidth, y + boxHeight, alphaBG | 0x111111);

        int textColor = (alpha << 24) | 0xFFFFFF;
        int lineY = y + padding;

        for (int i = 0; i < lines.size(); i++) {
            TooltipLine line = lines.get(i);
            int color = getLineColor(line, stack, alpha, textColor);

            graphics.text(client.font, line.text(), x + padding, lineY, color, true);
            lineY += lineHeight;
        }
    }

    private static int getLineColor(TooltipLine line, ItemStack stack, int alpha, int fallbackColor) {
        return switch (line.kind()) {
            case TITLE -> withAlpha(getTitleColor(line.text(), stack, TITLE_FALLBACK_COLOR), alpha);
            case ENCHANTMENT, SHULKER_ITEM -> withAlpha(ENCHANTMENT_COLOR, alpha);
            case NOTE -> withAlpha(NAME_COLOR, alpha);
            case DURABILITY -> withAlpha(getDurabilityColor(stack), alpha);
            case DAMAGE -> withAlpha(0xFFFF55, alpha);
            case DEFENSE -> withAlpha(0x55AAFF, alpha);
            case TOUGHNESS -> withAlpha(0x00AAAA, alpha);
            case KNOCKBACK -> withAlpha(0x55AA55, alpha);
            case POTION -> withAlpha(POTION_COLOR, alpha);
            case EFFECT -> withAlpha(EFFECT_COLOR, alpha);
        };
    }

    private static int getTitleColor(Component title, ItemStack stack, int fallback) {
        var titleColor = title.getStyle().getColor();
        if (titleColor != null) {
            return titleColor.getValue();
        }

        Integer rarityColor = stack.getRarity().color().getColor();
        if (rarityColor != null) {
            return rarityColor;
        }

        return fallback;
    }

    private static int getDurabilityColor(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return 0xFFFFFF;
        }

        int max = stack.getMaxDamage();
        if (max <= 0) {
            return 0x888888;
        }

        int dur = Math.max(0, max - stack.getDamageValue());
        int pct = (int) Math.round((dur * 100.0) / max);

        if (pct >= 51) return 0x55FF55;
        if (pct >= 25) return 0xFFAA00;
        if (pct >= 10) return 0xFF5555;
        if (pct <= 0) return 0x888888;
        return 0xFF5555;
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    private static boolean hasPotionData(ItemStack stack) {
        return stack.get(DataComponents.POTION_CONTENTS) != null;
    }

    private static void addPotionLines(List<TooltipLine> lines, ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return;
        }

        var effects = contents.getAllEffects();
        if (!effects.iterator().hasNext()) {
            return;
        }

        for (MobEffectInstance effect : effects) {
            Component effectName = effect.getEffect().value().getDisplayName();
            int level = effect.getAmplifier() + 1;
            String durationText = effect.getEffect().value().isInstantenous()
                ? Component.translatable("tooltip.notenoughtooltips.duration.instant").getString()
                : formatTicks(effect.getDuration());
            lines.add(new TooltipLine(
                    Component.translatable("tooltip.notenoughtooltips.effect", effectName, toRoman(level), durationText),
                    LineKind.EFFECT
            ));
        }
    }

    private static String formatOne(double value) {
        return String.format("%.1f", value);
    }

    private static String formatTicks(int ticks) {
        if (ticks < 0) {
            return Component.translatable("tooltip.notenoughtooltips.duration.infinite").getString();
        }

        int totalSeconds = ticks / 20;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private static String toRoman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> Integer.toString(level);
        };
    }

    private static double getAttr(ItemStack stack, Holder<Attribute> attr) {
        var modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) return 0.0;
        double sum = 0.0;
        for (var entry : modifiers.modifiers()) {
            if (entry.attribute().equals(attr) && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                sum += entry.modifier().amount();
            }
        }
        return sum;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem())
                .getPath()
                .endsWith("shulker_box");
    }

    private static List<ItemStack> getShulkerBoxItems(ItemStack stack) {
        List<ItemStack> items = new ArrayList<>();

        // Shulker-like contents are stored via container components in modern versions.
        try {
            ItemContainerContents containerTag = stack.get(DataComponents.CONTAINER);
            if (containerTag != null) {
                for (ItemStack item : containerTag.nonEmptyItemCopyStream().toList()) {
                    items.add(item);
                }
            }
        } catch (Exception ignored) {
            // Fallback if container component isn't available
        }

        return items;
    }

    // Called from mixin to check whether our popup is displaying.
    public static boolean isDisplaying() {
        return displayTimer > 0;
    }

    private static boolean hasSignificantChange(ItemStack a, ItemStack b) {
        if (a == null) a = ItemStack.EMPTY;
        if (b == null) b = ItemStack.EMPTY;
        if (a.isEmpty() && b.isEmpty()) return false;
        if (a.isEmpty() != b.isEmpty()) return true;

        if (!a.getItem().equals(b.getItem())) return true;

        // Compare custom names (if player renamed or renamed by anvil)
        String aName = a.getHoverName().getString();
        String bName = b.getHoverName().getString();
        String aDefault = a.getItem().getDefaultInstance().getHoverName().getString();
        String bDefault = b.getItem().getDefaultInstance().getHoverName().getString();
        boolean aNamed = !aName.equals(aDefault);
        boolean bNamed = !bName.equals(bDefault);
        if (aNamed != bNamed) return true;
        if (aNamed && bNamed) {
            if (!aName.equals(bName)) return true;
        }

        // Compare enchantments only — ignore damage/Durability changes
        ItemEnchantments ea = a.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        ItemEnchantments eb = b.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (!(ea.isEmpty() && eb.isEmpty())) {
            // quick check: size
            if (ea.entrySet().size() != eb.entrySet().size()) return true;
            // detailed check: ensure each enchantment and level match
            for (var entry : ea.entrySet()) {
                boolean found = false;
                for (var entry2 : eb.entrySet()) {
                    if (entry2.getKey().equals(entry.getKey()) && entry2.getValue().equals(entry.getValue())) {
                        found = true;
                        break;
                    }
                }
                if (!found) return true;
            }
        }

        // Compare attribute modifiers component if present
        var ma = a.get(DataComponents.ATTRIBUTE_MODIFIERS);
        var mb = b.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (ma == null && mb == null) return false;
        if (ma == null || mb == null) return true;
        if (!ma.equals(mb)) return true;

        return false;
    }
}
