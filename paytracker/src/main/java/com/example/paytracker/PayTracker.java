package com.example.paytracker;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side only. Reads chat, recolors payment messages and shows your net
 * profit/loss from payments at the top of the screen.
 *
 * If DonutSMP words its payment messages differently, edit the two patterns below.
 */
public class PayTracker implements ClientModInitializer {

    private static final String AMOUNT = "\\$?([\\d,]+(?:\\.\\d+)?)\\s*([kmbtKMBT])?";

    // "Steve paid you $1.5M"
    private static final Pattern RECEIVED =
            Pattern.compile("(\\w{3,16}) paid you " + AMOUNT);
    // "You paid Steve $250K"
    private static final Pattern SENT =
            Pattern.compile("You paid (\\w{3,16}) " + AMOUNT);

    private static final int ORANGE = 0xFFAA00;
    private static final int GREEN = 0x55FF55;
    private static final int RED = 0xFF5555;

    /** Net money from payments this session. */
    private static double net = 0;

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay) return message;
            return process(message);
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath("paytracker", "net_label"),
                (graphics, delta) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.options.hideGui) return;
                    String title = "SESSION PROFIT";
                    String text = (net >= 0 ? "+" : "-") + "$" + format(Math.abs(net));
                    int color = net > 0 ? GREEN : net < 0 ? RED : 0xFFFFFF;

                    int w = Math.max(mc.font.width(title), mc.font.width(text)) + 16;
                    int h = 30;
                    int x = graphics.guiWidth() - w - 6; // top right, like the screenshot
                    int y = 6;
                    graphics.fill(x, y, x + w, y + h, 0xCC1A1206);
                    graphics.renderOutline(x, y, w, h, 0xFFD4A017);
                    graphics.drawString(mc.font, title,
                            x + (w - mc.font.width(title)) / 2, y + 5, 0xFFFFD27F, true);
                    graphics.drawString(mc.font, text,
                            x + (w - mc.font.width(text)) / 2, y + 17, 0xFF000000 | color, true);
                });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) ->
                dispatcher.register(ClientCommandManager.literal("paytracker")
                        .then(ClientCommandManager.literal("reset").executes(c -> {
                            net = 0;
                            return Command.SINGLE_SUCCESS;
                        }))));
    }

    private static Component process(Component original) {
        String s = original.getString();

        Matcher m = RECEIVED.matcher(s);
        if (m.find()) {
            double amount = parse(m.group(2), m.group(3));
            net += amount;
            return rebuild(s, m, 1, 2, ORANGE, GREEN);
        }

        m = SENT.matcher(s);
        if (m.find()) {
            double amount = parse(m.group(2), m.group(3));
            net -= amount;
            // Name stays default colour, money is red.
            return rebuild(s, m, 1, 2, 0xFFFFFF, RED);
        }
        return original;
    }

    /** Rebuilds the line with the name and amount recolored. */
    private static Component rebuild(String s, Matcher m, int nameGroup, int amountGroup,
                                     int nameColor, int amountColor) {
        int nameStart = m.start(nameGroup), nameEnd = m.end(nameGroup);
        // The money text runs from just before the digits (maybe a $) to the end of the match.
        int digitsStart = m.start(amountGroup);
        int moneyStart = (digitsStart > 0 && s.charAt(digitsStart - 1) == '$') ? digitsStart - 1 : digitsStart;
        int moneyEnd = m.end();

        MutableComponent out = Component.literal(s.substring(0, nameStart));
        out.append(Component.literal(s.substring(nameStart, nameEnd))
                .withStyle(st -> st.withColor(nameColor)));
        out.append(Component.literal(s.substring(nameEnd, moneyStart)));
        out.append(Component.literal(s.substring(moneyStart, moneyEnd))
                .withStyle(st -> st.withColor(amountColor)));
        out.append(Component.literal(s.substring(moneyEnd)));
        return out;
    }

    private static double parse(String number, String suffix) {
        double v = Double.parseDouble(number.replace(",", ""));
        if (suffix == null) return v;
        return switch (suffix.toLowerCase(Locale.ROOT).charAt(0)) {
            case 'k' -> v * 1_000;
            case 'm' -> v * 1_000_000;
            case 'b' -> v * 1_000_000_000;
            case 't' -> v * 1_000_000_000_000.0;
            default -> v;
        };
    }

    private static String format(double v) {
        if (v >= 1e12) return trim(v / 1e12) + "T";
        if (v >= 1e9) return trim(v / 1e9) + "B";
        if (v >= 1e6) return trim(v / 1e6) + "M";
        if (v >= 1e3) return trim(v / 1e3) + "K";
        return trim(v);
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }
}
