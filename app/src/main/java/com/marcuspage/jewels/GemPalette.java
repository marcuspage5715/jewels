package com.marcuspage.jewels;

/** The six jewel colours, derived light and dark shades for the gem shading. */
public final class GemPalette {

    public static final int COUNT = 6;

    /** Index 0 is unused so a board cell value maps straight to a colour. */
    public static final int[] BASE = {
            0,
            0xFFE53950, // ruby
            0xFF2E7BF6, // sapphire
            0xFF2ECC71, // emerald
            0xFFF2C230, // topaz
            0xFFA24BF5, // amethyst
            0xFF20C9D6  // aqua
    };

    private GemPalette() {
    }

    public static int lighten(int color, float amount) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) (r + (255 - r) * amount);
        g = (int) (g + (255 - g) * amount);
        b = (int) (b + (255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static int darken(int color, float amount) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) (r * (1f - amount));
        g = (int) (g * (1f - amount));
        b = (int) (b * (1f - amount));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
