package com.marcuspage.jewels;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Flash Columns. A stack of jewels fills the bottom of the well and one jewel
 * flashes; clear it as fast as you can, then the next round stacks higher.
 */
public class GameView extends View {

    private enum State { TITLE, PLAYING, ROUND_CLEAR, GAME_OVER, PAUSED }

    private static final int COLS = Board.COLS;
    private static final int ROWS = Board.ROWS;

    private static final int[] CLASS_COLORS = {4, 5, 6};
    private static final String[] CLASS_NAMES = {"NOVICE", "AMATEUR", "PRO"};
    private static final int[] STACK_HEIGHTS = {3, 4, 5, 6, 7, 8};

    private static final long POP_MS = 320L;

    private final Board board = new Board();
    private final Random rnd = new Random();
    private final SoundFx sfx;
    private final SharedPreferences prefs;

    private State state = State.TITLE;

    private int classIndex = 1;
    private int heightIndex = 1;

    // Run state.
    private int round = 1;
    private int level = 1;
    private int roundsCleared;
    private long roundElapsedMs;
    private long totalElapsedMs;
    private long lastRoundMs;
    private float dropAccumMs;
    private float dropIntervalMs = 640f;

    // Falling column.
    private final int[] pieceColors = new int[3];
    private final int[] nextColors = new int[3];
    private int pieceX;
    private int pieceY;
    private boolean hasPiece;

    // Bests.
    private int bestRounds;
    private long bestRoundMs;

    // Layout.
    private float w, h;
    private float cell, boardLeft, boardTop, boardW, boardH, hudH;
    private final RectF chipClass = new RectF();
    private final RectF chipStack = new RectF();
    private final RectF chipSound = new RectF();
    private final RectF chipExit = new RectF();
    private final RectF btnPlay = new RectF();
    private final RectF dst = new RectF();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap[] gems = new Bitmap[GemPalette.COUNT + 1];
    private int gemSize;

    private Typeface tfThin;
    private Typeface tfLight;
    private Typeface tfBold;

    private static class Pop {
        final float col, row;
        final int color;
        final long start;

        Pop(float col, float row, int color, long start) {
            this.col = col;
            this.row = row;
            this.color = color;
            this.start = start;
        }
    }

    private final List<Pop> pops = new ArrayList<>();

    // Touch tracking.
    private float downX, downY, lastMoveX, lastMoveY;
    private long downTime;
    private boolean flickUsed;

    private long lastFrameNanos;

    public GameView(Context context) {
        super(context);
        prefs = context.getSharedPreferences("jewels", Context.MODE_PRIVATE);
        sfx = new SoundFx(context, prefs.getBoolean("muted", false));
        bestRounds = prefs.getInt("best_rounds", 0);
        bestRoundMs = prefs.getLong("best_round_ms", 0L);

        tfThin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        tfBold = Typeface.create("sans-serif-medium", Typeface.BOLD);

        paint.setFilterBitmap(true);
        text.setAntiAlias(true);
        setBackgroundColor(0xFF060A14);
        setFocusable(true);
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        w = nw;
        h = nh;
        layoutBoard();
        layoutTitleUi();
        buildGems();
    }

    private void layoutBoard() {
        hudH = h * 0.155f;
        float footer = h * 0.055f;
        float avail = h - hudH - footer;
        float margin = w * 0.045f;
        cell = Math.min((w - 2 * margin) / COLS, avail / ROWS);
        boardW = cell * COLS;
        boardH = cell * ROWS;
        boardLeft = (w - boardW) / 2f;
        boardTop = hudH + (avail - boardH) / 2f;
    }

