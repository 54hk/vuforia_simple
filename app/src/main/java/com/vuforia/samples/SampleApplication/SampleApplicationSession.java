/*===============================================================================
Copyright (c) 2016-2018 PTC Inc. All Rights Reserved.

Copyright (c) 2012-2015 Qualcomm Connected Experiences, Inc. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other 
countries.
===============================================================================*/


package com.vuforia.samples.SampleApplication;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.AsyncTask;
import android.util.Log;
import android.view.WindowManager;

import com.vuforia.CameraDevice;
import com.vuforia.Device;
import com.vuforia.INIT_ERRORCODE;
import com.vuforia.INIT_FLAGS;
import com.vuforia.State;
import com.vuforia.Vuforia;
import com.vuforia.Vuforia.UpdateCallbackInterface;
import com.vuforia.samples.VuforiaSamples.R;

import java.lang.ref.WeakReference;


public class SampleApplicationSession implements UpdateCallbackInterface
{
    
    private static final String LOGTAG = "SampleAppSession";
    
    // Reference to the current activity
    private WeakReference<Activity> mActivityRef;
    private final WeakReference<SampleApplicationControl> mSessionControlRef;
    
    // Flags
    private boolean mStarted = false;
    private boolean mCameraRunning = false;

    private int mVideoMode = CameraDevice.MODE.MODE_DEFAULT;
    
    // The async tasks to initialize the Vuforia SDK:
    private InitVuforiaTask mInitVuforiaTask;
    private LoadTrackerTask mLoadTrackerTask;
    private ResumeVuforiaTask mResumeVuforiaTask;
    
    // An object used for synchronizing Vuforia initialization, dataset loading
    // and the Android onDestroy() life cycle event. If the application is
    // destroyed while a data set is still being loaded, then we wait for the
    // loading operation to finish before shutting down Vuforia:
    private final Object mLifecycleLock = new Object();
    
    // Vuforia initialization flags:
    private int mVuforiaFlags = 0;
    
    // Holds the camera configuration to use upon resuming
    private int mCamera = CameraDevice.CAMERA_DIRECTION.CAMERA_DIRECTION_DEFAULT;
    

    public SampleApplicationSession(SampleApplicationControl sessionControl)
    {
        mSessionControlRef = new WeakReference<>(sessionControl);
    }

    public SampleApplicationSession(SampleApplicationControl sessionControl, int videoMode)
    {
        mSessionControlRef = new WeakReference<>(sessionControl);
        mVideoMode = videoMode;
    }
    
    
    // Initializes Vuforia and sets up preferences.
    public void initAR(Activity activity, int screenOrientation)
    {
        SampleApplicationException vuforiaException = null;
        mActivityRef = new WeakReference<>(activity);
        
        if (screenOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR)
        {
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        }
        
        // Apply screen orientation
        mActivityRef.get().setRequestedOrientation(screenOrientation);
        
        // As long as this window is visible to the user, keep the device's
        // screen turned on and bright:
        mActivityRef.get().getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        mVuforiaFlags = INIT_FLAGS.GL_20;
        
        // Initialize Vuforia SDK asynchronously to avoid blocking the
        // main (UI) thread.
        //
        // NOTE: This task instance must be created and invoked on the
        // UI thread and it can be executed only once!
        if (mInitVuforiaTask != null)
        {
            String logMessage = "Cannot initialize SDK twice";
            vuforiaException = new SampleApplicationException(
                SampleApplicationException.VUFORIA_ALREADY_INITIALIZATED,
                logMessage);
            Log.e(LOGTAG, logMessage);
        }
        
        if (vuforiaException == null)
        {
            try {
                mInitVuforiaTask = new InitVuforiaTask(this);
                mInitVuforiaTask.execute();
            }
            catch (Exception e)
            {
                String logMessage = "Initializing Vuforia SDK failed";
                vuforiaException = new SampleApplicationException(
                    SampleApplicationException.INITIALIZATION_FAILURE,
                    logMessage);
                Log.e(LOGTAG, logMessage);
            }
        }

        if (vuforiaException != null)
        {
            // Send Vuforia Exception to the application and call initDone
            // to stop initialization process
            mSessionControlRef.get().onInitARDone(vuforiaException);
        }
    }
    
    
    // Starts Vuforia, initialize and starts the camera and start the trackers
    private void startCameraAndTrackers(int camera) throws SampleApplicationException
    {
        String error;
        if(mCameraRunning)
        {
        	error = "Camera already running, unable to open again";
        	Log.e(LOGTAG, error);
            throw new SampleApplicationException(
                SampleApplicationException.CAMERA_INITIALIZATION_FAILURE, error);
        }
        
        mCamera = camera;
        if (!CameraDevice.getInstance().init(camera))
        {
            error = "Unable to open camera device: " + camera;
            Log.e(LOGTAG, error);
            throw new SampleApplicationException(
                SampleApplicationException.CAMERA_INITIALIZATION_FAILURE, error);
        }
               
        if (!CameraDevice.getInstance().selectVideoMode(mVideoMode))
        {
            error = "Unable to set video mode";
            Log.e(LOGTAG, error);
            throw new SampleApplicationException(
                SampleApplicationException.CAMERA_INITIALIZATION_FAILURE, error);
        }
        
        if (!CameraDevice.getInstance().start())
        {
            error = "Unable to start camera device: " + camera;
            Log.e(LOGTAG, error);
            throw new SampleApplicationException(
                SampleApplicationException.CAMERA_INITIALIZATION_FAILURE, error);
        }
        
        mSessionControlRef.get().doStartTrackers();
        
        mCameraRunning = true;
    }

