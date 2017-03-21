package com.github.daemontus.ar.vuforia;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.AsyncTask;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.github.daemontus.renderer.R;
import com.vuforia.CameraDevice;
import com.vuforia.PIXEL_FORMAT;
import com.vuforia.Renderer;
import com.vuforia.State;
import com.vuforia.Vec2I;
import com.vuforia.VideoBackgroundConfig;
import com.vuforia.VideoMode;
import com.vuforia.Vuforia;

/**
 * Represents simple Vuforia App Session.
 *
 * Created by daemontus on 03/04/14.
 */
public class AppSession implements Vuforia.UpdateCallbackInterface {

        private static final String LOGTAG = "Vuforia_App_Session";

        // Reference to the current activity
        private Activity mActivity;
        private SessionControl mSessionControl;

        // Flags
        private boolean mStarted = false;

        // Display size of the device:
        private int mScreenWidth = 0;
        private int mScreenHeight = 0;

        // The async tasks to initialize the Vuforia SDK:
        private InitVuforiaTask mInitVuforiaTask;
        private LoadTrackerTask mLoadTrackerTask;

        // An object used for synchronizing Vuforia initialization, dataset loading
        // and the Android onDestroy() life cycle event. If the application is
        // destroyed while a data set is still being loaded, then we wait for the
        // loading operation to finish before shutting down Vuforia:
        private final Object mShutdownLock = new Object();

        // Vuforia initialization flags:
        private int mVuforiaFlags = 0;

        // Holds the camera configuration to use upon resuming
        private int mCamera = CameraDevice.CAMERA_DIRECTION.CAMERA_DIRECTION_DEFAULT;

        // Stores orientation
        private boolean mIsPortrait = false;


        public AppSession(SessionControl sessionControl)
        {
            mSessionControl = sessionControl;
        }


        // Initializes Vuforia and sets up preferences.
        public void initAR(Activity activity, int screenOrientation)
        {
            VuforiaException vuforiaException = null;
            mActivity = activity;

            if ((screenOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR) && (Build.VERSION.SDK_INT > Build.VERSION_CODES.FROYO)) {
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
            }

            // Apply screen orientation
            mActivity.setRequestedOrientation(screenOrientation);

            updateActivityOrientation();

            // Query display dimensions:
            storeScreenDimensions();

            // As long as this window is visible to the user, keep the device's
            // screen turned on and bright:
            mActivity.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            mVuforiaFlags = Vuforia.GL_20;

            // Initialize Vuforia SDK asynchronously to avoid blocking the
            // main (UI) thread.
            //
            // NOTE: This task instance must be created and invoked on the
            // UI thread and it can be executed only once!
            if (mInitVuforiaTask != null)
            {
                String logMessage = "Cannot initialize SDK twice";
                vuforiaException = new VuforiaException(
                        VuforiaException.VUFORIA_ALREADY_INITIALIZATED,
                        logMessage);
                Log.e(LOGTAG, logMessage);
            }

            if (vuforiaException == null)
            {
                try
                {
                    mInitVuforiaTask = new InitVuforiaTask();
                    mInitVuforiaTask.execute();
                } catch (Exception e)
                {
                    String logMessage = "Initializing Vuforia SDK failed";
                    vuforiaException = new VuforiaException(
                            VuforiaException.INITIALIZATION_FAILURE,
                            logMessage);
                    Log.e(LOGTAG, logMessage);
                }
            }

            if (vuforiaException != null)
                mSessionControl.onInitARDone(vuforiaException);
        }


