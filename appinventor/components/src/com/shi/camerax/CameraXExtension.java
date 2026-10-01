package com.shi.camerax;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

@DesignerComponent(
        version = 3,
        description = "High-stability native Camera extension supporting background preview and Base64 image capture.",
        category = ComponentCategory.EXTENSION,
        nonVisible = true,
        iconName = ""
)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.CAMERA, android.permission.FLASHLIGHT")
public class CameraXExtension extends AndroidNonvisibleComponent {

    private static final String TAG = "CameraExtension";
    private final ComponentContainer container;
    private final Activity activity;
    private Camera camera;
    private boolean isPreviewRunning = false;

    public CameraXExtension(ComponentContainer container) {
        super(container.$form());
        this.container = container;
        this.activity = container.$form();
    }

    @SimpleFunction(description = "Initialize native camera in the background.")
    public void InitializeCamera() {
        container.$form().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                openCamera();
            }
        });
    }

    private void openCamera() {
        try {
            if (camera == null) {
                camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
                setCameraDisplayOrientation(activity, Camera.CameraInfo.CAMERA_FACING_BACK, camera);
                // 开启预览（如果没有 SurfaceView，部分老式 Camera API 需要一个预览纹理或 Dummy Surface，
                // 如果直接 startPreview 报错，可按需适配 SurfaceTexture）
                camera.startPreview();
                isPreviewRunning = true;
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
        } catch (Exception e) {
            Log.e(TAG, "Release camera error: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Take a picture and return Base64 string via event.")
    public void TakePicture() {
        if (camera == null || !isPreviewRunning) {
            Log.w(TAG, "Camera is not ready.");
            return;
        }

        try {
            camera.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera cam) {
                    try {
                        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream);
                        bitmap.recycle();

                        String base64String = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT);
                        OnImageCaptured(base64String);

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

    @SimpleEvent(description = "Triggered when image is captured and converted to Base64.")
    public void OnImageCaptured(String base64Data) {
        EventDispatcher.dispatchEvent(this, "OnImageCaptured", base64Data);
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
