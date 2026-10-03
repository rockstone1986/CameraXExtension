package com.shi.camerax;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;

@DesignerComponent(
        version = 11,
        description = "High-stability native Camera extension supporting image enhancement, multi-camera selection, auto focus, resize, Base64, MD5 and local JPG saving (Optimized for scope isolation).",
        category = ComponentCategory.EXTENSION,
        nonVisible = true,
        iconName = ""
)
@SimpleObject(external = true)
// 【核心修改】移除了全局的 WRITE_EXTERNAL_STORAGE 和 READ_EXTERNAL_STORAGE 权限，
// 避免插件强制改变 Android 系统的存储作用域（Legacy 模式），从而保护 App Inventor 的 FileScope。
@UsesPermissions(permissionNames = "android.permission.CAMERA, android.permission.FLASHLIGHT")
public class CameraXExtension extends AndroidNonvisibleComponent {

    private static final String TAG = "CameraExtension";
    private final ComponentContainer container;
    private final Activity activity;
    private Camera camera;
    private SurfaceTexture dummySurfaceTexture;
    private boolean isPreviewRunning = false;
    
    // 当前使用的摄像头索引
    private int currentCameraId = 0;
    
    // 压缩与尺寸参数
    private int jpegQuality = 85;
    private int maxImageWidth = 1280;
    private int maxImageHeight = 1280;
    
    // 画质增强模式: 0 = 关闭, 1 = 高画质锐化, 2 = 降噪平滑
    private int enhanceMode = 0;

    public CameraXExtension(ComponentContainer container) {
        super(container.$form());
        this.container = container;
        this.activity = container.$form();
    }