        // Starts Vuforia, initialize and starts the camera and start the trackers
        public void startAR(int camera) throws VuforiaException
        {
            String error;
            mCamera = camera;
            if (!CameraDevice.getInstance().init(camera))
            {
                error = "Unable to open camera device: " + camera;
                Log.e(LOGTAG, error);
                throw new VuforiaException(
                        VuforiaException.CAMERA_INITIALIZATION_FAILURE, error);
            }

            configureVideoBackground();

            if (!CameraDevice.getInstance().selectVideoMode(
                    CameraDevice.MODE.MODE_DEFAULT))
            {
                error = "Unable to set video mode";
                Log.e(LOGTAG, error);
                throw new VuforiaException(
                        VuforiaException.CAMERA_INITIALIZATION_FAILURE, error);
            }

            if (!CameraDevice.getInstance().start())
            {
                error = "Unable to start camera device: " + camera;
                Log.e(LOGTAG, error);
                throw new VuforiaException(
                        VuforiaException.CAMERA_INITIALIZATION_FAILURE, error);
            }

            Vuforia.setFrameFormat(PIXEL_FORMAT.RGB565, true);

            mSessionControl.doStartTrackers();

            try
            {
                setFocusMode(CameraDevice.FOCUS_MODE.FOCUS_MODE_TRIGGERAUTO);
            } catch (VuforiaException exceptionTriggerAuto)
            {
                setFocusMode(CameraDevice.FOCUS_MODE.FOCUS_MODE_NORMAL);
            }
        }


    // Stops any ongoing initialization, stops Vuforia
    public void stopAR() throws VuforiaException
    {
        // Cancel potentially running tasks
        if (mInitVuforiaTask != null
                && mInitVuforiaTask.getStatus() != InitVuforiaTask.Status.FINISHED)
        {
            mInitVuforiaTask.cancel(true);
            mInitVuforiaTask = null;
        }

        if (mLoadTrackerTask != null
                && mLoadTrackerTask.getStatus() != LoadTrackerTask.Status.FINISHED)
        {
            mLoadTrackerTask.cancel(true);
            mLoadTrackerTask = null;
        }

        mInitVuforiaTask = null;
        mLoadTrackerTask = null;

        mStarted = false;

        stopCamera();

        // Ensure that all asynchronous operations to initialize Vuforia
        // and loading the tracker datasets do not overlap:
        synchronized (mShutdownLock)
        {

            boolean unloadTrackersResult;
            boolean deinitTrackersResult;

            // Destroy the tracking data set:
            unloadTrackersResult = mSessionControl.doUnloadTrackersData();

            // Deinitialize the trackers:
            deinitTrackersResult = mSessionControl.doDeinitTrackers();

            // Deinitialize Vuforia SDK:
            Vuforia.deinit();

            if (!unloadTrackersResult)
                throw new VuforiaException(
                        VuforiaException.UNLOADING_TRACKERS_FAILURE,
                        "Failed to unload trackers\' data");

            if (!deinitTrackersResult)
                throw new VuforiaException(
                        VuforiaException.TRACKERS_DEINITIALIZATION_FAILURE,
                        "Failed to deinitialize trackers");

        }
    }

    // Resumes Vuforia, restarts the trackers and the camera
    public void resumeAR() throws VuforiaException
    {
        // Vuforia-specific resume operation
        Vuforia.onResume();

        if (mStarted)
            startAR(mCamera);
    }


    // Pauses Vuforia and stops the camera
    public void pauseAR() throws VuforiaException
    {
        if (mStarted)
            stopCamera();

        Vuforia.onPause();
    }

    // Callback called every cycle
    @Override
    public void Vuforia_onUpdate(State state) {
        mSessionControl.onQCARUpdate(state);
    }

    // Manages the configuration changes
    public void onConfigurationChanged()
    {
        updateActivityOrientation();

        storeScreenDimensions();

        if (isARRunning())
        {
            // configure video background
            configureVideoBackground();
        }

    }

    public void onSurfaceChanged(int width, int height)
    {
        Vuforia.onSurfaceChanged(width, height);
    }

    public void onSurfaceCreated()
    {
        Vuforia.onSurfaceCreated();
    }

    // An async task to initialize Vuforia asynchronously.
    private class InitVuforiaTask extends AsyncTask<Void, Integer, Boolean>
    {
        // Initialize with invalid value:
        private int mProgressValue = -1;


