package com.shi.camerax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;
import com.google.appinventor.components.runtime.HVArrangement;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

@DesignerComponent(
        version = 2,
        description = "High-stability CameraX extension supporting preview, flash control, and Base64 image capture.",
        category = ComponentCategory.EXTENSION,
        nonVisible = false,
        icon = ""
)
@SimpleObject(external = true)
@UsesLibraries(libraries = "camera-camera2:1.3.1, camera-view:1.3.1, camera-lifecycle:1.3.1")
@UsesPermissions(permissionNames = "android.permission.CAMERA")
public class CameraXExtension extends AndroidNonvisibleComponent {

    private static final String TAG = "CameraXExtension";
    private final ComponentContainer container;
    private final Context context;
    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;

    public CameraXExtension(ComponentContainer container) {
        super(container.$form());
        this.container = container;
        this.context = container.$context();
    }

    @SimpleFunction(description = "Initialize CameraX and bind preview to a layout container with strict error handling.")
    public void InitializeCamera(HVArrangement layoutContainer) {
        if (layoutContainer == null) {
            Log.e(TAG, "Layout container is null!");
            return;
        }

        container.$form().runOnUiThread(() -> {
            try {
                ViewGroup viewGroup = (ViewGroup) layoutContainer.getView();
                if (viewGroup == null) return;
                
                viewGroup.removeAllViews();

                previewView = new PreviewView(context);
                viewGroup.addView(previewView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));

                startCamera();
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize camera container: " + e.getMessage());
            }
        });
    }

    private void startCamera() {
        try {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);

            cameraProviderFuture.addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    imageCapture = new ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build();

                    CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                    cameraProvider.unbindAll();
                    camera = cameraProvider.bindToLifecycle(
                            (LifecycleOwner) context, cameraSelector, preview, imageCapture
                    );

                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "Camera binding failed: " + e.getMessage());
                }
            }, ContextCompat.getMainExecutor(context));
        } catch (Exception e) {
            Log.e(TAG, "ProcessCameraProvider initialization failed: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Take a picture and safely return it as a Base64 encoded string via event.")
    public void TakePicture() {
        if (imageCapture == null) {
            Log.w(TAG, "ImageCapture is not initialized yet.");
            return;
        }

        try {
            imageCapture.takePicture(ContextCompat.getMainExecutor(context), new ImageCapture.OnImageCapturedCallback() {
                @Override
                public void onCaptureSuccess(@androidx.annotation.NonNull ImageProxy image) {
                    try {
                        byte[] bytes = imageProxyToByteArray(image);
                        image.close();

                        if (bytes != null && bytes.length > 0) {
                            String base64String = Base64.encodeToString(bytes, Base64.DEFAULT);
                            OnImageCaptured(base64String);
                        } else {
                            Log.e(TAG, "Captured image bytes are empty.");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing captured image: " + e.getMessage());
                        image.close();
                    }
                }

                @Override
                public void onError(@androidx.annotation.NonNull ImageCaptureException exception) {
                    Log.e(TAG, "Capture failed: " + exception.getMessage(), exception);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Exception during takePicture: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Turn the camera flash on or off safely.")
    public void SetFlash(boolean enable) {
        try {
            if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                camera.getCameraControl().enableTorch(enable);
            } else {
                Log.w(TAG, "Flash unit is not available on this device.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set flash mode: " + e.getMessage());
        }
    }

    @SimpleEvent(description = "Triggered when a picture is successfully captured and converted to Base64.")
    public void OnImageCaptured(String base64Data) {
        EventDispatcher.dispatchEvent(this, "OnImageCaptured", base64Data);
    }

    private byte[] imageProxyToByteArray(ImageProxy image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return null;

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            // 压缩为 JPEG，画质 85%，兼顾传输速度和清晰度
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream);
            bitmap.recycle();
            
            return outputStream.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Conversion to byte array failed: " + e.getMessage());
            return null;
        }
    }
}
