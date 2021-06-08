/*
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.Range;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat;

@TargetApi(21)
public class Camera2Session implements CameraSession {
  private static final String TAG = "Camera2Session";

  private static final Histogram camera2StartTimeMsHistogram =
      Histogram.createCounts("WebRTC.Android.Camera2.StartTimeMs", 1, 10000, 50);
  private static final Histogram camera2StopTimeMsHistogram =
      Histogram.createCounts("WebRTC.Android.Camera2.StopTimeMs", 1, 10000, 50);
  private static final Histogram camera2ResolutionHistogram = Histogram.createEnumeration(
      "WebRTC.Android.Camera2.Resolution", CameraEnumerationAndroid.COMMON_RESOLUTIONS.size());

  private static enum SessionState { RUNNING, STOPPED }

  private final Handler cameraThreadHandler;
  private final CreateSessionCallback callback;
  private final Events events;
  private final Context applicationContext;
  private final CameraManager cameraManager;
  private final SurfaceTextureHelper surfaceTextureHelper;
  private final String cameraId;
  private final int width;
  private final int height;
  private final int framerate;

  // Initialized at start
  private CameraCharacteristics cameraCharacteristics;
  private int cameraOrientation;
  private boolean isCameraFrontFacing;
  private int fpsUnitFactor;
  private CaptureFormat captureFormat;

  // Initialized when camera opens
  @Nullable private CameraDevice cameraDevice;
  @Nullable private Surface surface;
  @Nullable private ImageReader imageReader;

  // Initialized when capture session is created
  @Nullable private CameraCaptureSession captureSession;

  // State
  private SessionState state = SessionState.RUNNING;
  private boolean firstFrameReported;

  private CaptureRequest.Builder captureRequestBuilder;

  // Used only for stats. Only used on the camera thread.
  private final long constructionTimeNs; // Construction time of this class.

  private class CameraStateCallback extends CameraDevice.StateCallback {
    private String getErrorDescription(int errorCode) {
      switch (errorCode) {
        case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
          return "Camera device has encountered a fatal error.";
        case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
          return "Camera device could not be opened due to a device policy.";
        case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
          return "Camera device is in use already.";
        case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
          return "Camera service has encountered a fatal error.";
        case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
          return "Camera device could not be opened because"
              + " there are too many other open camera devices.";
        default:
          return "Unknown camera error: " + errorCode;
      }
    }

    @Override
    public void onDisconnected(CameraDevice camera) {
      checkIsOnCameraThread();
      final boolean startFailure = (captureSession == null) && (state != SessionState.STOPPED);
      state = SessionState.STOPPED;
      stopInternal();
      if (startFailure) {
        callback.onFailure(FailureType.DISCONNECTED, "Camera disconnected / evicted.");
      } else {
        events.onCameraDisconnected(Camera2Session.this);
      }
    }

    @Override
    public void onError(CameraDevice camera, int errorCode) {
      checkIsOnCameraThread();
      reportError(getErrorDescription(errorCode));
    }

    @Override
    public void onOpened(CameraDevice camera) {
      checkIsOnCameraThread();

      Logging.d(TAG, "Camera opened.");
      cameraDevice = camera;

      try {
        surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);
        surface = new Surface(surfaceTextureHelper.getSurfaceTexture());

        camera.createCaptureSession(
            Arrays.asList(surface, imageReader.getSurface()), new CaptureSessionCallback(), cameraThreadHandler);
      } catch (Exception e) {
        reportError("Failed to create capture session. " + e);
        return;
      }
    }

    @Override
    public void onClosed(CameraDevice camera) {
      checkIsOnCameraThread();

      Logging.d(TAG, "Camera device closed.");
      events.onCameraClosed(Camera2Session.this);
    }
  }

  private class CaptureSessionCallback extends CameraCaptureSession.StateCallback {
    @Override
    public void onConfigureFailed(CameraCaptureSession session) {
      checkIsOnCameraThread();
      session.close();
      reportError("Failed to configure capture session.");
    }

    @Override
    public void onConfigured(CameraCaptureSession session) {
      checkIsOnCameraThread();
      Logging.d(TAG, "Camera capture session configured.");
      captureSession = session;
      try {
        /*
         * The viable options for video capture requests are:
         * TEMPLATE_PREVIEW: High frame rate is given priority over the highest-quality
         *   post-processing.
         * TEMPLATE_RECORD: Stable frame rate is used, and post-processing is set for recording
         *   quality.
         */
        captureRequestBuilder =
            cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        // Set auto exposure fps range.
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            new Range<Integer>(captureFormat.framerate.min / fpsUnitFactor,
                captureFormat.framerate.max / fpsUnitFactor));
        captureRequestBuilder.set(
            CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        chooseStabilizationMode(captureRequestBuilder);
        chooseFocusMode(captureRequestBuilder);

        captureRequestBuilder.addTarget(surface);
        session.setRepeatingRequest(
            captureRequestBuilder.build(), new CameraCaptureCallback(), cameraThreadHandler);
      } catch (CameraAccessException e) {
        reportError("Failed to start capture request. " + e);
        return;
      }

      surfaceTextureHelper.startListening((VideoFrame frame) -> {
        checkIsOnCameraThread();

        if (state != SessionState.RUNNING) {
          Logging.d(TAG, "Texture frame captured but camera is no longer running.");
          return;
        }

        if (!firstFrameReported) {
          firstFrameReported = true;
          final int startTimeMs =
              (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
          camera2StartTimeMsHistogram.addSample(startTimeMs);
        }

        // Undo the mirror that the OS "helps" us with.
        // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
        // Also, undo camera orientation, we report it as rotation instead.
        final VideoFrame modifiedFrame =
            new VideoFrame(CameraSession.createTextureBufferWithModifiedTransformMatrix(
                               (TextureBufferImpl) frame.getBuffer(),
                               /* mirror= */ isCameraFrontFacing,
                               /* rotation= */ -cameraOrientation),
                /* rotation= */ getFrameOrientation(), frame.getTimestampNs());
        events.onFrameCaptured(Camera2Session.this, modifiedFrame);
        modifiedFrame.release();
      });
      Logging.d(TAG, "Camera device successfully started.");
      callback.onDone(Camera2Session.this);
    }

    // Prefers optical stabilization over software stabilization if available. Only enables one of
    // the stabilization modes at a time because having both enabled can cause strange results.
    private void chooseStabilizationMode(CaptureRequest.Builder captureRequestBuilder) {
      final int[] availableOpticalStabilization = cameraCharacteristics.get(
          CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
      if (availableOpticalStabilization != null) {
        for (int mode : availableOpticalStabilization) {
          if (mode == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) {
            captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            Logging.d(TAG, "Using optical stabilization.");
            return;
          }
        }
      }
      // If no optical mode is available, try software.
      final int[] availableVideoStabilization = cameraCharacteristics.get(
          CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
      if (availableVideoStabilization != null) {
        for (int mode : availableVideoStabilization) {
          if (mode == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
            captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            Logging.d(TAG, "Using video stabilization.");
            return;
          }
        }
      }
      Logging.d(TAG, "Stabilization not available.");
    }

    private void chooseFocusMode(CaptureRequest.Builder captureRequestBuilder) {
      final int[] availableFocusModes =
          cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
      for (int mode : availableFocusModes) {
        if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
          captureRequestBuilder.set(
              CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
          Logging.d(TAG, "Using continuous video auto-focus.");
          return;
        }
      }
      Logging.d(TAG, "Auto-focus is not available.");
    }
  }

  private static class CameraCaptureCallback extends CameraCaptureSession.CaptureCallback {
    @Override
    public void onCaptureFailed(
        CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
      Logging.d(TAG, "Capture failed: " + failure);
    }
  }

  private void initImageReader() {
    List<Size> sizes = Camera2Enumerator.getSupportedSizes(cameraCharacteristics);
    Size maxSize = sizes.get(0);
    //Size maxSize16_9 = null;
    for (Size s : sizes) {
      if (s.height * s.width > maxSize.height * maxSize.width ) {
        maxSize = s;
      }
		  /*if (s.height * 16 == s.width * 9 && (maxSize16_9 == null || s.height * s.width > maxSize16_9.height * maxSize16_9.width)) {
			  maxSize16_9 = s;
		  }*/
    }
    if (this.imageReader != null) {
		    // dispose it
		  this.imageReader.close();
		  this.imageReader = null;
    }
	  /*if (maxSize16_9 != null)
		  this.imageReader = ImageReader.newInstance(maxSize16_9.width, maxSize16_9.height, ImageFormat.JPEG, 1);
	  else*/
    this.imageReader = ImageReader.newInstance(maxSize.width, maxSize.height, ImageFormat.JPEG, 1);
  }

  public static void create(CreateSessionCallback callback, Events events,
      Context applicationContext, CameraManager cameraManager,
      SurfaceTextureHelper surfaceTextureHelper, String cameraId, int width, int height,
      int framerate) {
    new Camera2Session(callback, events, applicationContext, cameraManager, surfaceTextureHelper,
        cameraId, width, height, framerate);
  }

  private Camera2Session(CreateSessionCallback callback, Events events, Context applicationContext,
      CameraManager cameraManager, SurfaceTextureHelper surfaceTextureHelper, String cameraId,
      int width, int height, int framerate) {
    Logging.d(TAG, "Create new camera2 session on camera " + cameraId);

    constructionTimeNs = System.nanoTime();

    this.cameraThreadHandler = new Handler();
    this.callback = callback;
    this.events = events;
    this.applicationContext = applicationContext;
    this.cameraManager = cameraManager;
    this.surfaceTextureHelper = surfaceTextureHelper;
    this.cameraId = cameraId;
    this.width = width;
    this.height = height;
    this.framerate = framerate;
    this.imageReader = null;

    start();
  }

  private void start() {
    checkIsOnCameraThread();
    Logging.d(TAG, "start");

    try {
      cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
    } catch (CameraAccessException | IllegalArgumentException e) {
      reportError("getCameraCharacteristics(): " + e.getMessage());
      return;
    }
    cameraOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    isCameraFrontFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
        == CameraMetadata.LENS_FACING_FRONT;

    findCaptureFormat();
    openCamera();
    initImageReader();
  }

  private void findCaptureFormat() {
    checkIsOnCameraThread();

    Range<Integer>[] fpsRanges =
        cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
    fpsUnitFactor = Camera2Enumerator.getFpsUnitFactor(fpsRanges);
    List<CaptureFormat.FramerateRange> framerateRanges =
        Camera2Enumerator.convertFramerates(fpsRanges, fpsUnitFactor);
    List<Size> sizes = Camera2Enumerator.getSupportedSizes(cameraCharacteristics);
    Logging.d(TAG, "Available preview sizes: " + sizes);
    Logging.d(TAG, "Available fps ranges: " + framerateRanges);

    if (framerateRanges.isEmpty() || sizes.isEmpty()) {
      reportError("No supported capture formats.");
      return;
    }

    final CaptureFormat.FramerateRange bestFpsRange =
        CameraEnumerationAndroid.getClosestSupportedFramerateRange(framerateRanges, framerate);

    final Size bestSize = CameraEnumerationAndroid.getClosestSupportedSize(sizes, width, height);
    CameraEnumerationAndroid.reportCameraResolution(camera2ResolutionHistogram, bestSize);

    captureFormat = new CaptureFormat(bestSize.width, bestSize.height, bestFpsRange);
    Logging.d(TAG, "Using capture format: " + captureFormat);
  }

  private void openCamera() {
    checkIsOnCameraThread();

    Logging.d(TAG, "Opening camera " + cameraId);
    events.onCameraOpening();

    try {
      cameraManager.openCamera(cameraId, new CameraStateCallback(), cameraThreadHandler);
    } catch (CameraAccessException | IllegalArgumentException e) {
      reportError("Failed to open camera: " + e);
      return;
    }
  }

  @Override
  public void stop() {
    Logging.d(TAG, "Stop camera2 session on camera " + cameraId);
    checkIsOnCameraThread();
    if (state != SessionState.STOPPED) {
      final long stopStartTime = System.nanoTime();
      state = SessionState.STOPPED;
      stopInternal();
      final int stopTimeMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
      camera2StopTimeMsHistogram.addSample(stopTimeMs);
    }
  }

  private void stopInternal() {
    Logging.d(TAG, "Stop internal");
    checkIsOnCameraThread();

    surfaceTextureHelper.stopListening();

    if (captureSession != null) {
      captureSession.close();
      captureSession = null;
    }
    if (surface != null) {
      surface.release();
      surface = null;
    }
    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }

    Logging.d(TAG, "Stop done");
  }

  private void reportError(String error) {
    checkIsOnCameraThread();
    Logging.e(TAG, "Error: " + error);

    final boolean startFailure = (captureSession == null) && (state != SessionState.STOPPED);
    state = SessionState.STOPPED;
    stopInternal();
    if (startFailure) {
      callback.onFailure(FailureType.ERROR, error);
    } else {
      events.onCameraError(this, error);
    }
  }

  private int getFrameOrientation() {
    int rotation = CameraSession.getDeviceOrientation(applicationContext);
    if (!isCameraFrontFacing) {
      rotation = 360 - rotation;
    }
    return (cameraOrientation + rotation) % 360;
  }

  private void checkIsOnCameraThread() {
    if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
      throw new IllegalStateException("Wrong thread");
    }
  }

  @Override
  public boolean hasTorch() {
      Logging.d(TAG, "Has Torch Called for:" + cameraId);
      boolean hasTorch = cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
      Logging.d(TAG, "Torch Available:" + hasTorch + " For Camera:" +  cameraId);
      return hasTorch;
  }

  @Override
  public boolean setTorch(boolean enable) {
      try {
        Logging.d(TAG, "Set Torch:" + enable);
        if (enable) {
          captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
          captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
          return true;
        } else {
          captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
          captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
          return false;
        }
      } catch (CameraAccessException e) {
        Logging.d(TAG, "Set flash mode failed");
      }
      return false;
  }


  @Override
  public void processSingleRequest(CameraCapturer.SingleCaptureCallBack captureCallback, Handler captureHandler) {
      try {
          final CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

		      captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
		      captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
          captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getFrameOrientation());
		      captureRequestBuilder.addTarget(imageReader.getSurface());

          imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
              @Override
              public void onImageAvailable(ImageReader reader) {
                  Logging.d(TAG, "SNAPSHOT: Image available");
    				      Image image = null;

                  try {
                      image = reader.acquireLatestImage();
                      if (image == null) {
                          captureCallback.captureFailed("No available image from ImageReader");
                          return ;
                      }

                      ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                      byte[] imageBytes = new byte[buffer.remaining()];
                      buffer.get(imageBytes);
                      image.close();
                      captureCallback.captureSuccess(imageBytes, getFrameOrientation());
                  } catch (Exception e) {
                      Logging.d(TAG, "SNAPSHOT: Image acquire/conversion failed due to " + e.toString());
                      if (image != null) {
                        image.close();
                      }
                      captureCallback.captureFailed("SNAPSHOT: Image acquire/conversion failed due to " + e.toString());
                  }
              }
           }, captureHandler);

           captureSession.capture(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    Logging.d(TAG, "SNAPSHOT: capture completed");
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    Logging.d(TAG, "SNAPSHOT: capture failed due to " + failure.toString());
                    captureHandler.post(() -> captureCallback.captureFailed(failure.toString()) );
                }
            }, null);
      } catch (Exception e) {
          Logging.e(TAG, "SNAPSHOT: failed due to " + e.getMessage());
          captureCallback.captureFailed("SNAPSHOT: failed due to " + e.getMessage());
      }
  }
}