    private void layoutTitleUi() {
        float cx = w / 2f;
        float chipW = Math.min(w * 0.74f, h * 0.52f);
        float chipH = Math.max(50f, h * 0.072f);
        float y0 = h * 0.44f;
        chipClass.set(cx - chipW / 2f, y0, cx + chipW / 2f, y0 + chipH);
        chipStack.set(cx - chipW / 2f, y0 + chipH * 1.22f, cx + chipW / 2f, y0 + chipH * 2.22f);
        btnPlay.set(cx - chipW / 2f, y0 + chipH * 2.85f, cx + chipW / 2f, y0 + chipH * 4.05f);

        // Sound and Exit share one row so the footer stays clear of them.
        float pillH = chipH * 0.80f;
        float gap = chipH * 0.35f;
        float halfW = (chipW - gap) / 2f;
        float pillY = y0 + chipH * 4.45f;
        chipSound.set(cx - chipW / 2f, pillY, cx - chipW / 2f + halfW, pillY + pillH);
        chipExit.set(cx + chipW / 2f - halfW, pillY, cx + chipW / 2f, pillY + pillH);
    }

    private void buildGems() {
        int size = Math.max(16, Math.round(cell));
        if (size == gemSize) return;
        gemSize = size;
        for (int i = 1; i <= GemPalette.COUNT; i++) {
            gems[i] = makeGem(GemPalette.BASE[i], size);
        }
    }