        protected Boolean doInBackground(Void... params)
        {
            // Prevent the onDestroy() method to overlap with initialization:
            synchronized (mShutdownLock)
            {
                Vuforia.setInitParameters(mActivity, mVuforiaFlags, mActivity.getString(R.string.vuforia_key));

                do
                {
                    // Vuforia.init() blocks until an initialization step is
                    // complete, then it proceeds to the next step and reports
                    // progress in percents (0 ... 100%).
                    // If Vuforia.init() returns -1, it indicates an error.
                    // Initialization is done when progress has reached 100%.
                    mProgressValue = Vuforia.init();

                    // Publish the progress value:
                    publishProgress(mProgressValue);

                    // We check whether the task has been canceled in the
                    // meantime (by calling AsyncTask.cancel(true)).
                    // and bail out if it has, thus stopping this thread.
                    // This is necessary as the AsyncTask will run to completion
                    // regardless of the status of the component that
                    // started is.
                } while (!isCancelled() && mProgressValue >= 0
                        && mProgressValue < 100);

                return (mProgressValue > 0);
            }
        }


        protected void onProgressUpdate(Integer... values)
        {
            // Do something with the progress value "values[0]", e.g. update
            // splash screen, progress bar, etc.
        }


        protected void onPostExecute(Boolean result)
        {
            // Done initializing Vuforia, proceed to next application
            // initialization status:

            VuforiaException vuforiaException;

            if (result)
            {
                Log.d(LOGTAG, "InitVuforiaTask.onPostExecute: Vuforia "
                        + "initialization successful");

                boolean initTrackersResult;
                initTrackersResult = mSessionControl.doInitTrackers();

                if (initTrackersResult)
                {
                    try
                    {
                        mLoadTrackerTask = new LoadTrackerTask();
                        mLoadTrackerTask.execute();
                    } catch (Exception e)
                    {
                        String logMessage = "Loading tracking data set failed";
                        vuforiaException = new VuforiaException(
                                VuforiaException.LOADING_TRACKERS_FAILURE,
                                logMessage);
                        Log.e(LOGTAG, logMessage);
                        mSessionControl.onInitARDone(vuforiaException);
                    }

                } else
                {
                    vuforiaException = new VuforiaException(
                            VuforiaException.TRACKERS_INITIALIZATION_FAILURE,
                            "Failed to initialize trackers");
                    mSessionControl.onInitARDone(vuforiaException);
                }
            } else
            {
                String logMessage;

                // NOTE: Check if initialization failed because the device is
                // not supported. At this point the user should be informed
                // with a message.
                if (mProgressValue == Vuforia.INIT_DEVICE_NOT_SUPPORTED)
                {
                    logMessage = "Failed to initialize Vuforia because this "
                            + "device is not supported.";
                } else
                {
                    logMessage = "Failed to initialize Vuforia.";
                }

                // Log error:
                Log.e(LOGTAG, "InitVuforiaTask.onPostExecute: " + logMessage
                        + " Exiting.");

                // Send Vuforia Exception to the application and call initDone
                // to stop initialization process
                vuforiaException = new VuforiaException(
                        VuforiaException.INITIALIZATION_FAILURE,
                        logMessage);
                mSessionControl.onInitARDone(vuforiaException);
            }
        }
    }

    // An async task to load the tracker data asynchronously.
    private class LoadTrackerTask extends AsyncTask<Void, Integer, Boolean>
    {
        protected Boolean doInBackground(Void... params)
        {
            // Prevent the onDestroy() method to overlap:
            synchronized (mShutdownLock)
            {
                // Load the tracker data set:
                return mSessionControl.doLoadTrackersData();
            }
        }


