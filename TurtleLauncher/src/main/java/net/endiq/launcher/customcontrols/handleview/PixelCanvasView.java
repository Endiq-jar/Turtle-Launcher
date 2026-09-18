package net.endiq.launcher.customcontrols.handleview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * TurtleLauncher: a small pixel-art canvas for drawing custom control-button icons directly
 * in-app, as an alternative to picking an existing image file. Fixed at a modest GRID_SIZE -
 * this is meant for small button icons (in the same spirit as the turtle launcher icon itself,
 * a 32x32-ish sprite scaled up), not a general-purpose art tool, so there's no zoom/pan/layer
 * system here - just a flat grid of ARGB pixels, a handful of tools, and bounded undo.
 */
public class PixelCanvasView extends View {
    public static final int GRID_SIZE = 32;
    private static final int MAX_UNDO_DEPTH = 20;

    public enum Tool { PENCIL, ERASER, FILL }

    private final int[] mPixels = new int[GRID_SIZE * GRID_SIZE];
    private final Deque<int[]> mUndoStack = new ArrayDeque<>();

    private int mCurrentColor = Color.BLACK;
    private Tool mCurrentTool = Tool.PENCIL;
    private boolean mGestureSnapshotTaken = false;
    private int mLastTouchedCell = -1;

    private final Paint mCheckerPaintLight = new Paint();
    private final Paint mCheckerPaintDark = new Paint();
    private final Paint mPixelPaint = new Paint();
    private final Paint mGridLinePaint = new Paint();
    private final RectF mCellRect = new RectF();

    private OnPixelChangedListener mListener;

    public interface OnPixelChangedListener {
        void onPixelChanged();
    }

    public PixelCanvasView(Context context) {
        super(context);
        init();
    }

