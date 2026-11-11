package com.example.paytracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pay Tracker - client side only, and only active on DonutSMP.
 *
 * - Recolors "X paid you $N" (name orange, amount green) and "You paid X $N" (amount red)
 * - Shows your profit in a box at the top right
 * - Menu (keybind, set it in Options > Controls) to load your starting balance from /bal
 */
public class PayTracker implements ClientModInitializer {

    // ---- patterns -------------------------------------------------------
    private static final String AMOUNT = "\\$?([\\d,]+(?:\\.\\d+)?)([kmbt])?(?![a-z])";

    // "Steve paid you $1.5M"
    private static final Pattern RECEIVED =
            Pattern.compile("(?i)(\\w{1,16}) (?:has )?(?:paid|sent) you " + AMOUNT);
    // "You paid Steve $250K"
    private static final Pattern SENT =
            Pattern.compile("(?i)\\byou (?:paid|sent) (\\w{1,16}) " + AMOUNT);
    // reply to /bal, e.g. "Balance: $1,234,567" (adjust here if DonutSMP words it differently)
    private static final Pattern BAL =
            Pattern.compile("(?i)(?:bal(?:ance)?\\D{0,20}?|\\$)\\s*([\\d,]+(?:\\.\\d+)?)\\s?([kmbt])?(?![a-z])");
    private static final Pattern LOOSE =
            Pattern.compile("(?i)\\$?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kmbt])?");

    private static final int ORANGE = 0xFFAA00;
    private static final int GREEN = 0x55FF55;
    private static final int RED = 0xFF5555;

    // ---- state ----------------------------------------------------------
    enum Pending { NONE, START, REFRESH }

    static Double start = null;     // starting balance (saved per account)
    static Double balance = null;   // last known balance (updated by payments and /bal)
    static double net = 0;          // net payments this session (fallback)
    static Pending pending = Pending.NONE;
    static long pendingUntil = 0;

    static String lastAny = "";
    static String lastPaid = "";
    static boolean lastPaidMatched = false;
    static String lastBalReply = "";

    private static boolean forceActive = false; // used by the test button
    private static boolean suppress = false;    // test messages must not change your numbers

    private static String lastText = "";
    private static long lastTime = 0;
    private static Component lastOut = null;

