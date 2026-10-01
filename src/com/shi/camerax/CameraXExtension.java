package com.shi.camerax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
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
import java.util.concurrent.ExecutionException;

@DesignerComponent(
        version = 1,
        description = "A powerful CameraX extension supporting preview, flash control, and Base64 image capture.",
        category = ComponentCategory.EXTENSION,
        nonVisible = false,
        icon = ""
)
@SimpleObject(external = true)
@UsesLibraries(libraries = "camera-camera2:1.3.1, camera-view:1.3.1, camera-lifecycle:1.3.1")
@UsesPermissions(permissionNames = "android.permission.CAMERA")
public class CameraXExtension extends AndroidNonvisibleComponent {

    private final ComponentContainer container;
    private final Context context;
    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private boolean isFlashOn = false;

    public CameraXExtension(ComponentContainer container) {
        super(container.$form());
        this.container = container;
        this.context = container.$context();
    }

    @SimpleFunction(description = "Initialize CameraX and bind preview to a layout container.")
    public void InitializeCamera(HVArrangement layoutContainer) {
        if (layoutContainer == null) return;
        
        container.$form().runOnUiThread(() -> {
            ViewGroup viewGroup = (ViewGroup) layoutContainer.getView();
            viewGroup.removeAllViews();

            previewView = new PreviewView(context);
            viewGroup.addView(previewView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            startCamera();
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                androidx.camera.core.Preview preview = new androidx.camera.core.Preview.Builder().build();
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
                // Handle exceptions
            }
        }, ContextCompat.getMainExecutor(context));
    }

    @SimpleFunction(description = "Take a picture and return it as a Base64 encoded string via event.")
    public void TakePicture() {
        if (imageCapture == null) return;

        imageCapture.takePicture(ContextCompat.getMainExecutor(context), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@androidx.annotation.NonNull ImageProxy image) {
                byte[] bytes = imageProxyToByteArray(image);
                image.close();

                String base64String = Base64.encodeToString(bytes, Base64.DEFAULT);
                OnImageCaptured(base64String);
            }

            @Override
            public void onError(@androidx.annotation.NonNull ImageCaptureException exception) {
                // Handle error
            }
        });
    }

    @SimpleFunction(description = "Turn the camera flash on or off.")
    public void SetFlash(boolean enable) {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(enable);
            isFlashOn = enable;
        }
    }

    @SimpleEvent(description = "Triggered when a picture is successfully captured and converted to Base64.")
    public void OnImageCaptured(String base64Data) {
        EventDispatcher.dispatchEvent(this, "OnImageCaptured", base64Data);
    }

    private byte[] imageProxyToByteArray(ImageProxy image) {
        java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
        return outputStream.toByteArray();
    }
}