    public PixelCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        Arrays.fill(mPixels, Color.TRANSPARENT);
        mCheckerPaintLight.setColor(0xFFCCCCCC);
        mCheckerPaintDark.setColor(0xFFAAAAAA);
        mGridLinePaint.setColor(0x22000000);
        mGridLinePaint.setStrokeWidth(1f);
        setClickable(true);
    }

    public void setOnPixelChangedListener(OnPixelChangedListener listener) {
        mListener = listener;
    }

    public void setCurrentColor(int color) {
        mCurrentColor = color;
    }

    public void setCurrentTool(Tool tool) {
        mCurrentTool = tool;
    }

    /** Loads an existing image into the grid, nearest-neighbour scaled to GRID_SIZE x GRID_SIZE. */
    public void loadBitmap(Bitmap bitmap) {
        if (bitmap == null) return;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, GRID_SIZE, GRID_SIZE, false);
        pushUndoSnapshot();
        scaled.getPixels(mPixels, 0, GRID_SIZE, 0, 0, GRID_SIZE, GRID_SIZE);
        if (scaled != bitmap) scaled.recycle();
        invalidate();
        notifyChanged();
    }

    public void clear() {
        pushUndoSnapshot();
        Arrays.fill(mPixels, Color.TRANSPARENT);
        invalidate();
        notifyChanged();
    }

    public boolean undo() {
        int[] previous = mUndoStack.pollLast();
        if (previous == null) return false;
        System.arraycopy(previous, 0, mPixels, 0, mPixels.length);
        invalidate();
        notifyChanged();
        return true;
    }

    public boolean hasContent() {
        for (int pixel : mPixels) {
            if (Color.alpha(pixel) != 0) return true;
        }
        return false;
    }

    /** Exports the current grid at its native resolution (GRID_SIZE x GRID_SIZE) - deliberately
     *  not upscaled here, ControlButton already scales any custom image to fit the button, and
     *  scaling a crisp small grid up preserves the pixel-art look far better than shipping a
     *  pre-blurred/upscaled bitmap would. */
    public Bitmap exportBitmap() {
        return Bitmap.createBitmap(mPixels, GRID_SIZE, GRID_SIZE, Bitmap.Config.ARGB_8888);
    }

    private void pushUndoSnapshot() {
        if (mUndoStack.size() >= MAX_UNDO_DEPTH) mUndoStack.pollFirst();
        mUndoStack.addLast(Arrays.copyOf(mPixels, mPixels.length));
    }

    private void notifyChanged() {
        if (mListener != null) mListener.onPixelChanged();
    }

    private float cellSize() {
        return Math.min(getWidth(), getHeight()) / (float) GRID_SIZE;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cell = cellSize();
        if (cell <= 0) return;

        // Checkerboard so transparency in the drawing is visible, same convention most pixel
        // editors and image tools use.
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                boolean light = (row + col) % 2 == 0;
                mCellRect.set(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell);
                canvas.drawRect(mCellRect, light ? mCheckerPaintLight : mCheckerPaintDark);

                int pixel = mPixels[row * GRID_SIZE + col];
                if (Color.alpha(pixel) != 0) {
                    mPixelPaint.setColor(pixel);
                    canvas.drawRect(mCellRect, mPixelPaint);
                }
            }
        }

        // Faint grid lines, only worth drawing once cells are big enough to actually show them.
        if (cell >= 8f) {
            float gridExtent = GRID_SIZE * cell;
            for (int i = 0; i <= GRID_SIZE; i++) {
                float pos = i * cell;
                canvas.drawLine(pos, 0, pos, gridExtent, mGridLinePaint);
                canvas.drawLine(0, pos, gridExtent, pos, mGridLinePaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float cell = cellSize();
        if (cell <= 0) return false;

        int col = (int) (event.getX() / cell);
        int row = (int) (event.getY() / cell);
        if (col < 0 || col >= GRID_SIZE || row < 0 || row >= GRID_SIZE) {
            mLastTouchedCell = -1;
            return true;
        }
        int cellIndex = row * GRID_SIZE + col;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mGestureSnapshotTaken = false;
                mLastTouchedCell = -1;
                applyToolAt(row, col, cellIndex);
                break;
            case MotionEvent.ACTION_MOVE:
                if (cellIndex != mLastTouchedCell) {
                    applyToolAt(row, col, cellIndex);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mLastTouchedCell = -1;
                return true;
            default:
                return false;
        }
        return true;
    }

    private void applyToolAt(int row, int col, int cellIndex) {
        // Fill is a single flood-fill operation per gesture, not a continuous paint - re-running
        // it on every ACTION_MOVE cell would be both pointless (same result) and wasteful.
        if (mCurrentTool == Tool.FILL) {
            if (!mGestureSnapshotTaken) {
                pushUndoSnapshot();
                mGestureSnapshotTaken = true;
                floodFill(row, col, mCurrentColor);
                invalidate();
                notifyChanged();
            }
            mLastTouchedCell = cellIndex;
            return;
        }

        if (!mGestureSnapshotTaken) {
            pushUndoSnapshot();
            mGestureSnapshotTaken = true;
        }
        mPixels[cellIndex] = (mCurrentTool == Tool.ERASER) ? Color.TRANSPARENT : mCurrentColor;
        mLastTouchedCell = cellIndex;
        invalidate();
        notifyChanged();
    }

    private void floodFill(int startRow, int startCol, int newColor) {
        int targetColor = mPixels[startRow * GRID_SIZE + startCol];
        if (targetColor == newColor) return;

        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{startRow, startCol});
        while (!stack.isEmpty()) {
            int[] cell = stack.pop();
            int r = cell[0], c = cell[1];
            if (r < 0 || r >= GRID_SIZE || c < 0 || c >= GRID_SIZE) continue;
            int index = r * GRID_SIZE + c;
            if (mPixels[index] != targetColor) continue;

            mPixels[index] = newColor;
            stack.push(new int[]{r + 1, c});
            stack.push(new int[]{r - 1, c});
            stack.push(new int[]{r, c + 1});
            stack.push(new int[]{r, c - 1});
        }
    }
}
