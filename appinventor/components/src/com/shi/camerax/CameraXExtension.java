package com.shi.camerax;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.app.ActivityCompat;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.AndroidViewComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

@DesignerComponent(
        version = 22,
        description = "Stable Camera2 extension with explicit integer properties, container preview, flash, auto-focus, and safe lifecycle.",
        category = ComponentCategory.EXTENSION,
        nonVisible = false,
        iconName = ""
)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.CAMERA, android.permission.FLASHLIGHT")
public class CameraXExtension extends AndroidViewComponent implements TextureView.SurfaceTextureListener {

    private static final String TAG = "Camera2Extension";
    private final Activity activity;
    private final FrameLayout frameLayout;
    private final TextureView textureView;

    private String cameraId = "0";
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    private int jpegQuality = 85;
    private int maxImageWidth = 1280;
    private int maxImageHeight = 1280;
    private boolean isFlashOn = false;
    
    private boolean isSurfaceAvailable = false;
    private boolean isCameraInitializedRequested = false;

    public CameraXExtension(ComponentContainer container) {
        super(container);
        this.activity = container.$form();

        this.frameLayout = new FrameLayout(activity);
        this.textureView = new TextureView(activity);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        this.textureView.setLayoutParams(params);
        this.frameLayout.addView(this.textureView);
        this.textureView.setSurfaceTextureListener(this);

        container.$add(this);
    }

    @Override
    public View getView() {
        return frameLayout;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.i(TAG, "TextureView surface available.");
        isSurfaceAvailable = true;
        if (isCameraInitializedRequested && cameraDevice == null) {
            startBackgroundThread();
            openCamera();
        } else if (cameraDevice != null && captureSession == null) {
            createCameraPreviewSession();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.i(TAG, "TextureView surface destroyed.");
        isSurfaceAvailable = false;
        closeCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    @SimpleFunction(description = "Initialize camera safely.")
    public void InitializeCamera() {
        isCameraInitializedRequested = true;
        startBackgroundThread();
        if (isSurfaceAvailable) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    openCamera();
                }
            });
        }
    }

    private void startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("Camera2Background");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Stop background thread error: " + e.getMessage());
            }
        }
    }

    @SimpleFunction(description = "Open camera by index.")
    public void OpenCameraByIndex(final int index) {
        try {
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            if (index >= 0 && index < ids.length) {
                cameraId = ids[index];
                closeCamera();
                InitializeCamera();
            } else {
                ErrorOccurred("Invalid camera index: " + index);
            }
        } catch (Exception e) {
            ErrorOccurred("OpenCameraByIndex error: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Switch camera.")
    public void SwitchCamera() {
        try {
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            if (ids.length <= 1) {
                ErrorOccurred("Only one camera available.");
                return;
            }
            for (int i = 0; i < ids.length; i++) {
                if (ids[i].equals(cameraId)) {
                    int nextIndex = (i + 1) % ids.length;
                    cameraId = ids[nextIndex];
                    break;
                }
            }
            closeCamera();
            InitializeCamera();
        } catch (Exception e) {
            ErrorOccurred("Switch camera error: " + e.getMessage());
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ErrorOccurred("Camera permission not granted.");
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (Exception e) {
            ErrorOccurred("Open camera exception: " + e.getMessage());
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            ErrorOccurred("Camera device error code: " + error);
        }
    };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null || !isSurfaceAvailable) return;

            texture.setDefaultBufferSize(1280, 720);
            Surface surface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

            cameraDevice.createCaptureSession(java.util.Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                setFlashParameter(previewRequestBuilder);
                                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                            } catch (Exception e) {
                                ErrorOccurred("Start preview session error: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            ErrorOccurred("Camera session configuration failed.");
                        }
                    }, backgroundHandler);
        } catch (Exception e) {
            ErrorOccurred("Create preview session exception: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Take picture.")
    public void TakePicture() {
        if (cameraDevice == null || captureSession == null) {
            ErrorOccurred("Camera not ready.");
            InitializeCamera();
            return;
        }
        try {
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setFlashParameter(captureBuilder);

            captureSession.stopRepeating();
            captureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {}, backgroundHandler);
        } catch (Exception e) {
            ErrorOccurred("Take picture error: " + e.getMessage());
        }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                image.close();

                String md5Str = calculateMD5(bytes);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) return;

                Bitmap scaled = resizeBitmapIfNeeded(bitmap);
                if (scaled != bitmap) bitmap.recycle();

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out);
                byte[] compressed = out.toByteArray();
                scaled.recycle();

                String base64 = Base64.encodeToString(compressed, Base64.DEFAULT);
                String path = saveBitmapToLocalStorage(compressed);

                if (!path.isEmpty()) {
                    OnImageCaptured(base64, md5Str, path);
                }

                if (captureSession != null && previewRequestBuilder != null) {
                    captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                }
            } catch (Exception e) {
                ErrorOccurred("Process image error: " + e.getMessage());
                if (image != null) image.close();
            }
        }
    };

    @SimpleFunction(description = "Set flash on or off.")
    public void SetFlash(boolean enable) {
        isFlashOn = enable;
        if (captureSession != null && previewRequestBuilder != null) {
            try {
                setFlashParameter(previewRequestBuilder);
                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
            } catch (Exception e) {
                Log.e(TAG, "Set flash error: " + e.getMessage());
            }
        }
    }

    private void setFlashParameter(CaptureRequest.Builder builder) {
        if (isFlashOn) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
        }
    }

    @SimpleFunction(description = "Trigger autofocus.")
    public void Focus() {
        if (captureSession == null || cameraDevice == null) return;
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture != null) builder.addTarget(new Surface(texture));
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureSession.capture(builder.build(), null, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "Focus error: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Set quality.")
    public void SetQuality(int q) { if (q > 0 && q <= 100) this.jpegQuality = q; }

    @SimpleFunction(description = "Set image size.")
    public void SetImageSize(int w, int h) { this.maxImageWidth = w; this.maxImageHeight = h; }

    private Bitmap resizeBitmapIfNeeded(Bitmap bitmap) {
        if (maxImageWidth <= 0 || maxImageHeight <= 0) return bitmap;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= maxImageWidth && h <= maxImageHeight) return bitmap;
        float scale = Math.min((float) maxImageWidth / w, (float) maxImageHeight / h);
        return Bitmap.createScaledBitmap(bitmap, Math.round(w * scale), Math.round(h * scale), true);
    }

    private String saveBitmapToLocalStorage(byte[] data) {
        try {
            File dir = activity.getExternalFilesDir(null);
            if (dir == null) dir = activity.getFilesDir();
            File file = new File(dir, "IMG_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data);
            fos.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    private String calculateMD5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void closeCamera() {
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
        } catch (Exception e) {
            Log.e(TAG, "Close camera error: " + e.getMessage());
        }
        stopBackgroundThread();
    }

    @SimpleEvent(description = "Error occurred.")
    public void ErrorOccurred(String msg) {
        EventDispatcher.dispatchEvent(this, "ErrorOccurred", msg);
    }

    @SimpleEvent(description = "Image captured.")
    public void OnImageCaptured(String base64, String md5, String path) {
        EventDispatcher.dispatchEvent(this, "OnImageCaptured", base64, md5, path);
    }
}
