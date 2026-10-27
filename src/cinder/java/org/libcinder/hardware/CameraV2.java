package org.libcinder.hardware;

import org.libcinder.app.CinderNativeActivity;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** \class CameraV2
 *
 */
@TargetApi(21)
public class CameraV2 extends org.libcinder.hardware.Camera {

    private static final String TAG = "cinder|CameraV2";

    private CameraManager mCameraManager = null;

    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private CameraDevice mCamera = null;

    private static int MAX_WAIT_TIME_MS = 10000;
    /** How long a stop may wait for the camera lock.
     *
     *  Deliberately far below MAX_WAIT_TIME_MS, and below the five seconds
     *  Android allows a window to answer an input event: stopDevice runs on
     *  the native activity's own thread, which is the thread that dispatches
     *  input, so a stop that waits longer than that is a stop that gets the
     *  process killed. Closing a camera is not worth waiting seconds for.
     *
     *  Small, because these waits are serial: a look that opened the camera
     *  twice tears down twice, and the app has only a short grace period
     *  after it goes to the background in which it may still start its
     *  foreground service. Two seconds of camera teardown spent that grace,
     *  and the handset went deaf instead of listening on from the service.
     *
     *  On real hardware none of this should ever fire -- a capture session
     *  is created in about a tenth of a second and the lock is free by the
     *  time a stop asks for it. It fires on the emulator, whose virtual
     *  camera never finishes creating one at all. */
    private static int STOP_WAIT_TIME_MS = 150;

    private HandlerThread mCameraHandlerThread;
    private Handler mCameraHandler;

    private static final int MAX_PREVIEW_IMAGES = 2;
    private int mPreviewImageFormat = ImageFormat.YUV_420_888;
    private ImageReader mImageReader = null;
    private Surface mImageReaderSurface = null;

    private SurfaceTexture mDummyTexture = null;
    private Surface mPreviewSurface = null;

    private CaptureRequest mPreviewRequest = null;
    private CaptureRequest.Builder mPreviewRequestBuilder = null;
    private CameraCaptureSession mPreviewCaptureSession = null;
    private AtomicBoolean mPreviewCaptureSessionCreated = new AtomicBoolean(false);

    private AtomicBoolean mPingBack = new AtomicBoolean(false);

    // =============================================================================================
    // Callback/listener member vars
    // =============================================================================================

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            // The native side reads mPixels as NV21 — all of Y, then
            // interleaved VU (see ConvertYUV2RGB in hardware/Camera.cpp).
            // YUV_420_888 arrives as three planes with their own row and
            // pixel strides, so the frame is repacked here rather than
            // assumed contiguous. The image is closed each time; the reader
            // stays open — it is the session's output for as long as the
            // capture runs. (This body was dead code for years, and with it
            // every camera effect on Camera2 devices: nothing ever set
            // mNewFrameAvailable, so no frame was ever fetched.)
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if ((null == image) || (image.getPlanes().length < 3)) {
                    return;
                }
                final int w = image.getWidth();
                final int h = image.getHeight();
                final Image.Plane yPlane = image.getPlanes()[0];
                final Image.Plane uPlane = image.getPlanes()[1];
                final Image.Plane vPlane = image.getPlanes()[2];
                final ByteBuffer yBuf = yPlane.getBuffer();
                final ByteBuffer uBuf = uPlane.getBuffer();
                final ByteBuffer vBuf = vPlane.getBuffer();