    /** Pre-renders one faceted jewel so the board draws with plain bitmaps. */
    private Bitmap makeGem(int color, int size) {
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        float r = size * 0.46f;
        float cx = size / 2f;
        float cy = size / 2f;
        int light = GemPalette.lighten(color, 0.55f);
        int dark = GemPalette.darken(color, 0.55f);
        int deep = GemPalette.darken(color, 0.75f);

        Path body = gemPath(cx, cy, r);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Soft shadow under the stone.
        p.setColor(0x59000000);
        c.save();
        c.translate(0, size * 0.045f);
        c.drawPath(body, p);
        c.restore();

        // Body with a light source at the upper left.
        p.setShader(new RadialGradient(cx - r * 0.34f, cy - r * 0.42f, r * 1.9f,
                new int[]{light, color, dark}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(body, p);
        p.setShader(null);

        // Table facet catching the light.
        Path table = new Path();
        table.moveTo(cx - r * 0.52f, cy - r * 0.30f);
        table.lineTo(cx + r * 0.10f, cy - r * 0.62f);
        table.lineTo(cx + r * 0.42f, cy - r * 0.10f);
        table.lineTo(cx - r * 0.18f, cy + r * 0.02f);
        table.close();
        p.setColor(0x4DFFFFFF);
        c.drawPath(table, p);

        // Rim.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, size * 0.035f));
        p.setColor(deep);
        c.drawPath(body, p);
        p.setStyle(Paint.Style.FILL);

        // Specular dot.
        p.setColor(0xCCFFFFFF);
        c.drawCircle(cx - r * 0.30f, cy - r * 0.38f, r * 0.13f, p);

        return bm;
    }

    private static Path gemPath(float cx, float cy, float r) {
        Path p = new Path();
        p.moveTo(cx, cy - r);                       // top
        p.lineTo(cx + r * 0.78f, cy - r * 0.46f);   // upper right
        p.lineTo(cx + r * 0.88f, cy + r * 0.18f);   // lower right
        p.lineTo(cx, cy + r);                       // bottom
        p.lineTo(cx - r * 0.88f, cy + r * 0.18f);   // lower left
        p.lineTo(cx - r * 0.78f, cy - r * 0.46f);   // upper left
        p.close();
        return p;
    }

    private float s(float fraction) {
        return h * fraction;
    }

    // ------------------------------------------------------------------ loop

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        if (dt > 0.1f) dt = 0.1f;

        update(dt);
        render(canvas);
        postInvalidateOnAnimation();
    }

    private void update(float dt) {
        if (state == State.PLAYING) {
            roundElapsedMs += (long) (dt * 1000f);
            totalElapsedMs += (long) (dt * 1000f);
            dropAccumMs += dt * 1000f;
            if (dropAccumMs >= dropIntervalMs) {
                dropAccumMs = 0f;
                stepDown();
            }
        }
        long now = System.nanoTime();
        Iterator<Pop> it = pops.iterator();
        while (it.hasNext()) {
            if ((now - it.next().start) / 1_000_000L > POP_MS) it.remove();
        }
    }

    // ---------------------------------------------------------------- action

    private void startRun() {
        roundsCleared = 0;
        totalElapsedMs = 0L;
        round = 1;
        level = 1;
        startRound();
    }

    private void startRound() {
        roundElapsedMs = 0L;
        dropAccumMs = 0f;
        pops.clear();

        int colorCount = CLASS_COLORS[classIndex];
        int baseHeight = STACK_HEIGHTS[heightIndex];
        int height = Math.min(ROWS - 3, baseHeight + (round - 1) / 2);

        board.generateStack(height, colorCount, rnd);
        board.placeFlashJewel(colorCount, rnd);

        randomize(nextColors);
        spawnPiece();

        dropIntervalMs = Math.max(115f, 640f - (level - 1) * 55f);
        state = State.PLAYING;
    }

    private void randomize(int[] into) {
        int colorCount = CLASS_COLORS[classIndex];
        for (int i = 0; i < 3; i++) into[i] = 1 + rnd.nextInt(colorCount);
    }

    private void spawnPiece() {
        System.arraycopy(nextColors, 0, pieceColors, 0, 3);
        randomize(nextColors);
        pieceX = COLS / 2 - 1;
        pieceY = 0;
        dropAccumMs = 0f;
        hasPiece = true;
        if (!board.canPlace(pieceX, pieceY)) {
            hasPiece = false;
            gameOver();
        }
    }

    private void stepDown() {
        if (!hasPiece) return;
        if (board.canPlace(pieceX, pieceY + 1)) {
            pieceY++;
        } else {
            lockPiece();
        }
    }

    private void move(int dx) {
        if (!hasPiece) return;
        if (board.canPlace(pieceX + dx, pieceY)) {
            pieceX += dx;
            sfx.play(SoundFx.MOVE);
        }
    }

    private void rotate() {
        if (!hasPiece) return;
        int tmp = pieceColors[2];
        pieceColors[2] = pieceColors[1];
        pieceColors[1] = pieceColors[0];
        pieceColors[0] = tmp;
        sfx.play(SoundFx.ROTATE);
    }

    private void softDrop() {
        if (!hasPiece) return;
        if (board.canPlace(pieceX, pieceY + 1)) {
            pieceY++;
            dropAccumMs = 0f;
        }
    }

    private void hardDrop() {
        if (!hasPiece) return;
        while (board.canPlace(pieceX, pieceY + 1)) pieceY++;
        sfx.play(SoundFx.DROP);
        lockPiece();
    }

    private int landingY() {
        int y = pieceY;
        while (board.canPlace(pieceX, y + 1)) y++;
        return y;
    }

    private void lockPiece() {
        board.lock(pieceX, pieceY, pieceColors);
        hasPiece = false;
        Board.ClearResult res = board.resolve();
        if (res.count > 0) {
            addPops(res);
            sfx.play(SoundFx.CLEAR);
        }
        if (res.flashCleared) {
            finishRound();
            return;
        }
        spawnPiece();
    }

    private void addPops(Board.ClearResult res) {
        long now = System.nanoTime();
        for (int[] cell : res.cells) {
            pops.add(new Pop(cell[1], cell[0], cell[2], now));
        }
    }

    private void finishRound() {
        lastRoundMs = roundElapsedMs;
        roundsCleared++;
        state = State.ROUND_CLEAR;
        hasPiece = false;

        boolean changed = false;
        if (bestRoundMs == 0L || lastRoundMs < bestRoundMs) {
            bestRoundMs = lastRoundMs;
            prefs.edit().putLong("best_round_ms", bestRoundMs).apply();
            changed = true;
        }
        if (roundsCleared > bestRounds) {
            bestRounds = roundsCleared;
            prefs.edit().putInt("best_rounds", bestRounds).apply();
            changed = true;
        }
        if (changed) prefs.edit().apply();
        sfx.play(SoundFx.WIN);
    }

    private void nextRound() {
        round++;
        level++;
        startRound();
    }

    private void gameOver() {
        state = State.GAME_OVER;
        hasPiece = false;
        if (roundsCleared > bestRounds) {
            bestRounds = roundsCleared;
            prefs.edit().putInt("best_rounds", bestRounds).apply();
        }
        sfx.play(SoundFx.OVER);
    }

    // -------------------------------------------------------------- rendering

    private void render(Canvas c) {
        drawBackdrop(c);
        if (state == State.TITLE) {
            drawTitle(c);
            return;
        }
        drawHud(c);
        drawBoardPanel(c);
        drawJewels(c);
        drawPops(c);
        drawGhost(c);
        drawPiece(c);

        if (state == State.ROUND_CLEAR) {
            drawCard(c, "ROUND CLEARED", formatSeconds(lastRoundMs),
                    "ROUND " + (round + 1) + " IS STACKING HIGHER", "TAP TO CONTINUE");
        } else if (state == State.GAME_OVER) {
            drawCard(c, "STACK OVERFLOW", roundsCleared + (roundsCleared == 1 ? " ROUND" : " ROUNDS"),
                    "TOTAL TIME " + formatClock(totalElapsedMs), "TAP TO RETURN");
        } else if (state == State.PAUSED) {
            drawCard(c, "PAUSED", "—", "ROUND " + round, "TAP TO RESUME");
        }
    }

    private void drawBackdrop(Canvas c) {
        paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF0C1428, 0xFF04070E, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        paint.setShader(new RadialGradient(w * 0.5f, h * 0.06f, h * 0.55f,
                new int[]{0x2220C9D6, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.6f, paint);
        paint.setShader(null);
    }

    private void drawHud(Canvas c) {
        float pad = w * 0.055f;

        drawText(c, "ROUND", pad, hudH * 0.34f, s(0.022f), 0xFF7C8AAE, tfLight, 0.24f, Paint.Align.LEFT);
        drawText(c, String.valueOf(round), pad, hudH * 0.78f, s(0.062f), 0xFFFFFFFF, tfBold, 0f, Paint.Align.LEFT);

        drawText(c, formatSeconds(roundElapsedMs), w / 2f, hudH * 0.64f, s(0.105f),
                0xFFEFF6FF, tfThin, 0.02f, Paint.Align.CENTER);
        drawText(c, "SECONDS", w / 2f, hudH * 0.87f, s(0.020f), 0xFF6C7A9C, tfLight, 0.30f, Paint.Align.CENTER);

        float mini = cell * 0.50f;
        drawText(c, "NEXT", w - pad, hudH * 0.20f, s(0.020f), 0xFF7C8AAE, tfLight, 0.24f, Paint.Align.RIGHT);
        for (int i = 0; i < 3; i++) {
            drawGem(c, gems[nextColors[i]], w - pad - mini, hudH * 0.26f + i * mini * 0.95f, mini, 255);
        }

        if (bestRounds > 0) {
            drawText(c, "BEST " + bestRounds, pad, hudH * 0.98f, s(0.020f), 0xFF4E5C7C, tfLight, 0.16f, Paint.Align.LEFT);
        }
    }

    private void drawBoardPanel(Canvas c) {
        RectF r = new RectF(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH);
        float rad = cell * 0.35f;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, boardTop, 0, boardTop + boardH, 0xFF101A2E, 0xFF090F1C, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, cell * 0.018f));
        paint.setColor(0x12FFFFFF);
        for (int i = 1; i < COLS; i++) {
            float x = boardLeft + i * cell;
            c.drawLine(x, boardTop + rad * 0.4f, x, boardTop + boardH - rad * 0.4f, paint);
        }
        for (int i = 1; i < ROWS; i++) {
            float y = boardTop + i * cell;
            c.drawLine(boardLeft + rad * 0.4f, y, boardLeft + boardW - rad * 0.4f, y, paint);
        }

        paint.setStrokeWidth(Math.max(2f, cell * 0.04f));
        paint.setColor(0xFF24325A);
        c.drawRoundRect(r, rad, rad, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawJewels(Canvas c) {
        for (int r = 0; r < ROWS; r++) {
            for (int col = 0; col < COLS; col++) {
                int v = board.cells[r][col];
                if (v == 0) continue;
                float left = boardLeft + col * cell;
                float top = boardTop + r * cell;
                drawGem(c, gems[v], left, top, cell, 255);
                if (board.flash[r][col]) drawFlash(c, left, top);
            }
        }
    }

    private void drawFlash(Canvas c, float left, float top) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 1_000_000_000.0 * 6.0);
        float cx = left + cell / 2f;
        float cy = top + cell / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha((int) (25 + 70 * pulse));
        c.drawCircle(cx, cy, cell * 0.47f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(cell * 0.085f);
        paint.setAlpha((int) (60 + 165 * pulse));
        c.drawCircle(cx, cy, cell * 0.40f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
    }

    private void drawPiece(Canvas c) {
        if (!hasPiece) return;
        paint.setColor(0x0EFFFFFF);
        c.drawRect(boardLeft + pieceX * cell, boardTop, boardLeft + (pieceX + 1) * cell, boardTop + boardH, paint);
        for (int i = 0; i < 3; i++) {
            int r = pieceY + i;
            if (r < 0 || r >= ROWS) continue;
            drawGem(c, gems[pieceColors[i]], boardLeft + pieceX * cell, boardTop + r * cell, cell, 255);
        }
    }

    private void drawGhost(Canvas c) {
        if (!hasPiece) return;
        int ly = landingY();
        if (ly == pieceY) return;
        for (int i = 0; i < 3; i++) {
            int r = ly + i;
            if (r >= ROWS) continue;
            drawGem(c, gems[pieceColors[i]], boardLeft + pieceX * cell, boardTop + r * cell, cell, 42);
        }
    }

    private void drawPops(Canvas c) {
        long now = System.nanoTime();
        for (Pop p : pops) {
            float t = (now - p.start) / (POP_MS * 1_000_000f);
            if (t < 0f) t = 0f;
            if (t > 1f) continue;
            float cx = boardLeft + (p.col + 0.5f) * cell;
            float cy = boardTop + (p.row + 0.5f) * cell;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(cell * 0.10f * (1f - t) + 1f);
            paint.setColor(GemPalette.lighten(GemPalette.BASE[p.color], 0.45f));
            paint.setAlpha((int) (225 * (1f - t)));
            c.drawCircle(cx, cy, cell * (0.16f + 0.58f * t), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
        }
    }

    private void drawGem(Canvas c, Bitmap bm, float left, float top, float size, int alpha) {
        if (bm == null) return;
        dst.set(left, top, left + size, top + size);
        paint.setAlpha(alpha);
        c.drawBitmap(bm, null, dst, paint);
        paint.setAlpha(255);
    }

    // ------------------------------------------------------------------ title

    private void drawTitle(Canvas c) {
        float cx = w / 2f;

        paint.setShader(new RadialGradient(cx, h * 0.18f, w * 0.8f,
                new int[]{0x3322D3B0, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, h * 0.18f, w * 0.8f, paint);
        paint.setShader(null);

        drawText(c, "JEWELS", cx, h * 0.205f, Math.min(w * 0.235f, h * 0.105f), 0xFFFFFFFF, tfBold, 0.14f, Paint.Align.CENTER);
        drawText(c, "FLASH COLUMNS", cx, h * 0.252f, Math.min(w * 0.046f, h * 0.023f), 0xFF7FE3E8, tfLight, 0.36f, Paint.Align.CENTER);

        // A row of jewels under the title.
        float g = Math.min(cell * 0.78f, w * 0.115f);
        float total = g * 6f;
        float gx = cx - total / 2f;
        float gy = h * 0.315f;
        for (int i = 0; i < 6; i++) {
            drawGem(c, gems[i + 1], gx + i * g, gy, g, 235);
        }

        drawChip(c, chipClass, "CLASS", CLASS_NAMES[classIndex] + "  " + CLASS_COLORS[classIndex]);
        drawChip(c, chipStack, "STACK", STACK_HEIGHTS[heightIndex] + " ROWS");

        paint.setShader(new LinearGradient(btnPlay.left, btnPlay.top, btnPlay.right, btnPlay.bottom,
                0xFF4FE3A8, 0xFF17A67A, Shader.TileMode.CLAMP));
        c.drawRoundRect(btnPlay, btnPlay.height() * 0.42f, btnPlay.height() * 0.42f, paint);
        paint.setShader(null);
        drawText(c, "PLAY", btnPlay.centerX(), btnPlay.centerY() + btnPlay.height() * 0.155f,
                btnPlay.height() * 0.40f, 0xFF04231A, tfBold, 0.18f, Paint.Align.CENTER);

        drawPill(c, chipSound, "SOUND  " + (sfx.isMuted() ? "OFF" : "ON"), 0xFFA9B7D6);
        drawPill(c, chipExit, "EXIT", 0xFFFF9A9A);

        if (bestRounds > 0) {
            drawText(c, "BEST RUN   ROUND " + bestRounds, cx, chipSound.bottom + s(0.055f),
                    s(0.024f), 0xFF8FA0C4, tfLight, 0.14f, Paint.Align.CENTER);
            if (bestRoundMs > 0L) {
                drawText(c, "FASTEST ROUND   " + formatSeconds(bestRoundMs), cx, chipSound.bottom + s(0.088f),
                        s(0.024f), 0xFF8FA0C4, tfLight, 0.14f, Paint.Align.CENTER);
            }
        }

        drawTextFitted(c, "SWIPE TO MOVE   TAP TO ROTATE   SWIPE DOWN TO DROP", cx, h * 0.968f,
                s(0.0155f), 0xFF51608A, tfLight, 0.06f, w * 0.90f);
    }

    private void drawChip(Canvas c, RectF r, String label, String value) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF16203A);
        c.drawRoundRect(r, r.height() * 0.42f, r.height() * 0.42f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, cell * 0.02f));
        paint.setColor(0xFF2A3A5E);
        c.drawRoundRect(r, r.height() * 0.42f, r.height() * 0.42f, paint);
        paint.setStyle(Paint.Style.FILL);

        drawText(c, label, r.left + r.height() * 0.48f, r.centerY() + r.height() * 0.105f,
                r.height() * 0.30f, 0xFF7C8AAE, tfLight, 0.22f, Paint.Align.LEFT);
        drawText(c, value, r.right - r.height() * 0.48f, r.centerY() + r.height() * 0.125f,
                r.height() * 0.34f, 0xFFFFFFFF, tfBold, 0.04f, Paint.Align.RIGHT);
    }

    /** Single-label chip for the small actions on the title screen. */
    private void drawPill(Canvas c, RectF r, String label, int labelColor) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF16203A);
        c.drawRoundRect(r, r.height() * 0.42f, r.height() * 0.42f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, cell * 0.02f));
        paint.setColor(0xFF2A3A5E);
        c.drawRoundRect(r, r.height() * 0.42f, r.height() * 0.42f, paint);
        paint.setStyle(Paint.Style.FILL);

        drawText(c, label, r.centerX(), r.centerY() + r.height() * 0.135f,
                r.height() * 0.34f, labelColor, tfBold, 0.14f, Paint.Align.CENTER);
    }