    private static KeyMapping openKey;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("paytracker.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Double> saved = new HashMap<>();

    // ---- setup ----------------------------------------------------------
    @Override
    public void onInitializeClient() {
        loadFile();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("paytracker", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.paytracker.open", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    client.setScreen(new PayScreen());
                }
            }
        });

        // Backup route for system messages. The mixin (ChatComponentMixin) covers everything else;
        // both go through handle(), which de-duplicates, so nothing is counted twice.
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay) return message;
            return handle(message);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            net = 0;
            balance = null;
            pending = Pending.NONE;
            start = saved.get(user());
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.fromNamespaceAndPath("paytracker", "net_label"),
                (graphics, delta) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.options.hideGui || !active(mc)) return;

                    double p = profit();
                    String title = "PROFIT";
                    String text = (p >= 0 ? "+" : "-") + "$" + format(Math.abs(p));
                    int color = p > 0 ? GREEN : p < 0 ? RED : 0xFFFFFF;

                    String sub = null;
                    if (start == null) sub = "no start set - open menu";
                    else if (balance == null) sub = "payments only - sync in menu";

                    int w = Math.max(mc.font.width(title), mc.font.width(text));
                    if (sub != null) w = Math.max(w, mc.font.width(sub));
                    w += 16;
                    int h = sub != null ? 42 : 30;
                    int x = graphics.guiWidth() - w - 6; // top right
                    int y = 6;
                    graphics.fill(x, y, x + w, y + h, 0xCC1A1206);
                    graphics.renderOutline(x, y, w, h, 0xFFD4A017);
                    graphics.drawString(mc.font, title,
                            x + (w - mc.font.width(title)) / 2, y + 5, 0xFFFFD27F, true);
                    graphics.drawString(mc.font, text,
                            x + (w - mc.font.width(text)) / 2, y + 17, 0xFF000000 | color, true);
                    if (sub != null) {
                        graphics.drawString(mc.font, sub,
                                x + (w - mc.font.width(sub)) / 2, y + 30, 0xFFAAAAAA, true);
                    }
                });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) ->
                dispatcher.register(ClientCommandManager.literal("paytracker")
                        .then(ClientCommandManager.literal("reset").executes(c -> {
                            resetSession();
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommandManager.literal("test").executes(c -> {
                            sendTest();
                            return Command.SINGLE_SUCCESS;
                        }))));
    }

    // ---- state helpers --------------------------------------------------
    static boolean onDonut(Minecraft mc) {
        return mc.getCurrentServer() != null
                && mc.getCurrentServer().ip.toLowerCase(Locale.ROOT).contains("donutsmp");
    }

    static boolean active(Minecraft mc) {
        return forceActive || onDonut(mc);
    }

    static String user() {
        return Minecraft.getInstance().getUser().getName();
    }

    static double profit() {
        return (start != null && balance != null) ? balance - start : net;
    }

    /** Runs /bal and uses the reply as the starting balance (true) or as the current balance (false). */
    static void requestBalance(boolean asStart) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        pending = asStart ? Pending.START : Pending.REFRESH;
        pendingUntil = System.currentTimeMillis() + 6000;
        lastBalReply = "(waiting for reply...)";
        mc.player.connection.sendCommand("bal");
    }

    static void setStart(double value, boolean alsoBalance) {
        start = value;
        if (alsoBalance) {
            balance = value;
            net = 0;
        }
        saved.put(user(), value);
        saveFile();
    }

    static void forgetStart() {
        start = null;
        saved.remove(user());
        saveFile();
    }

    static void resetSession() {
        net = 0;
        if (balance != null) setStart(balance, true);
    }

    static Double parseLoose(String input) {
        Matcher m = LOOSE.matcher(input.trim());
        if (!m.matches()) return null;
        try {
            return parse(m.group(1), m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Puts two fake payment lines in chat so you can see if the colouring works. */
    static void sendTest() {
        Minecraft mc = Minecraft.getInstance();
        forceActive = true;
        suppress = true;
        try {
            mc.gui.getChat().addMessage(Component.literal("TestUser paid you $1.5B"));
            mc.gui.getChat().addMessage(Component.literal("You paid TestUser $250M"));
        } finally {
            forceActive = false;
            suppress = false;
        }
    }

    // ---- chat handling --------------------------------------------------
    /** Called for every chat line (by the mixin and the event). Returns the line to display. */
    public static Component handle(Component original) {
        Minecraft mc = Minecraft.getInstance();
        if (!active(mc)) return original;

        String s = original.getString();
        long now = System.currentTimeMillis();
        if (lastOut != null && s.equals(lastText) && now - lastTime < 250) {
            return lastOut; // same line already handled a moment ago
        }
        Component out = analyse(original, s);
        lastText = s;
        lastTime = now;
        lastOut = out;
        return out;
    }

    private static Component analyse(Component original, String s) {
        lastAny = s;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.contains(" paid ") || low.contains(" sent ")) {
            lastPaid = s;
            lastPaidMatched = false;
        }

        Matcher m = RECEIVED.matcher(s);
        if (m.find()) {
            lastPaid = s;
            lastPaidMatched = true;
            double amount = parse(m.group(2), m.group(3));
            if (!suppress) {
                net += amount;
                if (balance != null) balance += amount;
            }
            return recolor(original, s, m, ORANGE, GREEN);
        }

        m = SENT.matcher(s);
        if (m.find()) {
            lastPaid = s;
            lastPaidMatched = true;
            double amount = parse(m.group(2), m.group(3));
            if (!suppress) {
                net -= amount;
                if (balance != null) balance -= amount;
            }
            return recolor(original, s, m, -1, RED);
        }

        if (pending != Pending.NONE) {
            if (System.currentTimeMillis() > pendingUntil) {
                pending = Pending.NONE;
            } else if (low.contains("bal") || s.contains("$")) {
                Matcher b = BAL.matcher(s);
                if (b.find()) {
                    double value = parse(b.group(1), b.group(2));
                    lastBalReply = s;
                    if (pending == Pending.START) setStart(value, true);
                    else balance = value;
                    pending = Pending.NONE;
                }
            }
        }
        return original;
    }

    /** Recolors the name (group 1) and the amount, keeping the rest of the line's original styling. */
    private static Component recolor(Component original, String s, Matcher m, int nameColor, int moneyColor) {
        final int nameS = m.start(1), nameE = m.end(1);
        int digits = m.start(2);
        final int moneyS = (digits > 0 && s.charAt(digits - 1) == '$') ? digits - 1 : digits;
        final int moneyE = m.end(3) >= 0 ? m.end(3) : m.end(2);

        MutableComponent out = Component.empty();
        int[] pos = {0};
        original.<Object>visit((style, text) -> {
            int segStart = pos[0];
            int segEnd = segStart + text.length();
            pos[0] = segEnd;
            int cur = segStart;
            while (cur < segEnd) {
                int color = -1;
                int next = segEnd;
                if (cur >= nameS && cur < nameE) {
                    color = nameColor;
                    next = Math.min(next, nameE);
                } else if (cur >= moneyS && cur < moneyE) {
                    color = moneyColor;
                    next = Math.min(next, moneyE);
                } else {
                    if (nameS > cur) next = Math.min(next, nameS);
                    if (moneyS > cur) next = Math.min(next, moneyS);
                }
                Style st = color >= 0 ? style.withColor(color) : style;
                out.append(Component.literal(text.substring(cur - segStart, next - segStart)).withStyle(st));
                cur = next;
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    // ---- saving ---------------------------------------------------------
    private static void loadFile() {
        try {
            if (Files.exists(FILE)) {
                Type type = new TypeToken<Map<String, Double>>() {}.getType();
                Map<String, Double> m = GSON.fromJson(Files.readString(FILE), type);
                if (m != null) saved = m;
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveFile() {
        try {
            Files.writeString(FILE, GSON.toJson(saved));
        } catch (IOException ignored) {
        }
    }

    // ---- number helpers -------------------------------------------------
    static double parse(String number, String suffix) {
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

    static String format(double v) {
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
