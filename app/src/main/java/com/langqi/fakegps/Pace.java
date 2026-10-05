package com.langqi.fakegps;

import java.util.Locale;

final class Pace {
    private Pace() { }

    static int parse(String text) {
        String value = text.trim();
        double seconds;
        if (value.contains(":")) {
            if (!value.matches("\\d{1,5}:[0-5]\\d")) {
                throw new IllegalArgumentException("请按 分:秒 输入配速，例如 5:33");
            }
            String[] parts = value.split(":");
            seconds = Integer.parseInt(parts[0]) * 60.0 + Integer.parseInt(parts[1]);
        } else {
            // Accept old decimal-minute values when restoring an existing configuration.
            seconds = Double.parseDouble(value) * 60;
        }
        if (!Double.isFinite(seconds) || seconds < 1 || seconds > 599999) {
            throw new IllegalArgumentException("请输入有效配速");
        }
        return (int) Math.round(seconds);
    }

    static String format(int seconds) {
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