    /** Centred text that shrinks to fit, so a long line can never run off screen. */
    private void drawTextFitted(Canvas c, String str, float x, float y, float size, int color,
                                Typeface tf, float spacing, float maxWidth) {
        text.setTypeface(tf);
        text.setTextSize(size);
        text.setLetterSpacing(spacing);
        float width = text.measureText(str);
        if (width > maxWidth && width > 0f) {
            text.setTextSize(size * (maxWidth / width));
        }
        text.setColor(color);
        text.setTextAlign(Paint.Align.CENTER);
        text.setStyle(Paint.Style.FILL);
        c.drawText(str, x, y, text);
        text.setLetterSpacing(0f);
    }

    private void drawCard(Canvas c, String title, String big, String sub, String hint) {
        paint.setColor(0xC2050A14);
        c.drawRect(0, 0, w, h, paint);

        float cw = w * 0.84f;
        float chh = h * 0.36f;
        RectF card = new RectF((w - cw) / 2f, (h - chh) / 2f, (w + cw) / 2f, (h + chh) / 2f);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, card.top, 0, card.bottom, 0xFF17223C, 0xFF0A1120, Shader.TileMode.CLAMP));
        c.drawRoundRect(card, cell * 0.7f, cell * 0.7f, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, cell * 0.035f));
        paint.setColor(0xFF2C3D66);
        c.drawRoundRect(card, cell * 0.7f, cell * 0.7f, paint);
        paint.setStyle(Paint.Style.FILL);

        float cx = w / 2f;
        drawText(c, title, cx, card.top + chh * 0.24f, s(0.024f), 0xFF7FE3E8, tfLight, 0.30f, Paint.Align.CENTER);
        drawText(c, big, cx, card.top + chh * 0.56f, s(0.095f), 0xFFFFFFFF, tfBold, 0.02f, Paint.Align.CENTER);
        drawText(c, sub, cx, card.top + chh * 0.74f, s(0.030f), 0xFFA9B7D6, tfLight, 0.04f, Paint.Align.CENTER);
        drawText(c, hint, cx, card.top + chh * 0.90f, s(0.023f), 0xFF6C7A9C, tfLight, 0.18f, Paint.Align.CENTER);
    }

    private void drawText(Canvas c, String str, float x, float y, float size, int color,
                          Typeface tf, float spacing, Paint.Align align) {
        text.setTypeface(tf);
        text.setTextSize(size);
        text.setColor(color);
        text.setLetterSpacing(spacing);
        text.setTextAlign(align);
        text.setStyle(Paint.Style.FILL);
        c.drawText(str, x, y, text);
        text.setLetterSpacing(0f);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                lastMoveX = x;
                lastMoveY = y;
                downTime = SystemClock.uptimeMillis();
                flickUsed = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (state == State.PLAYING && hasPiece) handleDrag(x, y);
                return true;

            case MotionEvent.ACTION_UP:
                handleRelease(x, y);
                performClick();
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void handleDrag(float x, float y) {
        float stepX = cell * 0.62f;
        while (x - lastMoveX > stepX) {
            move(1);
            lastMoveX += stepX;
        }
        while (lastMoveX - x > stepX) {
            move(-1);
            lastMoveX -= stepX;
        }

        long held = SystemClock.uptimeMillis() - downTime;
        if (!flickUsed && (y - downY) > cell * 2.4f && held < 240) {
            hardDrop();
            flickUsed = true;
            return;
        }

        float stepY = cell * 0.85f;
        while (y - lastMoveY > stepY) {
            softDrop();
            lastMoveY += stepY;
        }
    }

    private void handleRelease(float x, float y) {
        long held = SystemClock.uptimeMillis() - downTime;
        float dx = x - downX;
        float dy = y - downY;

        switch (state) {
            case TITLE:
                handleTitleTap(x, y);
                break;
            case ROUND_CLEAR:
                nextRound();
                break;
            case GAME_OVER:
                state = State.TITLE;
                board.clear();
                break;
            case PAUSED:
                state = State.PLAYING;
                break;
            case PLAYING:
                boolean still = Math.abs(dx) < cell * 0.45f && Math.abs(dy) < cell * 0.45f;
                if (!flickUsed && still && held < 320) rotate();
                break;
        }
    }

    private void handleTitleTap(float x, float y) {
        if (chipClass.contains(x, y)) {
            classIndex = (classIndex + 1) % CLASS_COLORS.length;
        } else if (chipStack.contains(x, y)) {
            heightIndex = (heightIndex + 1) % STACK_HEIGHTS.length;
        } else if (btnPlay.contains(x, y)) {
            startRun();
        } else if (chipSound.contains(x, y)) {
            boolean muted = !sfx.isMuted();
            sfx.setMuted(muted);
            prefs.edit().putBoolean("muted", muted).apply();
            if (!muted) sfx.play(SoundFx.ROTATE);
        } else if (chipExit.contains(x, y)) {
            exitApp();
        }
    }

    /** Leaves the app from the title screen. */
    private void exitApp() {
        Context context = getContext();
        if (context instanceof Activity) {
            ((Activity) context).finish();
        }
    }

    // -------------------------------------------------------------- lifecycle

    public void onHostPause() {
        if (state == State.PLAYING) {
            state = State.PAUSED;
        }
    }

    public void onHostResume() {
        lastFrameNanos = 0L;
    }

    public void onHostDestroy() {
        sfx.release();
    }

    // ------------------------------------------------------------------ time

    private String formatSeconds(long ms) {
        if (ms < 60000L) return String.format(Locale.US, "%.1f", ms / 1000f);
        long totalSec = ms / 1000L;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    private String formatClock(long ms) {
        long totalSec = ms / 1000L;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }
}