                lockPixels();
                try {
                    final int size = w * h + (w * h) / 2;
                    if ((null == mPixels) || (mPixels.length != size)) {
                        mPixels = new byte[size];
                    }

                    final int yRowStride = yPlane.getRowStride();
                    if (yRowStride == w) {
                        yBuf.position(0);
                        yBuf.get(mPixels, 0, w * h);
                    } else {
                        for (int row = 0; row < h; ++row) {
                            yBuf.position(row * yRowStride);
                            yBuf.get(mPixels, row * w, w);
                        }
                    }

                    final int uRowStride = uPlane.getRowStride();
                    final int uPixStride = uPlane.getPixelStride();
                    final int vRowStride = vPlane.getRowStride();
                    final int vPixStride = vPlane.getPixelStride();
                    int dst = w * h;
                    for (int row = 0; row < h / 2; ++row) {
                        for (int col = 0; col < w / 2; ++col) {
                            mPixels[dst++] = vBuf.get(row * vRowStride + col * vPixStride);
                            mPixels[dst++] = uBuf.get(row * uRowStride + col * uPixStride);
                        }
                    }
                }
                finally {
                    unlockPixels();
                }
                mNewFrameAvailable.set(true);
            }
            catch (Exception e) {
                Log.w(TAG, "onImageAvailable: " + e.getMessage());
            }
            finally {
                if (null != image) {
                    image.close();
                }
            }
        }
    };

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraOpenCloseLock.release();
            mCamera = camera;

            Log.i(TAG, "Thread ID: " + Thread.currentThread().getId() + " | (CameraDevice.StateCallback.onOpened)");
        }

        /*
        @Override
        public void onClosed(CameraDevice camera) {
            Log.i(TAG, "Thread ID: " + Thread.currentThread().getId() + " | (CameraDevice.StateCallback.onClosed)");
        }
        */

        @Override
        public void onDisconnected(CameraDevice camera) {
            mCameraOpenCloseLock.release();
            camera.close();
            mCamera = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            mCameraOpenCloseLock.release();
            camera.close();
            mCamera = null;
        }
    };

    // =============================================================================================
    // CameraV2
    // =============================================================================================

    /** CameraV2
     *
     */
    public CameraV2(CinderNativeActivity activity) {
        super(activity);
        Log.i(TAG, "CameraV2 constructed");
    }

    /** checkCameraPresence
     *
     */
    public static void checkCameraPresence(boolean[] back, boolean[] front) {
        back[0] = false;
        front[0] = false;

        CameraManager cm = (CameraManager)CinderNativeActivity.getInstance().getSystemService(Context.CAMERA_SERVICE);

        try {
            for (String cameraId : cm.getCameraIdList()) {
                CameraCharacteristics info = cm.getCameraCharacteristics(cameraId);

                int facing = info.get(CameraCharacteristics.LENS_FACING);
                if (CameraCharacteristics.LENS_FACING_BACK == facing) {
                    back[0] = true;
                } else if (CameraCharacteristics.LENS_FACING_FRONT == facing) {
                    front[0] = true;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("failed getting camera: " + e);
        }
    }

    public DeviceInfo[] enumerateDevices() {
        ArrayList<DeviceInfo> devices = new ArrayList<>();

        try {
            CameraManager cm = (CameraManager)getActivity().getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics info = cm.getCameraCharacteristics(id);

                int facing = info.get(CameraCharacteristics.LENS_FACING);
                boolean frontFacing = (CameraCharacteristics.LENS_FACING_FRONT == facing);

                StreamConfigurationMap scm = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                List<Size> sizes = Arrays.asList(scm.getOutputSizes(SurfaceTexture.class));
                int[] resolutions = new int[2*sizes.size()];
                for(int j = 0; j < sizes.size(); ++j) {
                    Size size = sizes.get(j);
                    resolutions[2*j + 0] = size.getWidth();
                    resolutions[2*j + 1] = size.getHeight();
                }

                devices.add(new DeviceInfo(id, frontFacing, resolutions));

                if (!devices.isEmpty()) {
                    mCachedDeviceInfos = new DeviceInfo[devices.size()];
                    for (int i = 0; i < devices.size(); ++i) {
                        mCachedDeviceInfos[i] = devices.get(i);
                    }
                }
            }
        }
        catch(Exception e ) {
            Log.w(TAG, "enumerateDevices error: unable to get camera device list");
        }

        DeviceInfo[] result = null;
        if(! devices.isEmpty()) {
            result = new DeviceInfo[devices.size()];
            for(int i = 0; i < devices.size(); ++i) {
                result[i] = devices.get(i);
            }
        }

        return mCachedDeviceInfos;
    }

    /** cameraThreadFn_setPreviewTexture
     *
     * [Only call from mCameraHandlerThread]
     *
     */
    private void cameraThreadFn_setPreviewTexture(final SurfaceTexture previewTexture) {
        try {
            mCameraOpenCloseLock.acquire();

            if(null != previewTexture) {
                mPreviewTexture = previewTexture;
                mPreviewTexture.setDefaultBufferSize(getWidth(), getHeight());
                mPreviewSurface = new Surface(mPreviewTexture);
            }
            else {
                mDummyTexture.setDefaultBufferSize(getWidth(), getHeight());
                mPreviewSurface = new Surface(mDummyTexture);
            }

        }
        catch(Exception e) {
            Log.e(TAG, "cameraThreadFn_setPreviewTexture error: " + e.getMessage());
        }
        finally {
            mCameraOpenCloseLock.release();
        }
    }

    /** cameraThreadFn_createCaptureSession
     *
     * [Only call from mCameraHandlerThread]
     *
     */
    private void cameraThreadFn_createCaptureSession() {
        Log.i(TAG, "cameraThreadFn_createCaptureSession ENTER");

        try {
            mCameraOpenCloseLock.acquire();

            Log.i(TAG, "----- cameraThreadFn_createCaptureSession: Thread ID=" + Thread.currentThread().getId());

            if (mPreviewCaptureSessionCreated.get()) {
                Log.i(TAG, "----- cameraThreadFn_createCaptureSession: exiting - session already created");
                return;
            }

            if (null == mImageReaderSurface) {
                throw new RuntimeException("mImageReaderSurface is null");
            }

            if (null == mPreviewSurface) {
                throw new RuntimeException("mPreviewSurface is null");
            }

            // Allocate CaptureRequest.Builder
            mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mImageReaderSurface);
            mPreviewRequestBuilder.addTarget(mPreviewSurface);

            mCamera.createCaptureSession(
                Arrays.asList(mImageReaderSurface, mPreviewSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        //Log.i(TAG, "CameraCaptureSession.StateCallback.onConfigured");

                        if (null == mCamera) {
                            return;
                        }

                        mPreviewCaptureSession = session;
                        try {
                            mPreviewRequest = mPreviewRequestBuilder.build();
                            mPreviewCaptureSession.setRepeatingRequest(mPreviewRequest, null, mCameraHandler);
                        } catch (Exception e) {
                            //throw new RuntimeException("CameraCaptureSession.setRepeatingRequest error: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        String config = "(res:" + getWidth() + "x" + getHeight() + ", fmt:" + Camera.imageFormatString(mPreviewImageFormat) + ")";
                        throw new RuntimeException("Unable to create capture session using current configuration " + config);
                    }
                },
                null
            );

            mPreviewCaptureSessionCreated.set(true);
        }
        catch(Exception e) {
            Log.e(TAG, "cameraThreadFn_createCaptureSession error: " + e.getMessage());
        }
        finally {
            mCameraOpenCloseLock.release();
        }

        Log.i(TAG, "cameraThreadFn_createCaptureSession EXIT");
    }

    /** cameraThreadFn_destroyCaptureSession
     *
     * [Only call from mCameraHandlerThread]
     *
     */
    private void cameraThreadFn_destroyCaptureSession() {
        Log.i(TAG, "cameraThreadFn_destroyCaptureSession ENTER");

        if(! mPreviewCaptureSessionCreated.get()) {
            Log.i(TAG, "cameraThreadFn_destroyCaptureSession: exiting - no active session");
            return;
        }

        try {
            mCameraOpenCloseLock.acquire();

            if(null != mPreviewCaptureSession) {
                mPreviewCaptureSession.abortCaptures();
                mPreviewCaptureSession.close();
                mPreviewCaptureSession = null;
            }

            mPreviewRequestBuilder = null;

            mPreviewCaptureSessionCreated.set(false);
        }
        catch(Exception e) {
            Log.e(TAG, "cameraThreadFn_destroyCaptureSession error: " + e.getMessage());
        }
        finally {
            mCameraOpenCloseLock.release();
        }

        Log.i(TAG, "cameraThreadFn_destroyCaptureSession EXIT");
    }

    /** startCameraHandlerThread
     *
     */
    private void startCameraThread() {
        Log.i(TAG, "startCameraThread ENTER");

        // Create camera handler thread
        try {
            if (null == mCameraHandlerThread) {
                mCameraHandlerThread = new HandlerThread("camera-handler-thread");
                mCameraHandlerThread.start();
                Log.i(TAG, "Thread ID: " + mCameraHandlerThread.getId() + "| (mCameraHandlerThread.getId())");
            }
        }
        catch(Exception e) {
            throw new RuntimeException("camera handler thread error: " + e.getMessage());
        }

        // Create camera handler
        try {
            if (null == mCameraHandler) {
                mCameraHandler = new Handler(mCameraHandlerThread.getLooper());
            }
        }
        catch(Exception e) {
            throw new RuntimeException("camera handler create error: " + e.getMessage());
        }

        Log.i(TAG, "startCameraThread EXIT");
    }

    /** stopCameraHandlerThread
     *
     */
    private void stopCameraThread() {
        Log.i(TAG, "stopCameraThread ENTER");

        try {
            if (null != mCameraHandlerThread) {
                flushCameraHandler();

                mCameraHandlerThread.quitSafely();
                // Bounded for the same reason, and quitSafely cannot help
                // here: it stops the looper taking new work, it cannot
                // interrupt a runnable already blocked inside the camera
                // lock. The references are dropped either way -- a thread
                // that is still finishing will exit on its own, and the next
                // start builds a fresh one.
                mCameraHandlerThread.join(STOP_WAIT_TIME_MS);
                if (mCameraHandlerThread.isAlive()) {
                    Log.w(TAG, "stopCameraThread: the camera thread is still finishing, letting it go");
                }
                mCameraHandlerThread = null;
                mCameraHandler = null;
            }

        } catch (Exception e) {
            throw new RuntimeException("camera handler thread stop error: " + e.getMessage());
        }

        mCameraManager = null;

        Log.i(TAG, "stopCameraThread EXIT");
    }

    /** startPreview
     *
     */
    private void startPreview() {
        Log.i(TAG, "startPreview ENTER");

        try {
            if(null != mCameraHandler) {
                mCameraHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        cameraThreadFn_createCaptureSession();
                    }
                });
            }
        }
        catch(Exception e) {
            Log.e(TAG, "startPreview error: " + e.getMessage());
        }

        Log.i(TAG, "startPreview EXIT");
    }

    /** stopPreview
     *
     */
    private void stopPreview() {
        Log.i(TAG, "stopPreview ENTER");

        if(! mPreviewCaptureSessionCreated.get()) {
            Log.i(TAG, "stopPreview: exiting - no active session");
            return;
        }

        try {
            mCameraOpenCloseLock.acquire();

            if(null != mPreviewCaptureSession) {
                mPreviewCaptureSession.abortCaptures();
                mPreviewCaptureSession.close();
                mPreviewCaptureSession = null;
            }

            mPreviewRequestBuilder = null;

            mPreviewCaptureSessionCreated.set(false);
        }
        catch(Exception e) {
            Log.e(TAG, "stopPreview error: " + e.getMessage());
        }
        finally {
            mCameraOpenCloseLock.release();
        }

        /*
        try {
            if(null != mCameraHandler) {
                mCameraHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        cameraThreadFn_destroyCaptureSession();
                    }
                });
            }
        }
        catch(Exception e) {
            Log.e(TAG, "stopPreview error: " + e.getMessage());
        }
        */

        Log.i(TAG, "stopPreview EXIT");
    }

    /** startDevice
     *
     */
    private void startDevice(String deviceId) {
        if((null != mActiveDeviceId) && mActiveDeviceId.equals(deviceId)) {
            return;
        }

        if(null != mActiveDeviceId) {
            stopDevice();
        }

        try {
            if(null == deviceId) {
                throw new RuntimeException("parameter deviceId is null");
            }

            mActiveDeviceId = deviceId;

            Size previewSize = new Size(mPreferredPreviewWidth, mPreferredPreviewHeight);
            if((0 == mPreferredPreviewWidth) && (0 == mPreferredPreviewHeight)) {
                previewSize = getOptimalPreviewSize(mActiveDeviceId);
                if (null == previewSize) {
                    throw new RuntimeException("couldn't getInstance preview size for Camera " + mCamera.getId());
                }
            }
            setPreferredPreviewSize(previewSize.getWidth(), previewSize.getHeight());

            // We create this here since the resolution is device dependent.
            mImageReader = ImageReader.newInstance(getWidth(), getHeight(), mPreviewImageFormat, MAX_PREVIEW_IMAGES);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mCameraHandler);
            mImageReaderSurface = mImageReader.getSurface();

            // Readjust size to match device
            mDummyTexture.setDefaultBufferSize(getWidth(), getHeight());
            if(null != mPreviewTexture) {
                mPreviewTexture.setDefaultBufferSize(getWidth(), getHeight());
            }

            // Acquire lock to start device
            if (! mCameraOpenCloseLock.tryAcquire(MAX_WAIT_TIME_MS, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("timed out acquiring camera lock");
            }
            Log.i(TAG, "Thread ID: " + Thread.currentThread().getId() + " | (CameraManager.openCamera)");
            mCameraManager.openCamera(mActiveDeviceId, mStateCallback, mCameraHandler);


            Log.i(TAG, "Started Camera " + deviceId + ": res=" + getWidth() + "x" + getHeight() + ", fmt=" + Camera.imageFormatString(mPreviewImageFormat));
        }
        catch(Exception e) {
            Log.e(TAG, "startDevice error: " + e.getMessage());
        }
    }

    /** stopDevice
     *
     */
    private void stopDevice() {
        Log.i(TAG, "stopDevice ENTER");

        // Bounded, the way startDevice's acquire already is. An unbounded
        // acquire here deadlocks whenever the capture session is still being
        // created: cameraThreadFn_createCaptureSession holds this same
        // semaphore and only releases it when the session callback arrives,
        // so stopping a camera look sooner than that waited for ever. The
        // camera thread never finished, and the next time the app went to
        // the background the system killed it for not answering input.
        boolean locked = false;
        try {
            locked = mCameraOpenCloseLock.tryAcquire(STOP_WAIT_TIME_MS, TimeUnit.MILLISECONDS);
            if(! locked) {
                // Closing without the lock is worse than closing with it and
                // better than not closing at all: the camera is going away
                // either way, and a device left open is a device the next
                // look cannot have.
                Log.w(TAG, "stopDevice: timed out acquiring the camera lock, closing anyway");
            }

            mActiveDeviceId = null;

            if(null != mCamera) {
                mCamera.close();
                mCamera = null;
            }
        }
        catch(Exception e) {
            Log.e(TAG, "stopDevice error: " + e.getMessage());
        }
        finally {
            // Only what we took. The old code released unconditionally, so an
            // acquire that threw handed back a permit it never held and left
            // the semaphore counting up.
            if(locked) {
                mCameraOpenCloseLock.release();
            }
        }

        /*
        try {
            if((null != mCameraHandler) && (null != mCameraHandlerThread) && (mCameraHandlerThread.isAlive())) {
                mCameraHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        cameraThreadFn_stopCamera();
                    }
                });
            }
        }
        catch(Exception e) {
            Log.e(TAG, "stopDevice error: " + e.getMessage());
        }
        */


        Log.i(TAG, "stopDevice EXIT");
    }

    private void flushCameraHandler() {
        try {
            mPingBack.set(false);
            if (null != mCameraHandler) {
                mCameraHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mPingBack.set(true);
                    }
                });

                // Bounded. This waits for a runnable to come back round the
                // camera thread, and that thread can be sitting inside
                // createCaptureSession holding the camera lock -- in which
                // case the runnable never runs and this span for ever. It is
                // called from stopCameraThread, on the native activity's own
                // thread, which is the thread that dispatches input: waiting
                // here longer than Android's five seconds gets the process
                // killed for not answering.
                final long deadline = System.currentTimeMillis() + STOP_WAIT_TIME_MS;
                while (!mPingBack.get() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(1);
                }
                if (!mPingBack.get()) {
                    Log.w(TAG, "flushCameraHandler: the camera thread did not answer, carrying on");
                }
                mCameraHandler.removeCallbacksAndMessages(null);
            }
        }
        catch(Exception e) {
            throw new RuntimeException("flushCamerHandler error: " + e.getMessage());
        }
    }

    /** updatePreviewTransform
     *
     */
    public void updatePreviewTransform(int viewWidth, int viewHeight, int orientation, int displayRotation) {
        float centerX = viewWidth/2.0f;
        float centerY = viewHeight/2.0f;
        float scaleX = 1;
        float scaleY = 1;

        float rotationDegrees = 0;
        switch(displayRotation) {
            case Surface.ROTATION_0: {
                rotationDegrees = 0;
            }
            break;

            case Surface.ROTATION_90: {
                rotationDegrees = 90;
                scaleX = (float)viewWidth/(float)viewHeight;
                scaleY = (float)viewHeight/(float)viewWidth;
            }
            break;

            case Surface.ROTATION_180: {
                rotationDegrees = 180;
            }
            break;

            case Surface.ROTATION_270: {
                rotationDegrees = 270;
                scaleX = (float)viewWidth/(float)viewHeight;
                scaleY = (float)viewHeight/(float)viewWidth;
            }
            break;
        }

        mPreviewTransform.reset();
        mPreviewTransform.postRotate(360.0f - rotationDegrees, centerX, centerY);
        mPreviewTransform.postScale(scaleX, scaleY, centerX, centerY);
    }

    // =============================================================================================
    // Camera functions
    // =============================================================================================

    /** initializeImpl
     *
     */
    @Override
    protected final void initializeImpl() {
        mCameraManager = (CameraManager)getActivity().getSystemService(Context.CAMERA_SERVICE);

        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                CameraCharacteristics info = mCameraManager.getCameraCharacteristics(cameraId);

                int facing = info.get(CameraCharacteristics.LENS_FACING);
                if (CameraCharacteristics.LENS_FACING_BACK == facing) {
                    mBackDeviceId = cameraId;
                } else if (CameraCharacteristics.LENS_FACING_FRONT == facing) {
                    mFrontDeviceId = cameraId;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("failed getting camera: " + e.getMessage());
        }
    }

    /** setPreviewTextureImpl
     *
     */
    @Override
    protected void setPreviewTextureImpl(final SurfaceTexture previewTexture) {
        Log.i(TAG, "setPreviewTextureImpl ENTER");

        try {
            flushCameraHandler();

            stopPreview();

            try {
                mCameraOpenCloseLock.acquire();

                if(null != previewTexture) {
                    mPreviewTexture = previewTexture;
                    mPreviewTexture.setDefaultBufferSize(getWidth(), getHeight());
                    mPreviewSurface = new Surface(mPreviewTexture);
                }
                else {
                    mDummyTexture.setDefaultBufferSize(getWidth(), getHeight());
                    mPreviewSurface = new Surface(mDummyTexture);
                }
            }
            catch(Exception e) {
                Log.e(TAG, "stopPreview error: " + e.getMessage());
            }
            finally {
                mCameraOpenCloseLock.release();
            }

            startPreview();

        }
        catch(Exception e) {
            Log.w(TAG, "failed setting preview texture: " + e.getMessage());
        }

        /*
        try {
            if((null != mCameraHandler) && (null != mCameraHandlerThread) && (mCameraHandlerThread.isAlive())) {
                mCameraHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        cameraThreadFn_destroyCaptureSession();
                        cameraThreadFn_setPreviewTexture(previewTexture);
                        cameraThreadFn_createCaptureSession();
                    }
                });
            }
        }
        catch(Exception e) {
            Log.w(TAG, "failed setting preview texture: " + e.getMessage());
        }
        */

        /*
        try {
            mPreviewTexture = previewTexture;
            if(null != mPreviewTexture) {
                mPreviewTexture.setDefaultBufferSize(getWidth(), getHeight());
            }

            cameraThreadFn_setPreviewTexture(mPreviewTexture);
        }
        catch(Exception e) {
            Log.w(TAG, "failed setting preview texture: " + e.getMessage());
        }
        */

        Log.i(TAG, "setPreviewTextureImpl EXIT");
    }

    /** startSessionImpl
     *  TODO withTorch is not used
     */
    @Override
    protected void startSessionImpl(String deviceId, final boolean withTorch) {
        if(null != deviceId) {
            startDevice(deviceId);
        }
        else {
            if(isBackCameraAvailable()) {
                startDevice(mBackDeviceId);
            } else if(isFrontCameraAvailable()) {
                startDevice(mFrontDeviceId);
            }
        }

        startPreview();
    }

    /** stopSessionImpl
     *
     */
    @Override
    protected void stopSessionImpl() {
        Log.i(TAG, "stopSessionImpl ENTER");

        stopPreview();
        stopDevice();

        Log.i(TAG, "stopSessionImpl EXIT");
    }

    /** startCaptureImpl
     *
     */
    @Override
    protected void startCaptureImpl(String deviceId) {
        // Create dummy texture
        if(null == mDummyTexture) {
            mDummyTexture = new SurfaceTexture(0);
        }

        // Create preview surface
        if(null == mPreviewSurface) {
            mPreviewSurface = new Surface(mDummyTexture);
        }

        startCameraThread();
        // NOTE: no startSession here. Camera.startCapture issues the session
        // start itself right after this returns; doing it here as well opened
        // the device twice in a row, and the second pass's defensive stop
        // closed the device the first pass was still opening — the capture
        // session then died on a null device and the camera stayed black
        // (CameraV1.startCaptureImpl has never started the session either).
    }

    /** stopCaptureImpl
     *
     */
    @Override
    protected void stopCaptureImpl() {
        Log.i(TAG, "stopCaptureImpl ENTER");

        stopSession();
        stopCameraThread();
        mPreviewSurface = null;
        mDummyTexture = null;

        Log.i(TAG, "stopCaptureImpl EXIT");
    }

