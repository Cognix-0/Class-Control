package com.classcontrol.ui.app;

import com.classcontrol.ui.api.Status;

import java.awt.Color;
import java.awt.Font;

public final class Theme {

    private Theme() {}

    public static final Color PAGE_BG = new Color(0xF2, 0xF4, 0xF8);
    public static final Color CARD_BG = Color.WHITE;
    public static final Color BORDER = new Color(0xE1, 0xE4, 0xEA);

    public static final Color APP_BAR_BG = new Color(0x18, 0x23, 0x3A);
    public static final Color APP_BAR_FG = Color.WHITE;

    public static final Color ACCENT = new Color(0x2F, 0x6F, 0xED);
    public static final Color ACCENT_HOVER = new Color(0x27, 0x5C, 0xC8);

    public static final Color TEXT_PRIMARY = new Color(0x1C, 0x21, 0x2B);
    public static final Color TEXT_SECONDARY = new Color(0x6B, 0x72, 0x80);

    public static final Color DANGER = new Color(0xDC, 0x3C, 0x3C);
    public static final Color DANGER_HOVER = new Color(0xBE, 0x2E, 0x2E);

    public static final Color NEUTRAL_BUTTON = new Color(0xEC, 0xEE, 0xF2);
    public static final Color NEUTRAL_BUTTON_HOVER = new Color(0xDD, 0xE1, 0xE8);

    public static final Color STATUS_ONLINE = new Color(0x27, 0xAE, 0x60);
    public static final Color STATUS_OFFLINE = new Color(0x9A, 0xA1, 0xAC);
    public static final Color STATUS_LOCKED = new Color(0xE6, 0x9A, 0x0E);
    public static final Color STATUS_NOT_RESPONDING = new Color(0xDC, 0x3C, 0x3C);

    public static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 18);
    public static final Font FONT_SECTION = new Font("Segoe UI", Font.BOLD, 13);
    public static final Font FONT_BASE = new Font("Segoe UI", Font.PLAIN, 13);
    public static final Font FONT_SMALL = new Font("Segoe UI", Font.PLAIN, 11);
    public static final Font FONT_MONO = new Font("Consolas", Font.PLAIN, 12);

    public static Color statusColor(Status status) {
        return switch (status) {
            case ONLINE -> STATUS_ONLINE;
            case OFFLINE -> STATUS_OFFLINE;
            case LOCKED -> STATUS_LOCKED;
            case NOT_RESPONDING -> STATUS_NOT_RESPONDING;
        };
    }
}