    @SimpleFunction(description = "Initialize native camera in the background with default index 0.")
    public void InitializeCamera() {
        container.$form().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                openCamera(currentCameraId);
            }
        });
    }

    @SimpleFunction(description = "Get the total number of physical cameras available on the device.")
    public int GetCameraCount() {
        try {
            return Camera.getNumberOfCameras();
        } catch (Exception e) {
            Log.e(TAG, "Get camera count error: " + e.getMessage());
            return 0;
        }
    }

    @SimpleFunction(description = "Open a specific camera by its numeric index (0, 1, 2, 3, 4...).")
    public void OpenCameraByIndex(final int cameraId) {
        container.$form().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int totalCameras = Camera.getNumberOfCameras();
                if (cameraId >= 0 && cameraId < totalCameras) {
                    currentCameraId = cameraId;
                    releaseCamera();
                    openCamera(currentCameraId);
                    Log.i(TAG, "Switched to camera index: " + cameraId);
                } else {
                    Log.w(TAG, "Invalid camera index: " + cameraId + ". Total cameras: " + totalCameras);
                }
            }
        });
    }

    @SimpleFunction(description = "Switch between front and back camera (legacy helper).")
    public void SwitchCamera() {
        container.$form().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int totalCameras = Camera.getNumberOfCameras();
                if (totalCameras <= 1) return;
                
                currentCameraId = (currentCameraId + 1) % totalCameras;
                releaseCamera();
                openCamera(currentCameraId);
            }
        });
    }

    private void openCamera(int cameraId) {
        try {
            if (camera == null) {
                int totalCameras = Camera.getNumberOfCameras();
                if (cameraId < 0 || cameraId >= totalCameras) {
                    cameraId = 0;
                    currentCameraId = 0;
                }

                camera = Camera.open(cameraId);
                setCameraDisplayOrientation(activity, cameraId, camera);
                
                dummySurfaceTexture = new SurfaceTexture(10);
                camera.setPreviewTexture(dummySurfaceTexture);
                
                try {
                    Camera.Parameters params = camera.getParameters();
                    if (params.getSupportedFocusModes() != null &&
                            params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                        camera.setParameters(params);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Continuous focus not supported: " + e.getMessage());
                }

                camera.startPreview();
                isPreviewRunning = true;
                Log.i(TAG, "Camera " + cameraId + " preview started successfully.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Open camera error: " + e.getMessage());
        }
    }

    private void releaseCamera() {
        try {
            if (camera != null) {
                camera.stopPreview();
                camera.release();
                camera = null;
                isPreviewRunning = false;
            }
            if (dummySurfaceTexture != null) {
                dummySurfaceTexture.release();
                dummySurfaceTexture = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Release camera error: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Trigger auto focus manually.")
    public void Focus() {
        if (camera == null || !isPreviewRunning) return;
        try {
            camera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera cam) {
                    Log.i(TAG, "Auto focus result: " + success);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Focus error: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Set image compression quality (1-100).")
    public void SetQuality(int quality) {
        if (quality > 0 && quality <= 100) {
            this.jpegQuality = quality;
        }
    }

    @SimpleFunction(description = "Set maximum image dimensions (width and height) for resizing.")
    public void SetImageSize(int maxWidth, int maxHeight) {
        this.maxImageWidth = maxWidth;
        this.maxImageHeight = maxHeight;
    }

    @SimpleFunction(description = "Set image enhance mode: 0=Normal/Off, 1=High Quality Sharpen, 2=Denoise/Smooth.")
    public void SetImageEnhanceMode(int mode) {
        if (mode >= 0 && mode <= 2) {
            this.enhanceMode = mode;
        }
    }

    @SimpleFunction(description = "Take a picture and return Base64, MD5 and local JPG file path via event.")
    public void TakePicture() {
        if (camera == null || !isPreviewRunning) {
            Log.w(TAG, "Camera is not ready.");
            return;
        }

        try {
            camera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera cam) {
                    captureImageInternal();
                }
            });
        } catch (Exception e) {
            captureImageInternal();
        }
    }

    private void captureImageInternal() {
        try {
            camera.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera cam) {
                    try {
                        String md5Str = calculateMD5(data);

                        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                        Bitmap scaledBitmap = resizeBitmapIfNeeded(bitmap);
                        if (bitmap != scaledBitmap) {
                            bitmap.recycle();
                        }

                        Bitmap enhancedBitmap = applyEnhanceFilter(scaledBitmap, enhanceMode);
                        if (scaledBitmap != enhancedBitmap) {
                            scaledBitmap.recycle();
                        }

                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        enhancedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream);
                        byte[] compressedData = outputStream.toByteArray();
                        enhancedBitmap.recycle();

                        String base64String = Base64.encodeToString(compressedData, Base64.DEFAULT);
                        String filePath = saveBitmapToLocalStorage(compressedData);

                        OnImageCaptured(base64String, md5Str, filePath);

                        cam.startPreview();
                    } catch (Exception e) {
                        Log.e(TAG, "Process picture error: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Take picture error: " + e.getMessage());
        }
    }

    private Bitmap applyEnhanceFilter(Bitmap src, int mode) {
        if (mode == 0) {
            return src;
        }
        
        int width = src.getWidth();
        int height = src.getHeight();
        Bitmap result = Bitmap.createBitmap(width, height, src.getConfig());

        int[] pixels = new int[width * height];
        src.getPixels(pixels, 0, width, 0, 0, width, height);

        if (mode == 1) {
            int[] tempPixels = pixels.clone();
            int w = width;
            int h = height;

            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int idx = y * w + x;
                    int p0 = tempPixels[idx];
                    int pTop = tempPixels[(y - 1) * w + x];
                    int pBottom = tempPixels[(y + 1) * w + x];
                    int pLeft = tempPixels[y * w + (x - 1)];
                    int pRight = tempPixels[y * w + (x + 1)];

                    int r = clamp((5 * Color.red(p0) - Color.red(pTop) - Color.red(pBottom) - Color.red(pLeft) - Color.red(pRight)));
                    int g = clamp((5 * Color.green(p0) - Color.green(pTop) - Color.green(pBottom) - Color.green(pLeft) - Color.green(pRight)));
                    int b = clamp((5 * Color.blue(p0) - Color.blue(pTop) - Color.blue(pBottom) - Color.blue(pLeft) - Color.blue(pRight)));

                    pixels[idx] = Color.rgb(r, g, b);
                }
            }
        } else if (mode == 2) {
            int[] tempPixels = pixels.clone();
            int w = width;
            int h = height;

            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int rSum = 0, gSum = 0, bSum = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int p = tempPixels[(y + dy) * w + (x + dx)];
                            rSum += Color.red(p);
                            gSum += Color.green(p);
                            bSum += Color.blue(p);
                        }
                    }
                    pixels[y * w + x] = Color.rgb(rSum / 9, gSum / 9, bSum / 9);
                }
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private Bitmap resizeBitmapIfNeeded(Bitmap bitmap) {
        if (maxImageWidth <= 0 || maxImageHeight <= 0) {
            return bitmap;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= maxImageWidth && height <= maxImageHeight) {
            return bitmap;
        }

        float scale = Math.min((float) maxImageWidth / width, (float) maxImageHeight / height);
        int newWidth = Math.round(width * scale);
        int newHeight = Math.round(height * scale);

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }

    private String saveBitmapToLocalStorage(byte[] compressedData) {
        try {
            File dir = activity.getExternalFilesDir(null);
            if (dir == null) {
                dir = activity.getFilesDir();
            }
            File imageFile = new File(dir, "IMG_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(imageFile);
            fos.write(compressedData);
            fos.flush();
            fos.close();
            return imageFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Save image error: " + e.getMessage());
            return "";
        }
    }

    private String calculateMD5(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            Log.e(TAG, "Calculate MD5 error: " + e.getMessage());
            return "";
        }
    }

    @SimpleFunction(description = "Turn flash on or off.")
    public void SetFlash(boolean enable) {
        try {
            if (camera != null) {
                Camera.Parameters params = camera.getParameters();
                String mode = enable ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF;
                if (params.getSupportedFlashModes() != null && params.getSupportedFlashModes().contains(mode)) {
                    params.setFlashMode(mode);
                    camera.setParameters(params);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Set flash error: " + e.getMessage());
        }
    }

    @SimpleEvent(description = "Triggered when image is captured, returning Base64, MD5 and local JPG file path.")
    public void OnImageCaptured(String base64Data, String md5, String filePath) {
        EventDispatcher.dispatchEvent(this, "OnImageCaptured", base64Data, md5, filePath);
    }

    private void setCameraDisplayOrientation(Activity activity, int cameraId, android.hardware.Camera camera) {
        android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraId, info);
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }
        int result;
        if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        camera.setDisplayOrientation(result);
    }
}