//    /** switchToBackCameraImpl
//     *
//     */
//    @Override
//    protected void switchToBackCameraImpl() {
//        if (mActiveDeviceId.equals(mBackDeviceId)) {
//            return;
//        }
//
//        stopPreview();
//        startDevice(mBackDeviceId);
//        startPreview();
//    }
//
//    /** switchToFrontCameraImpl
//     *
//     */
//    @Override
//    protected void switchToFrontCameraImpl() {
//        if (mActiveDeviceId.equals(mFrontDeviceId)) {
//            return;
//        }
//
//        stopPreview();
//        startDevice(mFrontDeviceId);
//        startPreview();
//    }

    /** lockPixels
     *
     */
    @Override
    public byte[] lockPixels() {
        mPixelsMutex.lock();
        return mPixels;
    }

    /** unlockPixels
     *
     */
    @Override
    public void unlockPixels() {
        mPixelsMutex.unlock();
    }

    // =============================================================================================
    // Support methods
    // =============================================================================================

    /** getOptimalPreviewSize
     *
     */
    private Size getOptimalPreviewSize(String cameraId) {
        Size result = null;
        try {
            // Get the raw display size
            Point displaySize = getActivity().getDefaultDisplaySize();
            int rawDisplayWidth = displaySize.x;
            int rawDisplayHeight = displaySize.y;
            int rawDisplayArea = rawDisplayWidth*rawDisplayHeight;
            float rawAspectRatio = (float)rawDisplayWidth/(float)rawDisplayHeight;
            if(rawAspectRatio < 1.0f) {
                int t = rawDisplayWidth;
                rawDisplayWidth = rawDisplayHeight;
                rawDisplayHeight = t;
                rawAspectRatio = (float)rawDisplayWidth/(float)rawDisplayHeight;
            }

            // Get a list of output sizes and sort it
            CameraCharacteristics info = mCameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap scm = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            List<Size> sizes = Arrays.asList(scm.getOutputSizes(SurfaceTexture.class));
            Collections.sort(sizes, new Comparator<Size>() {
                @Override
                public int compare(Size a, Size b) {
                    int val = (a.getWidth()*a.getHeight()) - (b.getWidth()*b.getHeight());
                    return (val < 0) ? -1 : ((val > 0) ? 1 : 0);
                }
            });

            // Try to match up greatest area and aspect ratio
            for(Size size : sizes) {
                int width = size.getWidth();
                int height = size.getHeight();
                int area = width*height;
                float aspectRatio = (float)width/(float)height;
                if(aspectRatio < 1.0f) {
                    int t = width;
                    width = height;
                    height = t;
                    aspectRatio = (float)width/(float)height;
                }

                float aspectRatioDiff = Math.abs(rawAspectRatio - aspectRatio);
                if((rawDisplayArea >= area) && (aspectRatioDiff < 0.001f)) {
                    result = size;
                }
            }

            // If we didn't find a size - use just greatest area
            if(null == result) {
                for(Size size : sizes) {
                    int width = size.getWidth();
                    int height = size.getHeight();
                    int area = width*height;

                    if(rawDisplayArea >= area) {
                        result = size;
                    }
                }
            }
        }
        catch(Exception e) {
            Log.e(TAG, "getOptimalPreviewSize failed: " + e);
        }
        return result;
    }

    /**
     * startTorchImpl
     *
     */
    @Override
    protected void startTorchImpl(final String deviceId) {
        //not implemented
    }

}
