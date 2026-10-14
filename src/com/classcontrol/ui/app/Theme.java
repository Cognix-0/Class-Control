package com.classcontrol.ui.app;

import com.classcontrol.ui.api.Status;

import java.awt.Color;
import java.awt.Font;

public final class Theme {

    private Theme() {}

    public static final Color PAGE_BG = new Color(0xFA, 0xFA, 0xFB);
    public static final Color CARD_BG = Color.WHITE;
    public static final Color BORDER = new Color(0xEB, 0xEB, 0xEF);
    public static final Color BORDER_STRONG = new Color(0xDC, 0xDC, 0xE3);

    public static final Color APP_BAR_BG = Color.WHITE;
    public static final Color APP_BAR_FG = new Color(0x1A, 0x1A, 0x1F);

    public static final Color ACCENT = new Color(0x5E, 0x6A, 0xD2);
    public static final Color ACCENT_HOVER = new Color(0x4E, 0x5A, 0xC2);
    public static final Color ACCENT_TINT = new Color(0x5E, 0x6A, 0xD2, 26);

    public static final Color TEXT_PRIMARY = new Color(0x1A, 0x1A, 0x1F);
    public static final Color TEXT_SECONDARY = new Color(0x75, 0x75, 0x7D);
    public static final Color TEXT_MUTED = new Color(0xA0, 0xA0, 0xA8);

    public static final Color DANGER = new Color(0xE5, 0x48, 0x4D);
    public static final Color DANGER_HOVER = new Color(0xD1, 0x3B, 0x40);
    public static final Color DANGER_TINT = new Color(0xE5, 0x48, 0x4D, 26);

    public static final Color NEUTRAL_BUTTON = Color.WHITE;
    public static final Color NEUTRAL_BUTTON_HOVER = new Color(0xF4, 0xF4, 0xF6);

    public static final Color STATUS_ONLINE = new Color(0x30, 0xA4, 0x6C);
    public static final Color STATUS_OFFLINE = new Color(0x8B, 0x8D, 0x98);
    public static final Color STATUS_LOCKED = new Color(0xF5, 0xA5, 0x24);
    public static final Color STATUS_NOT_RESPONDING = new Color(0xE5, 0x48, 0x4D);

    public static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 20);
    public static final Font FONT_SECTION = new Font("Segoe UI", Font.BOLD, 13);
    public static final Font FONT_BASE = new Font("Segoe UI", Font.PLAIN, 13);
    public static final Font FONT_SMALL = new Font("Segoe UI", Font.PLAIN, 11);
    public static final Font FONT_SMALL_BOLD = new Font("Segoe UI", Font.BOLD, 11);
    public static final Font FONT_MONO = new Font("Consolas", Font.PLAIN, 12);

    public static Color statusColor(Status status) {
        return switch (status) {
            case ONLINE -> STATUS_ONLINE;
            case OFFLINE -> STATUS_OFFLINE;
            case LOCKED -> STATUS_LOCKED;
            case NOT_RESPONDING -> STATUS_NOT_RESPONDING;
        };
    }

    public static Color statusTint(Status status) {
        Color c = statusColor(status);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), 28);
    }
}
