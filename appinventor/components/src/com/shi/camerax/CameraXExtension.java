package com.shi.camerax;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
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
import com.google.appinventor.components.runtime.Form;
import com.google.appinventor.components.runtime.PermissionResultHandler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

@DesignerComponent(
        version = 23,
        description = "Stable Camera2 extension: UI-thread-safe events, runtime permission request, device-supported JPEG size, CameraReady event.",
        category = ComponentCategory.EXTENSION,
        nonVisible = false,
        iconName = ""
)
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.CAMERA, android.permission.FLASHLIGHT")
public class CameraXExtension extends AndroidViewComponent implements TextureView.SurfaceTextureListener {

    private static final String TAG = "Camera2Extension";

    private final Form form;
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
        this.form = container.$form();

        this.frameLayout = new FrameLayout(form);
        this.textureView = new TextureView(form);

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

    // ==================== FIX: UI-thread helpers + safe error dispatch ====================

    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            form.runOnUiThread(r);
        }
    }

    /** FIX: all internal errors go through here — logged AND dispatched on the UI thread. */
    private void fireError(final String msg) {
        Log.e(TAG, msg);
        runOnUi(new Runnable() {
            @Override
            public void run() {
                ErrorOccurred(msg);
            }
        });
    }

    // ==================== Surface lifecycle ====================

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.i(TAG, "TextureView surface available: " + width + "x" + height);
        isSurfaceAvailable = true;
        if (width == 0 || height == 0) {
            // FIX: previously a 0-size surface caused a silent "Camera not ready" forever.
            fireError("TextureView size is 0. Put the component in a visible arrangement and set Width/Height to Fill Parent.");
            return;
        }
        if (isCameraInitializedRequested && cameraDevice == null) {
            InitializeCamera();
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

    // ==================== Init / permission ====================

    @SimpleFunction(description = "Initialize camera. Requests CAMERA permission automatically if needed.")
    public void InitializeCamera() {
        isCameraInitializedRequested = true;
        startBackgroundThread();
        ensurePermission(new Runnable() {
            @Override
            public void run() {
                if (isSurfaceAvailable && cameraDevice == null) {
                    openCameraInternal();
                } else if (cameraDevice != null && captureSession == null) {
                    createCameraPreviewSession();
                }
            }
        });
    }

    /** FIX: ask the user for permission at runtime instead of just firing an error. */
    private void ensurePermission(final Runnable onGranted) {
        if (ActivityCompat.checkSelfPermission(form, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
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
                Log.e(TAG, "Stop background thread error: " + e.getMessage());
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
                closeCamera();
                InitializeCamera();
            } else {
                fireError("Invalid camera index: " + index);
            }
        } catch (Exception e) {
            fireError("OpenCameraByIndex error: " + e.getMessage());
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
            closeCamera();
            InitializeCamera();
        } catch (Exception e) {
            fireError("Switch camera error: " + e.getMessage());
        }
    }

    private void openCameraInternal() {
        // FIX: guard against opening the camera twice.
        if (cameraDevice != null) return;
        try {
            if (ActivityCompat.checkSelfPermission(form, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
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
            fireError("Open camera exception: " + e.getMessage());
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            // FIX: create the session on the UI thread (TextureView is not thread-safe).
            runOnUi(new Runnable() {
                @Override
                public void run() {
                    createCameraPreviewSession();
                }
            });
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            fireError("Camera disconnected.");
        }

        @Override
        public void onError(CameraDevice camera, final int error) {
            camera.close();
            cameraDevice = null;
            fireError("Camera device error code: " + error);
        }
    };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null || !isSurfaceAvailable) {
                fireError("Preview surface not ready (component not visible or size is 0).");
                return;
            }

            texture.setDefaultBufferSize(1280, 720);
            Surface surface = new Surface(texture);

            // FIX: pick a JPEG size the device actually supports instead of hardcoded 1920x1080.
            CameraManager manager = (CameraManager) form.getSystemService(Context.CAMERA_SERVICE);
            Size jpegSize = chooseJpegSize(manager, 1920, 1080);

            imageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                setFlashParameter(previewRequestBuilder);
                                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                                runOnUi(new Runnable() {
                                    @Override
                                    public void run() {
                                        CameraReady();
                                    }
                                });
                            } catch (Exception e) {
                                fireError("Start preview session error: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            fireError("Camera session configuration failed.");
                        }
                    }, backgroundHandler);
        } catch (Exception e) {
            fireError("Create preview session exception: " + e.getMessage());
        }
    }

    private Size chooseJpegSize(CameraManager manager, int desiredW, int desiredH) {
        try {
            CameraCharacteristics cc = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
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
        } catch (Exception e) {
            Log.w(TAG, "chooseJpegSize failed: " + e.getMessage());
        }
        return new Size(desiredW, desiredH);
    }

    @SimpleFunction(description = "Take picture.")
    public void TakePicture() {
        // FIX: report exactly what is missing, and guard against imageReader == null.
        if (cameraDevice == null || captureSession == null || imageReader == null) {
            fireError("Camera not ready: device=" + (cameraDevice != null)
                    + ", session=" + (captureSession != null)
                    + ", reader=" + (imageReader != null)
                    + ". Wait for CameraReady, or check the component is visible with a non-zero size.");
            InitializeCamera();
            return;
        }
        try {
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setFlashParameter(captureBuilder);

            captureSession.stopRepeating();
            // FIX: capture failures were completely silent before.
            captureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    fireError("Capture failed, reason code: " + failure.getReason());
                }
            }, backgroundHandler);
        } catch (Exception e) {
            fireError("Take picture error: " + e.getMessage());
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
                image = null;

                String md5Str = calculateMD5(bytes);

                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) {
                    // FIX: was a silent "return" before.
                    fireError("Failed to decode captured JPEG.");
                    return;
                }

                Bitmap scaled = resizeBitmapIfNeeded(bitmap);
                if (scaled != bitmap) bitmap.recycle();

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out);
                byte[] compressed = out.toByteArray();
                scaled.recycle();

                final String base64 = Base64.encodeToString(compressed, Base64.DEFAULT);
                final String path = saveBitmapToLocalStorage(compressed);
                final String md5Final = md5Str;

                if (path.isEmpty()) {
                    fireError("Failed to save image file (base64 is still delivered).");
                }

                // FIX: dispatch on the UI thread so blocks can safely touch Image/Canvas/WebViewer.
                // Before, this ran on the camera background thread and crashed the event silently.
                runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        OnImageCaptured(base64, md5Final, path);
                    }
                });

                // Restart the preview.
                if (captureSession != null && previewRequestBuilder != null) {
                    captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                }
            } catch (Exception e) {
                fireError("Process image error: " + e.getMessage());
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
            Log.e(TAG, "Save file error: " + e.getMessage());
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

    // ==================== Events ====================

    @SimpleEvent(description = "Error occurred. ALWAYS add a block for this while debugging.")
    public void ErrorOccurred(String msg) {
        EventDispatcher.dispatchEvent(this, "ErrorOccurred", msg);
    }

    @SimpleEvent(description = "Image captured. base64 = JPEG base64, md5 = MD5 of file bytes, path = saved file path (empty if saving failed). Fired on the UI thread.")
    public void OnImageCaptured(String base64, String md5, String path) {
        EventDispatcher.dispatchEvent(this, "OnImageCaptured", base64, md5, path);
    }

    @SimpleEvent(description = "Fired when the preview session is ready. Safe to call TakePicture after this.")
    public void CameraReady() {
        EventDispatcher.dispatchEvent(this, "CameraReady");
    }
}