        protected void onPostExecute(Boolean result)
        {

            VuforiaException vuforiaException = null;

            Log.d(LOGTAG, "LoadTrackerTask.onPostExecute: execution " + (result ? "successful" : "failed"));

            if (!result)
            {
                String logMessage = "Failed to load tracker data.";
                // Error loading dataset
                Log.e(LOGTAG, logMessage);
                vuforiaException = new VuforiaException(
                        VuforiaException.LOADING_TRACKERS_FAILURE,
                        logMessage);
            } else
            {
                // Hint to the virtual machine that it would be a good time to
                // run the garbage collector:
                //
                // NOTE: This is only a hint. There is no guarantee that the
                // garbage collector will actually be run.
                System.gc();

                Vuforia.registerCallback(AppSession.this);

                mStarted = true;
            }

            // Done loading the tracker, update application status, send the
            // exception to check errors
            mSessionControl.onInitARDone(vuforiaException);
        }
    }


    // Stores screen dimensions
    private void storeScreenDimensions()
    {
        // Query display dimensions:
        DisplayMetrics metrics = new DisplayMetrics();
        mActivity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;
    }


    // Stores the orientation depending on the current resources configuration
    private void updateActivityOrientation()
    {
        Configuration config = mActivity.getResources().getConfiguration();

        switch (config.orientation)
        {
            case Configuration.ORIENTATION_PORTRAIT:
                mIsPortrait = true;
                break;
            case Configuration.ORIENTATION_LANDSCAPE:
                mIsPortrait = false;
                break;
            case Configuration.ORIENTATION_UNDEFINED:
            default:
                break;
        }

        Log.i(LOGTAG, "Activity is in " + (mIsPortrait ? "PORTRAIT" : "LANDSCAPE"));
    }


    private void stopCamera()
    {
        mSessionControl.doStopTrackers();
        CameraDevice.getInstance().setFlashTorchMode(false);
        CameraDevice.getInstance().stop();
        CameraDevice.getInstance().deinit();

    }


    // Applies auto focus if supported by the current device
    private boolean setFocusMode(int mode) throws VuforiaException
    {
        boolean result = CameraDevice.getInstance().setFocusMode(mode);

        if (!result)
            throw new VuforiaException(
                    VuforiaException.SET_FOCUS_MODE_FAILURE,
                    "Failed to set focus mode: " + mode);

        return true;
    }


    // Configures the video mode and sets offsets for the camera's image
    private void configureVideoBackground()
    {
        CameraDevice cameraDevice = CameraDevice.getInstance();
        VideoMode vm = cameraDevice.getVideoMode(CameraDevice.MODE.MODE_DEFAULT);

        VideoBackgroundConfig config = new VideoBackgroundConfig();
        config.setEnabled(true);
        config.setPosition(new Vec2I(0, 0));

        int xSize, ySize;
        if (mIsPortrait)
        {
            xSize = (int) (vm.getHeight() * (mScreenHeight / (float) vm
                    .getWidth()));
            ySize = mScreenHeight;

            if (xSize < mScreenWidth)
            {
                xSize = mScreenWidth;
                ySize = (int) (mScreenWidth * (vm.getWidth() / (float) vm
                        .getHeight()));
            }
        } else
        {
            xSize = mScreenWidth;
            ySize = (int) (vm.getHeight() * (mScreenWidth / (float) vm
                    .getWidth()));

            if (ySize < mScreenHeight)
            {
                xSize = (int) (mScreenHeight * (vm.getWidth() / (float) vm
                        .getHeight()));
                ySize = mScreenHeight;
            }
        }

        config.setSize(new Vec2I(xSize, ySize));

        Log.i(LOGTAG, "Configure Video Background : Video (" + vm.getWidth()
                + " , " + vm.getHeight() + "), Screen (" + mScreenWidth + " , "
                + mScreenHeight + "), mSize (" + xSize + " , " + ySize + ")");

        Renderer.getInstance().setVideoBackgroundConfig(config);

    }


    // Returns true if Vuforia is initialized, the trackers started and the
    // tracker data loaded
    private boolean isARRunning()
    {
        return mStarted;
    }
}
