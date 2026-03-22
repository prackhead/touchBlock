package com.os.touchrejectionv2;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

public class TouchRejectService extends AccessibilityService {

    private static final String CHANNEL_ID = "touch_reject_service";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_STOP = "com.os.touchrejectionv2.STOP_SERVICE";

    private static TouchRejectService instance = null;

    private WindowManager wm;
    private NotificationManager notificationManager;
    private View toggleButton;
    private WindowManager.LayoutParams buttonParams;
    private View touchBlockOverlay;
    private WindowManager.LayoutParams blockParams;
    private boolean blocking = false;
    private boolean penNearScreen = false;
    private boolean active = false;

    public static TouchRejectService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
                | AccessibilityEvent.TYPE_TOUCH_INTERACTION_END;
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0;
        setServiceInfo(info);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        startup();
    }

    @Override
    public void onDestroy() {
        instance = null;
        removeTouchBlockOverlay();
        removeToggleButton();
        dismissNotification();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!blocking || blockParams == null || touchBlockOverlay == null) return;

        int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // App switch — force reblock so fingers are blocked in the new app
            disableStylusPassthrough();
        } else if (type == AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
                || type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
            // A view in the app below lost hover or a touch ended.
            // Clear FLAG_NOT_TOUCHABLE so the overlay can receive events again.
            // If the pen is still near, the overlay will immediately get
            // HOVER_ENTER and re-enable passthrough. If the pen is gone,
            // the overlay stays touchable and blocks fingers.
            if (penNearScreen) {
                disableStylusPassthrough();
            }
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            toggleBlocking();
            return true;
        }
        return super.onKeyEvent(event);
    }

    // --- Toggle Button ---

    private void createToggleButton() {
        if (toggleButton != null) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int widthDp = getIntPref(prefs, "button_width", 48);
        int heightDp = getIntPref(prefs, "button_height", 48);
        float opacity = prefs.getInt("opacity", 80) / 100f;

        toggleButton = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(12));
        bg.setColor(0xFFFFFFFF);
        toggleButton.setBackground(bg);
        toggleButton.setAlpha(opacity);

        int widthPx = dpToPx(widthDp);
        int heightPx = dpToPx(heightDp);

        buttonParams = new WindowManager.LayoutParams(
                widthPx, heightPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        buttonParams.gravity = Gravity.TOP | Gravity.LEFT;
        buttonParams.x = prefs.getInt("button_x", dpToPx(16));
        buttonParams.y = prefs.getInt("button_y", dpToPx(100));

        setupButtonTouch(prefs);
        wm.addView(toggleButton, buttonParams);
    }

    private void removeToggleButton() {
        if (toggleButton != null) {
            try { wm.removeView(toggleButton); } catch (Exception ignored) {}
            toggleButton = null;
            buttonParams = null;
        }
    }

    private void setupButtonTouch(final SharedPreferences prefs) {
        final boolean draggable = prefs.getBoolean("draggable", true);
        toggleButton.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int startPX, startPY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        startPX = buttonParams.x;
                        startPY = buttonParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (draggable) {
                            float dx = event.getRawX() - startX;
                            float dy = event.getRawY() - startY;
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                            if (moved) {
                                buttonParams.x = startPX + (int) dx;
                                buttonParams.y = startPY + (int) dy;
                                try { wm.updateViewLayout(toggleButton, buttonParams); }
                                catch (Exception ignored) {}
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            toggleBlocking();
                        } else {
                            prefs.edit()
                                    .putInt("button_x", buttonParams.x)
                                    .putInt("button_y", buttonParams.y)
                                    .apply();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    // --- Blocking ---

    public void startup() {
        if (active) return;
        active = true;
        showNotification();
        createToggleButton();
        enableBlocking();
    }

    public void shutdown() {
        active = false;
        disableBlocking();
        removeToggleButton();
        dismissNotification();
    }

    public boolean isActive() {
        return active;
    }

    private void toggleBlocking() {
        if (blocking) {
            disableBlocking();
        } else {
            enableBlocking();
        }
    }

    private void enableBlocking() {
        if (blocking) return;
        blocking = true;
        createTouchBlockOverlay();
        updateButtonColor();
    }

    private void disableBlocking() {
        if (!blocking) return;
        blocking = false;
        removeTouchBlockOverlay();
        updateButtonColor();
    }

    private void updateButtonColor() {
        if (toggleButton == null) return;
        if (blocking) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            float opacity = prefs.getInt("opacity", 80) / 100f;
            toggleButton.setAlpha(opacity);
        } else {
            toggleButton.setAlpha(1.0f);
        }
    }

    // --- Touch Block Overlay ---

    private void createTouchBlockOverlay() {
        if (touchBlockOverlay != null) return;

        touchBlockOverlay = new View(this);
        touchBlockOverlay.setBackgroundColor(0x01000000);

        // Hover listener: detect S-Pen approaching screen.
        // S-Pen always generates hover events before touch (Wacom digitizer
        // detects pen ~1cm above screen). By relying solely on hover, the
        // overlay sets FLAG_NOT_TOUCHABLE BEFORE the pen contacts the screen,
        // so ACTION_DOWN goes directly to the app — no stolen strokes.
        touchBlockOverlay.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, MotionEvent event) {
                int toolType = event.getToolType(0);
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS
                        || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                    if (event.getAction() == MotionEvent.ACTION_HOVER_ENTER
                            || event.getAction() == MotionEvent.ACTION_HOVER_MOVE) {
                        enableStylusPassthrough();
                    }
                    // HOVER_EXIT won't fire while FLAG_NOT_TOUCHABLE is set;
                    // instead, TYPE_VIEW_HOVER_EXIT / TYPE_TOUCH_INTERACTION_END
                    // accessibility events from the app below trigger reblock.
                    return true;
                }
                return true; // Block finger hover too
            }
        });

        // Touch listener: block ALL touches unconditionally.
        // Stylus passthrough is handled via hover detection + FLAG_NOT_TOUCHABLE,
        // so by the time the pen touches down, the flag is already set and
        // the touch goes directly to the app below (never reaches this listener).
        touchBlockOverlay.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return true; // Block everything — fingers, and any edge-case stylus touch
            }
        });

        blockParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
        );
        blockParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        wm.addView(touchBlockOverlay, blockParams);

        // Re-add button on top so it stays clickable above the overlay
        try { wm.removeView(toggleButton); } catch (Exception ignored) {}
        wm.addView(toggleButton, buttonParams);
    }

    private void removeTouchBlockOverlay() {
        penNearScreen = false;
        if (touchBlockOverlay != null) {
            touchBlockOverlay.setOnTouchListener(null);
            touchBlockOverlay.setOnHoverListener(null);
            try { wm.removeView(touchBlockOverlay); } catch (Exception ignored) {}
            touchBlockOverlay = null;
            blockParams = null;
        }
    }

    private void enableStylusPassthrough() {
        if (blockParams == null || touchBlockOverlay == null) return;
        penNearScreen = true;
        if ((blockParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
            blockParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { wm.updateViewLayout(touchBlockOverlay, blockParams); }
            catch (Exception ignored) {}
        }
    }

    private void disableStylusPassthrough() {
        penNearScreen = false;
        if (blocking && blockParams != null && touchBlockOverlay != null) {
            if ((blockParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
                blockParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                try { wm.updateViewLayout(touchBlockOverlay, blockParams); }
                catch (Exception ignored) {}
            }
        }
    }

    // --- Reload settings while running ---

    public void reloadSettings() {
        boolean wasBlocking = blocking;
        if (wasBlocking) removeTouchBlockOverlay();
        removeToggleButton();
        createToggleButton();
        if (wasBlocking) {
            blocking = true;
            createTouchBlockOverlay();
            updateButtonColor();
        }
    }

    // --- Notification ---

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "TouchRejection Service",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows while TouchRejection is running");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void showNotification() {
        Intent stopIntent = new Intent(ACTION_STOP);
        stopIntent.setComponent(new ComponentName(this, StopReceiver.class));
        PendingIntent stopPending = PendingIntent.getBroadcast(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TouchRejection v2")
                .setContentText("Running — tap Stop to shut down")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
                .build();

        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void dismissNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    // --- Util ---

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private int getIntPref(SharedPreferences prefs, String key, int def) {
        try {
            return Integer.parseInt(prefs.getString(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }
}
