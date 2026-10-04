package com.shi.camerax;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.Size;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.Component;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;
import com.google.appinventor.components.runtime.Form;
import com.google.appinventor.components.runtime.PermissionResultHandler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

@DesignerComponent(
        version = 27,
        description = "Optimized Non-visible Camera2 extension with NO_WRAP Base64 encoding.",
        category = ComponentCategory.EXTENSION,
        nonVisible = true,
        iconName = ""
)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.CAMERA, android.permission.FLASHLIGHT")
public class CameraXExtension extends AndroidNonvisibleComponent {

    private static final String TAG = "Camera2Extension";

    private final Form form;
    private String cameraId = "0";
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    private int jpegQuality = 85;
    private int maxImageWidth = 1280;
    private int maxImageHeight = 1280;
    private boolean isFlashOn = false;
    private boolean isCapturing = false;

    public CameraXExtension(ComponentContainer container) {
        super(container.$form());
        this.form = container.$form();
    }

    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            form.runOnUiThread(r);
        }
    }

    private void fireError(final String msg) {
        Log.e(TAG, msg);
        runOnUi(new Runnable() {
            @Override
            public void run() {
                ErrorOccurred(msg);
            }
        });
    }

    @SimpleFunction(description = "Initialize camera. Requests CAMERA permission automatically if needed.")
    public void InitializeCamera() {
        startBackgroundThread();
        ensurePermission(new Runnable() {
            @Override
            public void run() {
                openCameraInternal();
            }
        });
    }

    private void ensurePermission(final Runnable onGranted) {
        if (form.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            onGranted.run();
            return;
        }
        runOnUi(new Runnable() {
            @Override
            public void run() {
                form.askPermission(Manifest.permission.CAMERA, new PermissionResultHandler() {
                    @Override
                    public void HandlePermissionResponse(String permission, boolean granted) {
                        if (granted) {
                            onGranted.run();
                        } else {
                            fireError("Camera permission denied by user.");
                        }
                    }
                });
            }
        });
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
                Log.e(TAG, "Stop background thread error: " + Log.getStackTraceString(e));
            }
        }
    }

    @SimpleFunction(description = "Open camera by index.")
    public void OpenCameraByIndex(final int index) {
        try {
            CameraManager manager = (CameraManager) form.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            if (index >= 0 && index < ids.length) {
                cameraId = ids[index];
                CloseCamera();
                InitializeCamera();
            } else {
                fireError("Invalid camera index: " + index);
            }
        } catch (Exception e) {
            fireError("OpenCameraByIndex error: " + Log.getStackTraceString(e));
        }
    }

    @SimpleFunction(description = "Switch camera.")
    public void SwitchCamera() {
        try {
            CameraManager manager = (CameraManager) form.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            if (ids.length <= 1) {
                fireError("Only one camera available.");
                return;
            }
            for (int i = 0; i < ids.length; i++) {
                if (ids[i].equals(cameraId)) {
                    int nextIndex = (i + 1) % ids.length;
                    cameraId = ids[nextIndex];
                    break;
                }
            }
            CloseCamera();
            InitializeCamera();
        } catch (Exception e) {
            fireError("Switch camera error: " + Log.getStackTraceString(e));
        }
    }

    private void openCameraInternal() {
        try {
            if (form.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ensurePermission(new Runnable() {
                    @Override
                    public void run() {
                        openCameraInternal();
                    }
                });
                return;
            }
            CameraManager manager = (CameraManager) form.getSystemService(Context.CAMERA_SERVICE);
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (Exception e) {
            fireError("Open camera exception: " + Log.getStackTraceString(e));
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCaptureSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            CloseCamera();
            fireError("Camera disconnected.");
        }

        @Override
        public void onError(CameraDevice camera, final int error) {
            CloseCamera();
            fireError("Camera device error code: " + error);
        }
    };

    private void createCaptureSession() {
        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }

            CameraManager manager = (CameraManager) form.getSystemService(Context.CAMERA_SERVICE);
            Size jpegSize = chooseJpegSize(manager, maxImageWidth, maxImageHeight);

            imageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

            if (cameraDevice == null) return;

            cameraDevice.createCaptureSession(Arrays.asList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            runOnUi(new Runnable() {
                                @Override
                                public void run() {
                                    CameraReady();
                                }
                            });
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            fireError("Camera session configuration failed.");
                        }
                    }, backgroundHandler);
        } catch (Exception e) {
            fireError("Create capture session exception: " + Log.getStackTraceString(e));
        }
    }

    private Size chooseJpegSize(CameraManager manager, int desiredW, int desiredH) {
        try {
            CameraCharacteristics cc = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                Size best = null;
                for (Size s : sizes) {
                    if (s.getWidth() <= desiredW && s.getHeight() <= desiredH
                            && (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight())) {
                        best = s;
                    }
                }
                if (best == null && sizes.length > 0) best = sizes[0];
                if (best != null) return best;
            }
        } catch (Exception e) {
            Log.w(TAG, "chooseJpegSize failed: " + Log.getStackTraceString(e));
        }
        return new Size(desiredW, desiredH);
    }

    @SimpleFunction(description = "Take picture.")
    public void TakePicture() {
        if (isCapturing) {
            Log.w(TAG, "Capture already in progress, ignoring duplicate call.");
            return;
        }
        if (cameraDevice == null || captureSession == null || imageReader == null) {
            fireError("Camera not ready. Re-initializing camera...");
            InitializeCamera();
            return;
        }
        isCapturing = true;
        try {
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setFlashParameter(captureBuilder);

            captureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    isCapturing = false;
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    isCapturing = false;
                    fireError("Capture failed, reason code: " + failure.getReason());
                }
            }, backgroundHandler);
        } catch (Exception e) {
            isCapturing = false;
            fireError("Take picture error: " + Log.getStackTraceString(e));
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

                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) {
                    fireError("Failed to decode captured JPEG.");
                    return;
                }

                Bitmap scaled = resizeBitmapIfNeeded(bitmap);
                if (scaled != bitmap) bitmap.recycle();

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out);
                byte[] compressed = out.toByteArray();
                scaled.recycle();

                final String md5Final = calculateMD5(compressed);
                // 使用 NO_WRAP 避免 Base64 字符串中插入换行符
                final String base64 = Base64.encodeToString(compressed, Base64.NO_WRAP);
                final String path = saveBitmapToLocalStorage(compressed);

                if (path.isEmpty()) {
                    fireError("Failed to save image file.");
                    return;
                }

                runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        OnImageCaptured(base64, md5Final, path);
                    }
                });
            } catch (Exception e) {
                fireError("Process image error: " + Log.getStackTraceString(e));
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    };

    @SimpleFunction(description = "Set flash on or off.")
    public void SetFlash(boolean enable) {
        isFlashOn = enable;
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

    @SimpleFunction(description = "Set quality (1-100).")
    public void SetQuality(int q) {
        if (q > 0 && q <= 100) this.jpegQuality = q;
    }

    @SimpleFunction(description = "Set max saved image size in pixels.")
    public void SetImageSize(int w, int h) {
        this.maxImageWidth = w;
        this.maxImageHeight = h;
    }

    @SimpleFunction(description = "Returns true when the camera can take pictures.")
    public boolean IsCameraReady() {
        return cameraDevice != null && captureSession != null && imageReader != null;
    }

    @SimpleFunction(description = "Close camera and release resources.")
    public void CloseCamera() {
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
        } catch (Exception e) {
            Log.e(TAG, "Close camera error: " + Log.getStackTraceString(e));
        }
        isCapturing = false;
        stopBackgroundThread();
    }

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
            File dir = form.getExternalFilesDir(null);
            if (dir == null) dir = form.getFilesDir();
            File file = new File(dir, "IMG_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data);
            fos.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Save file error: " + Log.getStackTraceString(e));
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

    // ==================== Events ====================

    @SimpleEvent(description = "Error occurred with stack trace.")
    public void ErrorOccurred(String msg) {
        EventDispatcher.dispatchEvent(this, "ErrorOccurred", msg);
    }

    @SimpleEvent(description = "Image captured.")
    public void OnImageCaptured(String base64, String md5, String path) {
        EventDispatcher.dispatchEvent(this, "OnImageCaptured", base64, md5, path);
    }

    @SimpleEvent(description = "Fired when the camera session is ready.")
    public void CameraReady() {
        EventDispatcher.dispatchEvent(this, "CameraReady");
    }
}
