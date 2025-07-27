package com.example.rootedaimv1;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class AimbotService extends Service {
    private static final String TAG = "AimbotService";
    private static final int NOTIFICATION_ID = 1;
    private boolean isRunning = false;
    private boolean isAimbotEnabled = false;
    private int delayMs = 0;
    private float smoothingFactor = 0.3f;
    private WindowManager windowManager;
    private View floatingView;
    private ImageView minimizedView;
    private Handler handler;
    private float lastX = -1;
    private float lastY = -1;
    private Button toggleButton;
    private SeekBar delaySlider;
    private SeekBar smoothnessSlider;
    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    private boolean isMinimized = false;

    // FIX: Moved from local to class-level
    private WindowManager.LayoutParams fullParams;
    private WindowManager.LayoutParams minimizedParams;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotification();
        setupFloatingWindow();
        handler = new Handler();
        startAimbotLoop();
    }

    private void createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "AimbotChannel",
                "Aimbot Service",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new Notification.Builder(this, "AimbotChannel")
            .setContentTitle("Rooted AimV1")
            .setContentText("Aimbot service running")
            .setSmallIcon(android.R.drawable.ic_notification_overlay)
            .setContentIntent(pendingIntent)
            .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void setupFloatingWindow() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);

        // Full UI
        floatingView = inflater.inflate(R.layout.floating_window_layout, null);
        fullParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
			WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        fullParams.x = 0;
        fullParams.y = 100;
        windowManager.addView(floatingView, fullParams);

        // Minimized icon
        minimizedView = new ImageView(this);
        minimizedView.setImageResource(android.R.drawable.ic_menu_gallery);
        minimizedParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
			WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        minimizedParams.x = 0;
        minimizedParams.y = 100;
        minimizedParams.width = 50;
        minimizedParams.height = 50;
        minimizedView.setVisibility(View.GONE);
        windowManager.addView(minimizedView, minimizedParams);

        // Minimize button
        Button minimizeButton = floatingView.findViewById(R.id.minimize_button);
        if (minimizeButton != null) {
            minimizeButton.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						isMinimized = !isMinimized;
						updateVisibility();
					}
				});
        }

        // Toggle button
        toggleButton = floatingView.findViewById(R.id.toggle_button);
        if (toggleButton != null) {
            toggleButton.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						isAimbotEnabled = !isAimbotEnabled;
						toggleButton.setText(isAimbotEnabled ? "Disable Aimbot" : "Enable Aimbot");
					}
				});
        }

        // Delay slider
        delaySlider = floatingView.findViewById(R.id.delay_slider);
        if (delaySlider != null) {
            delaySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
					@Override
					public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
						delayMs = progress;
					}
					@Override
					public void onStartTrackingTouch(SeekBar seekBar) {}
					@Override
					public void onStopTrackingTouch(SeekBar seekBar) {}
				});
        }

        // Smoothness slider
        smoothnessSlider = floatingView.findViewById(R.id.smoothness_slider);
        if (smoothnessSlider != null) {
            smoothnessSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
					@Override
					public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
						smoothingFactor = progress / 100.0f;
					}
					@Override
					public void onStartTrackingTouch(SeekBar seekBar) {}
					@Override
					public void onStopTrackingTouch(SeekBar seekBar) {}
				});
        }

        // Draggable window
        floatingView.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View v, MotionEvent event) {
					switch (event.getAction()) {
						case MotionEvent.ACTION_DOWN:
							initialX = fullParams.x;
							initialY = fullParams.y;
							initialTouchX = event.getRawX();
							initialTouchY = event.getRawY();
							return true;
						case MotionEvent.ACTION_MOVE:
							fullParams.x = initialX + (int) (event.getRawX() - initialTouchX);
							fullParams.y = initialY + (int) (event.getRawY() - initialTouchY);
							windowManager.updateViewLayout(floatingView, fullParams);
							return true;
					}
					return false;
				}
			});

        // Tap to restore from minimized
        minimizedView.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View v, MotionEvent event) {
					if (event.getAction() == MotionEvent.ACTION_UP) {
						if (isMinimized) {
							isMinimized = false;
							updateVisibility();
						}
						return true;
					}
					return false;
				}
			});
    }

    private void updateVisibility() {
        if (floatingView != null && minimizedView != null) {
            if (isMinimized) {
                floatingView.setVisibility(View.GONE);
                minimizedParams.x = fullParams.x;
                minimizedParams.y = fullParams.y;
                minimizedView.setVisibility(View.VISIBLE);
            } else {
                floatingView.setVisibility(View.VISIBLE);
                minimizedView.setVisibility(View.GONE);
            }
            windowManager.updateViewLayout(floatingView, fullParams);
            windowManager.updateViewLayout(minimizedView, minimizedParams);
        }
    }

    private void startAimbotLoop() {
        isRunning = true;
        handler.post(new Runnable() {
				@Override
				public void run() {
					if (isRunning && isAimbotEnabled) {
						try {
							Bitmap screenshot = captureScreen();
							if (screenshot != null) {
								int[] target = detectHeadshot(screenshot);
								if (target != null) {
									smoothAim(target[0], target[1]);
								}
								screenshot.recycle();
							}
						} catch (Exception e) {
							Log.e(TAG, "Aimbot loop error: " + e.getMessage());
						}
					}
					handler.postDelayed(this, delayMs);
				}
			});
    }

    private Bitmap captureScreen() {
        ByteArrayOutputStream baos = null;
        InputStream inputStream = null;
        try {
            Process process = Runtime.getRuntime().exec("su -c screencap -p");
            inputStream = process.getInputStream();
            baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            inputStream.close();
            process.waitFor();
            byte[] screenshotBytes = baos.toByteArray();
            return BitmapFactory.decodeByteArray(screenshotBytes, 0, screenshotBytes.length);
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Screen capture error: " + e.getMessage());
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close InputStream: " + e.getMessage());
                }
            }
            if (baos != null) {
                try {
                    baos.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close ByteArrayOutputStream: " + e.getMessage());
                }
            }
        }
    }

    private int[] detectHeadshot(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int headRegionHeight = height / 4;
        for (int x = width / 4; x < width * 3 / 4; x++) {
            for (int y = 0; y < headRegionHeight; y++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.red(pixel) > 200 && Color.green(pixel) < 50 && Color.blue(pixel) < 50) {
                    return new int[]{x, y + 20};
                }
            }
        }
        return null;
    }

    private void smoothAim(int x, int y) {
        if (lastX == -1 || lastY == -1) {
            lastX = x;
            lastY = y;
        }
        float newX = lastX + smoothingFactor * (x - lastX);
        float newY = lastY + smoothingFactor * (y - lastY);
        lastX = newX;
        lastY = newY;
        simulateTouch((int) newX, (int) newY);
    }

    private void simulateTouch(int x, int y) {
        try {
            String command = "su -c input tap " + x + " " + y;
            Runtime.getRuntime().exec(command);
            Log.d(TAG, "Root touch at: " + x + ", " + y);
        } catch (IOException e) {
            Log.e(TAG, "Root touch failed: " + e.getMessage());
            AccessibilityInputService.performTap(x, y);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
        if (minimizedView != null) {
            windowManager.removeView(minimizedView);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