    public void startAR(int camera)
    {
        mCamera = camera;
        SampleApplicationException vuforiaException = null;

        try
        {
            StartVuforiaTask mStartVuforiaTask = new StartVuforiaTask(this);
            mStartVuforiaTask.execute();
        }
        catch (Exception e)
        {
            String logMessage = "Starting Vuforia failed";
            vuforiaException = new SampleApplicationException(
                    SampleApplicationException.CAMERA_INITIALIZATION_FAILURE,
                    logMessage);
            Log.e(LOGTAG, logMessage);
        }

        if (vuforiaException != null)
        {
            // Send Vuforia Exception to the application and call initDone
            // to stop initialization process
            mSessionControlRef.get().onInitARDone(vuforiaException);
        }
    }

    
    // Stops any ongoing initialization, stops Vuforia
    public void stopAR() throws SampleApplicationException
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
        synchronized (mLifecycleLock)
        {
            
            boolean unloadTrackersResult;
            boolean deinitTrackersResult;
            
            // Destroy the tracking data set:
            unloadTrackersResult = mSessionControlRef.get().doUnloadTrackersData();
            
            // Deinitialize the trackers:
            deinitTrackersResult = mSessionControlRef.get().doDeinitTrackers();
            
            // Deinitialize Vuforia SDK:
            Vuforia.deinit();
            
            if (!unloadTrackersResult)
                throw new SampleApplicationException(
                    SampleApplicationException.UNLOADING_TRACKERS_FAILURE,
                    "Failed to unload trackers\' data");
            
            if (!deinitTrackersResult)
                throw new SampleApplicationException(
                    SampleApplicationException.TRACKERS_DEINITIALIZATION_FAILURE,
                    "Failed to deinitialize trackers");
            
        }
    }
    

    // Resumes Vuforia, restarts the trackers and the camera
    private void resumeAR()
    {
        SampleApplicationException vuforiaException = null;

        try {
            mResumeVuforiaTask = new ResumeVuforiaTask(this);
            mResumeVuforiaTask.execute();
        }
        catch (Exception e)
        {
            String logMessage = "Resuming Vuforia failed";
            vuforiaException = new SampleApplicationException(
                    SampleApplicationException.INITIALIZATION_FAILURE,
                    logMessage);
            Log.e(LOGTAG, logMessage);
        }

        if (vuforiaException != null)
        {
            // Send Vuforia Exception to the application and call initDone
            // to stop initialization process
            mSessionControlRef.get().onInitARDone(vuforiaException);
        }
    }


    // Pauses Vuforia and stops the camera
    private void pauseAR()
    {
        if (mStarted)
        {
            stopCamera();
        }
        
        Vuforia.onPause();
    }
    
    
    // Callback called every cycle
    @Override
    public void Vuforia_onUpdate(State s)
    {
        mSessionControlRef.get().onVuforiaUpdate(s);
    }
    
    
    // Manages the configuration changes
    public void onConfigurationChanged()
    {
        if (mStarted)
        {
            Device.getInstance().setConfigurationChanged();
        }
    }
    
    
    // Methods to be called to handle lifecycle
    public void onResume()
    {
        if (mResumeVuforiaTask == null
                || mResumeVuforiaTask.getStatus() == ResumeVuforiaTask.Status.FINISHED)
        {
            // onResume() will sometimes be called twice depending on the screen lock mode
            // This will prevent redundant AsyncTasks from being executed
            resumeAR();
        }
    }
    
    
    public void onPause()
    {
        pauseAR();
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
    private static class InitVuforiaTask extends AsyncTask<Void, Integer, Boolean>
    {
        // Initialize with invalid value:
        private int mProgressValue = -1;

        private final WeakReference<SampleApplicationSession> appSession;

        InitVuforiaTask(SampleApplicationSession session)
        {
            appSession = new WeakReference<>(session);
        }
        
        protected Boolean doInBackground(Void... params)
        {
            SampleApplicationSession session = appSession.get();

            // Prevent the onDestroy() method to overlap with initialization:
            synchronized (session.mLifecycleLock)
            {
                Vuforia.setInitParameters(session.mActivityRef.get(), session.mVuforiaFlags, "AQjaqx7/////AAABGfUSOfFoNE1uqDncSDtanX0rE9s+M3ime5RPSVJ01bOG6Akwbsvi+jtTVxXE2AWZ+CFulYEL5uNutPZqLwoEBnx1K0s3jYh4CBH4E7HnhuLI+DNfc9C01kIBFpQ7V7hrkmUWdIC+BUOhIBX4Ft2iLHeXtLwd2GUwp75nH9jMUugLkgETYucQObbYs14rZtzP4UpcMrH5Z0iWfzDOX9khhAmgCXTkEHliKj3ddNKMSZ6Y1E6GuDL/OY2EOS76KHKcTZoSS+xUQWttCq44meEu0mK0t4P4/pkAjeWq26XabCXcyB44Yef4cHdI/lEm/boDwkmSbKt/nPfEQT/5GG8jly0mpmip0DisT8W1OqQN5l2a");
                
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

            Log.d(LOGTAG, "InitVuforiaTask.onPostExecute: execution "
                    + (result ? "successful" : "failed"));
            
            SampleApplicationException vuforiaException = null;
            SampleApplicationSession session = appSession.get();
            
            if (result)
            {
                try
                {
                    InitTrackerTask mInitTrackerTask = new InitTrackerTask(session);
                    mInitTrackerTask.execute();
                }
                catch (Exception e)
                {
                    String logMessage = "Failed to initialize tracker.";
                    vuforiaException = new SampleApplicationException(
                            SampleApplicationException.TRACKERS_INITIALIZATION_FAILURE,
                            logMessage);
                    Log.e(LOGTAG, logMessage);
                }
            } else
            {
                String logMessage;
                
                // NOTE: Check if initialization failed because the device is
                // not supported. At this point the user should be informed
                // with a message.
                logMessage = appSession.get().getInitializationErrorString(mProgressValue);
                
                // Log error:
                Log.e(LOGTAG, "InitVuforiaTask.onPostExecute: " + logMessage
                    + " Exiting.");

                vuforiaException = new SampleApplicationException(
                    SampleApplicationException.INITIALIZATION_FAILURE,
                    logMessage);
            }

            if (vuforiaException != null)
            {
                // Send Vuforia Exception to the application and call initDone
                // to stop initialization process
                session.mSessionControlRef.get().onInitARDone(vuforiaException);
            }
        }
    }

    // An async task to resume Vuforia asynchronously
    private static class ResumeVuforiaTask extends AsyncTask<Void, Void, Void>
    {
        private final WeakReference<SampleApplicationSession> appSession;

        ResumeVuforiaTask(SampleApplicationSession session)
        {
            appSession = new WeakReference<>(session);
        }

        protected Void doInBackground(Void... params)
        {
            // Prevent the concurrent lifecycle operations:
            synchronized (appSession.get().mLifecycleLock)
            {
                Vuforia.onResume();
            }

            return null;
        }

        protected void onPostExecute(Void result)
        {
            Log.d(LOGTAG, "ResumeVuforiaTask.onPostExecute");

            SampleApplicationSession session = appSession.get();

            // We may start the camera only if the Vuforia SDK has already been initialized
            if (session.mStarted && !session.mCameraRunning)
            {
                session.startAR(session.mCamera);
                session.mSessionControlRef.get().onVuforiaResumed();
            }
        }
    }

    // An async task to initialize trackers asynchronously
    private static class InitTrackerTask extends AsyncTask<Void, Integer, Boolean>
    {
        private final WeakReference<SampleApplicationSession> appSession;

        InitTrackerTask(SampleApplicationSession session)
        {
            appSession = new WeakReference<>(session);
        }

        protected  Boolean doInBackground(Void... params)
        {
            synchronized (appSession.get().mLifecycleLock)
            {
                // Load the tracker data set:
                return appSession.get().mSessionControlRef.get().doInitTrackers();
            }
        }

        protected void onPostExecute(Boolean result)
        {

            SampleApplicationException vuforiaException = null;
            SampleApplicationSession session = appSession.get();

            Log.d(LOGTAG, "InitTrackerTask.onPostExecute: execution "
                + (result ? "successful" : "failed"));

            if (result)
            {
                try
                {
                    session.mLoadTrackerTask = new LoadTrackerTask(session);
                    session.mLoadTrackerTask.execute();
                }
                catch (Exception e)
                {
                    String logMessage = "Failed to load tracker data.";
                    Log.e(LOGTAG, logMessage);

                    vuforiaException = new SampleApplicationException(
                            SampleApplicationException.LOADING_TRACKERS_FAILURE,
                            logMessage);
                }
            }
            else
            {
                String logMessage = "Failed to init tracker data.";
                Log.e(LOGTAG, logMessage);

                // Error loading dataset
                vuforiaException = new SampleApplicationException(
                        SampleApplicationException.TRACKERS_INITIALIZATION_FAILURE,
                        logMessage);
            }

            if (vuforiaException != null)
            {
                // Send Vuforia Exception to the application and call initDone
                // to stop initialization process
                session.mSessionControlRef.get().onInitARDone(vuforiaException);
            }
        }
    }
    
    // An async task to load the tracker data asynchronously.
    private static class LoadTrackerTask extends AsyncTask<Void, Void, Boolean>
    {
        private final WeakReference<SampleApplicationSession> appSession;

        LoadTrackerTask(SampleApplicationSession session)
        {
            appSession = new WeakReference<>(session);
        }

        protected Boolean doInBackground(Void... params)
        {
            // Prevent the concurrent lifecycle operations:
            synchronized (appSession.get().mLifecycleLock)
            {
                // Load the tracker data set:
                return appSession.get().mSessionControlRef.get().doLoadTrackersData();
            }
        }
        
        protected void onPostExecute(Boolean result)
        {
            
            SampleApplicationException vuforiaException = null;
            SampleApplicationSession session = appSession.get();
            
            Log.d(LOGTAG, "LoadTrackerTask.onPostExecute: execution "
                + (result ? "successful" : "failed"));
            
            if (!result)
            {
                String logMessage = "Failed to load tracker data.";
                // Error loading dataset
                Log.e(LOGTAG, logMessage);
                vuforiaException = new SampleApplicationException(
                    SampleApplicationException.LOADING_TRACKERS_FAILURE,
                    logMessage);
            } else
            {
                // Hint to the virtual machine that it would be a good time to
                // run the garbage collector:
                //
                // NOTE: This is only a hint. There is no guarantee that the
                // garbage collector will actually be run.
                System.gc();
                
                Vuforia.registerCallback(session);

                session.mStarted = true;
            }
            
            // Done loading the tracker, update application status, send the
            // exception to check errors
            session.mSessionControlRef.get().onInitARDone(vuforiaException);
        }
    }

    // An async task to start the camera and trackers
    private static class StartVuforiaTask extends AsyncTask<Void, Void, Boolean>
    {
        SampleApplicationException vuforiaException = null;

        private final WeakReference<SampleApplicationSession> appSession;

        StartVuforiaTask(SampleApplicationSession session)
        {
            appSession = new WeakReference<>(session);
        }

        protected Boolean doInBackground(Void... params)
        {
            SampleApplicationSession session = appSession.get();

            // Prevent the concurrent lifecycle operations:
            synchronized (session.mLifecycleLock)
            {
                try {
                    session.startCameraAndTrackers(session.mCamera);
                }
                catch (SampleApplicationException e)
                {
                    Log.e(LOGTAG, "StartVuforiaTask.doInBackground: Could not start AR with exception: " + e);
                    vuforiaException = e;
                }
            }

            return true;
        }

        protected void onPostExecute(Boolean result)
        {
            Log.d(LOGTAG, "StartVuforiaTask.onPostExecute: execution "
                + (result ? "successful" : "failed"));

            SampleApplicationControl sessionControl = appSession.get().mSessionControlRef.get();

            sessionControl.onVuforiaStarted();

            if (vuforiaException != null)
            {
                // Send Vuforia Exception to the application and call initDone
                // to stop initialization process
                sessionControl.onInitARDone(vuforiaException);
            }
        }
    }
    
    
    // Returns the error message for each error code
    private String getInitializationErrorString(int code)
    {
        if (code == INIT_ERRORCODE.INIT_DEVICE_NOT_SUPPORTED)
            return mActivityRef.get().getString(R.string.INIT_ERROR_DEVICE_NOT_SUPPORTED);
        if (code == INIT_ERRORCODE.INIT_NO_CAMERA_ACCESS)
            return mActivityRef.get().getString(R.string.INIT_ERROR_NO_CAMERA_ACCESS);
        if (code == INIT_ERRORCODE.INIT_LICENSE_ERROR_MISSING_KEY)
            return mActivityRef.get().getString(R.string.INIT_LICENSE_ERROR_MISSING_KEY);
        if (code == INIT_ERRORCODE.INIT_LICENSE_ERROR_INVALID_KEY)
            return mActivityRef.get().getString(R.string.INIT_LICENSE_ERROR_INVALID_KEY);
        if (code == INIT_ERRORCODE.INIT_LICENSE_ERROR_NO_NETWORK_TRANSIENT)
            return mActivityRef.get().getString(R.string.INIT_LICENSE_ERROR_NO_NETWORK_TRANSIENT);
        if (code == INIT_ERRORCODE.INIT_LICENSE_ERROR_NO_NETWORK_PERMANENT)
            return mActivityRef.get().getString(R.string.INIT_LICENSE_ERROR_NO_NETWORK_PERMANENT);
        if (code == INIT_ERRORCODE.INIT_LICENSE_ERROR_CANCELED_KEY)
            return mActivityRef.get().getString(R.string.INIT_LICENSE_ERROR_CANCELED_KEY);
        if (code == INIT_ERRORCODE.INIT_LICENSE_ERROR_PRODUCT_TYPE_MISMATCH)
            return mActivityRef.get().getString(R.string.INIT_LICENSE_ERROR_PRODUCT_TYPE_MISMATCH);
        else
        {
            return mActivityRef.get().getString(R.string.INIT_LICENSE_ERROR_UNKNOWN_ERROR);
        }
    }
    
    
    private void stopCamera()
    {
        if (mCameraRunning)
        {
            mSessionControlRef.get().doStopTrackers();
            mCameraRunning = false;
            CameraDevice.getInstance().stop();
            CameraDevice.getInstance().deinit();
        }
    }


    public int getVideoMode()
    {
        return mVideoMode;
    }
    
}
