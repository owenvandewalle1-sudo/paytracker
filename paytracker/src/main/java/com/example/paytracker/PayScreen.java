package com.example.paytracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The Pay Tracker menu (open it with the keybind from Options > Controls). */
public class PayScreen extends Screen {

    private EditBox manual;

    public PayScreen() {
        super(Component.literal("Pay Tracker"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        addRenderableWidget(Button.builder(Component.literal("Load starting balance (/bal)"),
                b -> PayTracker.requestBalance(true)).bounds(cx - 100, 96, 200, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Refresh current balance"),
                b -> PayTracker.requestBalance(false)).bounds(cx - 100, 118, 200, 20).build());

        manual = new EditBox(this.font, cx - 100, 140, 124, 20, Component.literal("Start balance"));
        manual.setHint(Component.literal("type e.g. 1.5B"));
        addRenderableWidget(manual);
        addRenderableWidget(Button.builder(Component.literal("Set start"), b -> {
            Double v = PayTracker.parseLoose(manual.getValue());
            if (v != null) PayTracker.setStart(v, false);
        }).bounds(cx + 28, 140, 72, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Send test chat colours"),
                b -> PayTracker.sendTest()).bounds(cx - 100, 162, 200, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Forget saved start"),
                b -> PayTracker.forgetStart()).bounds(cx - 100, 184, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.onClose()).bounds(cx + 2, 184, 98, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Minecraft mc = Minecraft.getInstance();
        int cx = this.width / 2;

        line(g, "Pay Tracker", cx, 10, 0xFFFFD27F);

        String ip = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
        boolean donut = PayTracker.onDonut(mc);
        line(g, "Account: " + PayTracker.user(), cx, 26, 0xFFFFFFFF);
        line(g, "Server: " + ip + (donut ? "  (DonutSMP detected)" : "  (not DonutSMP - mod is off)"),
                cx, 37, donut ? 0xFF55FF55 : 0xFFFF5555);
        line(g, "Starting balance: " + (PayTracker.start == null ? "not set"
                : "$" + PayTracker.format(PayTracker.start)), cx, 48, 0xFFFFFFFF);
        line(g, "Current balance: " + (PayTracker.balance == null ? "unknown (press Refresh)"
                : "$" + PayTracker.format(PayTracker.balance)), cx, 59, 0xFFFFFFFF);

        double p = PayTracker.profit();
        line(g, "Profit: " + (p >= 0 ? "+" : "-") + "$" + PayTracker.format(Math.abs(p)), cx, 72,
                p > 0 ? 0xFF55FF55 : p < 0 ? 0xFFFF5555 : 0xFFFFFFFF);

        if (PayTracker.pending != PayTracker.Pending.NONE) {
            line(g, "Waiting for the /bal reply...", cx, 84, 0xFFFFD27F);
        }

        int dy = Math.max(210, this.height - 60);
        line(g, "Last /bal reply: " + cut(PayTracker.lastBalReply), cx, dy, 0xFF888888);
        line(g, "Last 'paid' line: " + cut(PayTracker.lastPaid)
                + (PayTracker.lastPaid.isEmpty() ? "" : (PayTracker.lastPaidMatched ? "  [matched]" : "  [NOT matched]")),
                cx, dy + 11, 0xFF888888);
        line(g, "Last chat line seen: " + cut(PayTracker.lastAny), cx, dy + 22, 0xFF888888);
    }

    private void line(GuiGraphics g, String s, int cx, int y, int color) {
        g.drawString(this.font, s, cx - this.font.width(s) / 2, y, color, true);
    }

    private static String cut(String s) {
        if (s == null || s.isEmpty()) return "-";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
