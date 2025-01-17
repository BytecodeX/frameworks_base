/*
 * Copyright (C) 2007 The Android Open Source Project
 * This code has been modified.  Portions copyright (C) 2010, T-Mobile USA, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import com.android.internal.app.IUsageStats;
import com.android.internal.os.PkgUsageStats;
import com.android.internal.util.MemInfoReader;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Debug;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.view.Display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interact with the overall activities running in the system.
 */
public class ActivityManager {
    private static String TAG = "ActivityManager";
    private static boolean localLOGV = false;

    private final Context mContext;
    private final Handler mHandler;

    /*package*/ ActivityManager(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    /**
     * Screen compatibility mode: the application most always run in
     * compatibility mode.
     * @hide
     */
    public static final int COMPAT_MODE_ALWAYS = -1;

    /**
     * Screen compatibility mode: the application can never run in
     * compatibility mode.
     * @hide
     */
    public static final int COMPAT_MODE_NEVER = -2;

    /**
     * Screen compatibility mode: unknown.
     * @hide
     */
    public static final int COMPAT_MODE_UNKNOWN = -3;

    /**
     * Screen compatibility mode: the application currently has compatibility
     * mode disabled.
     * @hide
     */
    public static final int COMPAT_MODE_DISABLED = 0;

    /**
     * Screen compatibility mode: the application currently has compatibility
     * mode enabled.
     * @hide
     */
    public static final int COMPAT_MODE_ENABLED = 1;

    /**
     * Screen compatibility mode: request to toggle the application's
     * compatibility mode.
     * @hide
     */
    public static final int COMPAT_MODE_TOGGLE = 2;

    /** @hide */
    public int getFrontActivityScreenCompatMode() {
        try {
            return ActivityManagerNative.getDefault().getFrontActivityScreenCompatMode();
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return 0;
        }
    }

    /** @hide */
    public void setFrontActivityScreenCompatMode(int mode) {
        try {
            ActivityManagerNative.getDefault().setFrontActivityScreenCompatMode(mode);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
        }
    }

    /** @hide */
    public int getPackageScreenCompatMode(String packageName) {
        try {
            return ActivityManagerNative.getDefault().getPackageScreenCompatMode(packageName);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return 0;
        }
    }

    /** @hide */
    public void setPackageScreenCompatMode(String packageName, int mode) {
        try {
            ActivityManagerNative.getDefault().setPackageScreenCompatMode(packageName, mode);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
        }
    }

    /** @hide */
    public boolean getPackageAskScreenCompat(String packageName) {
        try {
            return ActivityManagerNative.getDefault().getPackageAskScreenCompat(packageName);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return false;
        }
    }

    /** @hide */
    public void setPackageAskScreenCompat(String packageName, boolean ask) {
        try {
            ActivityManagerNative.getDefault().setPackageAskScreenCompat(packageName, ask);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
        }
    }

    /**
     * Return the approximate per-application memory class of the current
     * device.  This gives you an idea of how hard a memory limit you should
     * impose on your application to let the overall system work best.  The
     * returned value is in megabytes; the baseline Android memory class is
     * 16 (which happens to be the Java heap limit of those devices); some
     * device with more memory may return 24 or even higher numbers.
     */
    public int getMemoryClass() {
        return staticGetMemoryClass();
    }
    
    /** @hide */
    static public int staticGetMemoryClass() {
        // Really brain dead right now -- just take this from the configured
        // vm heap size, and assume it is in megabytes and thus ends with "m".
        String vmHeapSize = SystemProperties.get("dalvik.vm.heapgrowthlimit", "");
        if (vmHeapSize != null && !"".equals(vmHeapSize)) {
            return Integer.parseInt(vmHeapSize.substring(0, vmHeapSize.length()-1));
        }
        return staticGetLargeMemoryClass();
    }
    
    /**
     * Return the approximate per-application memory class of the current
     * device when an application is running with a large heap.  This is the
     * space available for memory-intensive applications; most applications
     * should not need this amount of memory, and should instead stay with the
     * {@link #getMemoryClass()} limit.  The returned value is in megabytes.
     * This may be the same size as {@link #getMemoryClass()} on memory
     * constrained devices, or it may be significantly larger on devices with
     * a large amount of available RAM.
     *
     * <p>The is the size of the application's Dalvik heap if it has
     * specified <code>android:largeHeap="true"</code> in its manifest.
     */
    public int getLargeMemoryClass() {
        return staticGetLargeMemoryClass();
    }
    
    /** @hide */
    static public int staticGetLargeMemoryClass() {
        // Really brain dead right now -- just take this from the configured
        // vm heap size, and assume it is in megabytes and thus ends with "m".
        String vmHeapSize = SystemProperties.get("dalvik.vm.heapsize", "16m");
        return Integer.parseInt(vmHeapSize.substring(0, vmHeapSize.length()-1));
    }
    
    /**
     * Used by persistent processes to determine if they are running on a
     * higher-end device so should be okay using hardware drawing acceleration
     * (which tends to consume a lot more RAM).
     * @hide
     */
    static public boolean isHighEndGfx(Display display) {
        MemInfoReader reader = new MemInfoReader();
        reader.readMemInfo();
        if (reader.getTotalSize() >= (512*1024*1024)) {
            // If the device has at least 512MB RAM available to the kernel,
            // we can afford the overhead of graphics acceleration.
            return true;
        }
        Point p = new Point();
        display.getRealSize(p);
        int pixels = p.x * p.y;
        if (pixels >= (1024*600)) {
            // If this is a sufficiently large screen, then there are enough
            // pixels on it that we'd really like to use hw drawing.
            return true;
        }
        return false;
    }


    /**
     * Use to decide whether the running device can be considered a "large
     * RAM" device.  Exactly what memory limit large RAM is will vary, but
     * it essentially means there is plenty of RAM to have lots of background
     * processes running under decent loads.
     * @hide
     */
    static public boolean isLargeRAM() {
        MemInfoReader reader = new MemInfoReader();
        reader.readMemInfo();
        if (reader.getTotalSize() >= (640*1024*1024)) {
            // Currently 640MB RAM available to the kernel is the point at
            // which we have plenty of RAM to spare.
            return true;
        }
        return false;
    }

    /**
     * Information you can retrieve about tasks that the user has most recently
     * started or visited.
     */
    public static class RecentTaskInfo implements Parcelable {
        /**
         * If this task is currently running, this is the identifier for it.
         * If it is not running, this will be -1.
         */
        public int id;

        /**
         * The true identifier of this task, valid even if it is not running.
         */
        public int persistentId;
        
        /**
         * The original Intent used to launch the task.  You can use this
         * Intent to re-launch the task (if it is no longer running) or bring
         * the current task to the front.
         */
        public Intent baseIntent;

        /**
         * If this task was started from an alias, this is the actual
         * activity component that was initially started; the component of
         * the baseIntent in this case is the name of the actual activity
         * implementation that the alias referred to.  Otherwise, this is null.
         */
        public ComponentName origActivity;

        /**
         * Description of the task's last state.
         */
        public CharSequence description;
        
        public RecentTaskInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
            dest.writeInt(persistentId);
            if (baseIntent != null) {
                dest.writeInt(1);
                baseIntent.writeToParcel(dest, 0);
            } else {
                dest.writeInt(0);
            }
            ComponentName.writeToParcel(origActivity, dest);
            TextUtils.writeToParcel(description, dest,
                    Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        }

        public void readFromParcel(Parcel source) {
            id = source.readInt();
            persistentId = source.readInt();
            if (source.readInt() != 0) {
                baseIntent = Intent.CREATOR.createFromParcel(source);
            } else {
                baseIntent = null;
            }
            origActivity = ComponentName.readFromParcel(source);
            description = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        }
        
        public static final Creator<RecentTaskInfo> CREATOR
                = new Creator<RecentTaskInfo>() {
            public RecentTaskInfo createFromParcel(Parcel source) {
                return new RecentTaskInfo(source);
            }
            public RecentTaskInfo[] newArray(int size) {
                return new RecentTaskInfo[size];
            }
        };

        private RecentTaskInfo(Parcel source) {
            readFromParcel(source);
        }
    }

    /**
     * Flag for use with {@link #getRecentTasks}: return all tasks, even those
     * that have set their
     * {@link android.content.Intent#FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS} flag.
     */
    public static final int RECENT_WITH_EXCLUDED = 0x0001;
    
    /**
     * Provides a list that does not contain any
     * recent tasks that currently are not available to the user.
     */
    public static final int RECENT_IGNORE_UNAVAILABLE = 0x0002;

    /**
     * Return a list of the tasks that the user has recently launched, with
     * the most recent being first and older ones after in order.
     * 
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many tasks the
     * user has started and the maximum number the system can remember.
     * @param flags Information about what to return.  May be any combination
     * of {@link #RECENT_WITH_EXCLUDED} and {@link #RECENT_IGNORE_UNAVAILABLE}.
     * 
     * @return Returns a list of RecentTaskInfo records describing each of
     * the recent tasks.
     * 
     * @throws SecurityException Throws SecurityException if the caller does
     * not hold the {@link android.Manifest.permission#GET_TASKS} permission.
     */
    public List<RecentTaskInfo> getRecentTasks(int maxNum, int flags)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault().getRecentTasks(maxNum,
                    flags);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }

    /**
     * Information you can retrieve about a particular task that is currently
     * "running" in the system.  Note that a running task does not mean the
     * given task actually has a process it is actively running in; it simply
     * means that the user has gone to it and never closed it, but currently
     * the system may have killed its process and is only holding on to its
     * last state in order to restart it when the user returns.
     */
    public static class RunningTaskInfo implements Parcelable {
        /**
         * A unique identifier for this task.
         */
        public int id;

        /**
         * The component launched as the first activity in the task.  This can
         * be considered the "application" of this task.
         */
        public ComponentName baseActivity;

        /**
         * The activity component at the top of the history stack of the task.
         * This is what the user is currently doing.
         */
        public ComponentName topActivity;

        /**
         * Thumbnail representation of the task's current state.  Currently
         * always null.
         */
        public Bitmap thumbnail;

        /**
         * Description of the task's current state.
         */
        public CharSequence description;

        /**
         * Number of activities in this task.
         */
        public int numActivities;

        /**
         * Number of activities that are currently running (not stopped
         * and persisted) in this task.
         */
        public int numRunning;

        public RunningTaskInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
            ComponentName.writeToParcel(baseActivity, dest);
            ComponentName.writeToParcel(topActivity, dest);
            if (thumbnail != null) {
                dest.writeInt(1);
                thumbnail.writeToParcel(dest, 0);
            } else {
                dest.writeInt(0);
            }
            TextUtils.writeToParcel(description, dest,
                    Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            dest.writeInt(numActivities);
            dest.writeInt(numRunning);
        }

        public void readFromParcel(Parcel source) {
            id = source.readInt();
            baseActivity = ComponentName.readFromParcel(source);
            topActivity = ComponentName.readFromParcel(source);
            if (source.readInt() != 0) {
                thumbnail = Bitmap.CREATOR.createFromParcel(source);
            } else {
                thumbnail = null;
            }
            description = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
            numActivities = source.readInt();
            numRunning = source.readInt();
        }
        
        public static final Creator<RunningTaskInfo> CREATOR = new Creator<RunningTaskInfo>() {
            public RunningTaskInfo createFromParcel(Parcel source) {
                return new RunningTaskInfo(source);
            }
            public RunningTaskInfo[] newArray(int size) {
                return new RunningTaskInfo[size];
            }
        };

        private RunningTaskInfo(Parcel source) {
            readFromParcel(source);
        }
    }
    
    /**
     * Return a list of the tasks that are currently running, with
     * the most recent being first and older ones after in order.  Note that
     * "running" does not mean any of the task's code is currently loaded or
     * activity -- the task may have been frozen by the system, so that it
     * can be restarted in its previous state when next brought to the
     * foreground.
     * 
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many tasks the
     * user has started.
     *
     * @param flags Optional flags
     * @param receiver Optional receiver for delayed thumbnails
     *
     * @return Returns a list of RunningTaskInfo records describing each of
     * the running tasks.
     * 
     * Some thumbnails may not be available at the time of this call. The optional
     * receiver may be used to receive those thumbnails.
     *
     * @throws SecurityException Throws SecurityException if the caller does
     * not hold the {@link android.Manifest.permission#GET_TASKS} permission.
     *
     * @hide
     */
    public List<RunningTaskInfo> getRunningTasks(int maxNum, int flags, IThumbnailReceiver receiver)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault().getTasks(maxNum, flags, receiver);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }

    /**
     * Return a list of the tasks that are currently running, with
     * the most recent being first and older ones after in order.  Note that
     * "running" does not mean any of the task's code is currently loaded or
     * activity -- the task may have been frozen by the system, so that it
     * can be restarted in its previous state when next brought to the
     * foreground.
     *
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many tasks the
     * user has started.
     *
     * @return Returns a list of RunningTaskInfo records describing each of
     * the running tasks.
     *
     * @throws SecurityException Throws SecurityException if the caller does
     * not hold the {@link android.Manifest.permission#GET_TASKS} permission.
     */
    public List<RunningTaskInfo> getRunningTasks(int maxNum)
            throws SecurityException {
        return getRunningTasks(maxNum, 0, null);
    }

    /**
     * Remove some end of a task's activity stack that is not part of
     * the main application.  The selected activities will be finished, so
     * they are no longer part of the main task.
     *
     * @param taskId The identifier of the task.
     * @param subTaskIndex The number of the sub-task; this corresponds
     * to the index of the thumbnail returned by {@link #getTaskThumbnails(int)}.
     * @return Returns true if the sub-task was found and was removed.
     *
     * @hide
     */
    public boolean removeSubTask(int taskId, int subTaskIndex)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault().removeSubTask(taskId, subTaskIndex);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return false;
        }
    }

    /**
     * If set, the process of the root activity of the task will be killed
     * as part of removing the task.
     * @hide
     */
    public static final int REMOVE_TASK_KILL_PROCESS = 0x0001;

    /**
     * Completely remove the given task.
     *
     * @param taskId Identifier of the task to be removed.
     * @param flags Additional operational flags.  May be 0 or
     * {@link #REMOVE_TASK_KILL_PROCESS}.
     * @return Returns true if the given task was found and removed.
     *
     * @hide
     */
    public boolean removeTask(int taskId, int flags)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault().removeTask(taskId, flags);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return false;
        }
    }

    /** @hide */
    public static class TaskThumbnails implements Parcelable {
        public Bitmap mainThumbnail;

        public int numSubThumbbails;

        /** @hide */
        public IThumbnailRetriever retriever;

        public TaskThumbnails() {
        }

        public Bitmap getSubThumbnail(int index) {
            try {
                return retriever.getThumbnail(index);
            } catch (RemoteException e) {
                return null;
            }
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            if (mainThumbnail != null) {
                dest.writeInt(1);
                mainThumbnail.writeToParcel(dest, 0);
            } else {
                dest.writeInt(0);
            }
            dest.writeInt(numSubThumbbails);
            dest.writeStrongInterface(retriever);
        }

        public void readFromParcel(Parcel source) {
            if (source.readInt() != 0) {
                mainThumbnail = Bitmap.CREATOR.createFromParcel(source);
            } else {
                mainThumbnail = null;
            }
            numSubThumbbails = source.readInt();
            retriever = IThumbnailRetriever.Stub.asInterface(source.readStrongBinder());
        }

        public static final Creator<TaskThumbnails> CREATOR = new Creator<TaskThumbnails>() {
            public TaskThumbnails createFromParcel(Parcel source) {
                return new TaskThumbnails(source);
            }
            public TaskThumbnails[] newArray(int size) {
                return new TaskThumbnails[size];
            }
        };

        private TaskThumbnails(Parcel source) {
            readFromParcel(source);
        }
    }

    /** @hide */
    public TaskThumbnails getTaskThumbnails(int id) throws SecurityException {
        try {
            return ActivityManagerNative.getDefault().getTaskThumbnails(id);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }
    
    /**
     * Flag for {@link #moveTaskToFront(int, int)}: also move the "home"
     * activity along with the task, so it is positioned immediately behind
     * the task.
     */
    public static final int MOVE_TASK_WITH_HOME = 0x00000001;

    /**
     * Flag for {@link #moveTaskToFront(int, int)}: don't count this as a
     * user-instigated action, so the current activity will not receive a
     * hint that the user is leaving.
     */
    public static final int MOVE_TASK_NO_USER_ACTION = 0x00000002;

    /**
     * Ask that the task associated with a given task ID be moved to the
     * front of the stack, so it is now visible to the user.  Requires that
     * the caller hold permission {@link android.Manifest.permission#REORDER_TASKS}
     * or a SecurityException will be thrown.
     *
     * @param taskId The identifier of the task to be moved, as found in
     * {@link RunningTaskInfo} or {@link RecentTaskInfo}.
     * @param flags Additional operational flags, 0 or more of
     * {@link #MOVE_TASK_WITH_HOME}.
     */
    public void moveTaskToFront(int taskId, int flags) {
        try {
            ActivityManagerNative.getDefault().moveTaskToFront(taskId, flags);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
        }
    }

    /**
     * Information you can retrieve about a particular Service that is
     * currently running in the system.
     */
    public static class RunningServiceInfo implements Parcelable {
        /**
         * The service component.
         */
        public ComponentName service;

        /**
         * If non-zero, this is the process the service is running in.
         */
        public int pid;
        
        /**
         * The UID that owns this service.
         */
        public int uid;
        
        /**
         * The name of the process this service runs in.
         */
        public String process;
        
        /**
         * Set to true if the service has asked to run as a foreground process.
         */
        public boolean foreground;
        
        /**
         * The time when the service was first made active, either by someone
         * starting or binding to it.  This
         * is in units of {@link android.os.SystemClock#elapsedRealtime()}.
         */
        public long activeSince;
        
        /**
         * Set to true if this service has been explicitly started.
         */
        public boolean started;
        
        /**
         * Number of clients connected to the service.
         */
        public int clientCount;
        
        /**
         * Number of times the service's process has crashed while the service
         * is running.
         */
        public int crashCount;
        
        /**
         * The time when there was last activity in the service (either
         * explicit requests to start it or clients binding to it).  This
         * is in units of {@link android.os.SystemClock#uptimeMillis()}.
         */
        public long lastActivityTime;
        
        /**
         * If non-zero, this service is not currently running, but scheduled to
         * restart at the given time.
         */
        public long restarting;
        
        /**
         * Bit for {@link #flags}: set if this service has been
         * explicitly started.
         */
        public static final int FLAG_STARTED = 1<<0;
        
        /**
         * Bit for {@link #flags}: set if the service has asked to
         * run as a foreground process.
         */
        public static final int FLAG_FOREGROUND = 1<<1;
        
        /**
         * Bit for {@link #flags): set if the service is running in a
         * core system process.
         */
        public static final int FLAG_SYSTEM_PROCESS = 1<<2;
        
        /**
         * Bit for {@link #flags): set if the service is running in a
         * persistent process.
         */
        public static final int FLAG_PERSISTENT_PROCESS = 1<<3;
        
        /**
         * Running flags.
         */
        public int flags;
        
        /**
         * For special services that are bound to by system code, this is
         * the package that holds the binding.
         */
        public String clientPackage;
        
        /**
         * For special services that are bound to by system code, this is
         * a string resource providing a user-visible label for who the
         * client is.
         */
        public int clientLabel;
        
        public RunningServiceInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            ComponentName.writeToParcel(service, dest);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeString(process);
            dest.writeInt(foreground ? 1 : 0);
            dest.writeLong(activeSince);
            dest.writeInt(started ? 1 : 0);
            dest.writeInt(clientCount);
            dest.writeInt(crashCount);
            dest.writeLong(lastActivityTime);
            dest.writeLong(restarting);
            dest.writeInt(this.flags);
            dest.writeString(clientPackage);
            dest.writeInt(clientLabel);
        }

        public void readFromParcel(Parcel source) {
            service = ComponentName.readFromParcel(source);
            pid = source.readInt();
            uid = source.readInt();
            process = source.readString();
            foreground = source.readInt() != 0;
            activeSince = source.readLong();
            started = source.readInt() != 0;
            clientCount = source.readInt();
            crashCount = source.readInt();
            lastActivityTime = source.readLong();
            restarting = source.readLong();
            flags = source.readInt();
            clientPackage = source.readString();
            clientLabel = source.readInt();
        }
        
        public static final Creator<RunningServiceInfo> CREATOR = new Creator<RunningServiceInfo>() {
            public RunningServiceInfo createFromParcel(Parcel source) {
                return new RunningServiceInfo(source);
            }
            public RunningServiceInfo[] newArray(int size) {
                return new RunningServiceInfo[size];
            }
        };

        private RunningServiceInfo(Parcel source) {
            readFromParcel(source);
        }
    }

    /**
     * Return a list of the services that are currently running.
     * 
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many services
     * are running.
     * 
     * @return Returns a list of RunningServiceInfo records describing each of
     * the running tasks.
     */
    public List<RunningServiceInfo> getRunningServices(int maxNum)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault()
                    .getServices(maxNum, 0);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }
    
    /**
     * Returns a PendingIntent you can start to show a control panel for the
     * given running service.  If the service does not have a control panel,
     * null is returned.
     */
    public PendingIntent getRunningServiceControlPanel(ComponentName service)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault()
                    .getRunningServiceControlPanel(service);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }
    
    /**
     * Information you can retrieve about the available memory through
     * {@link ActivityManager#getMemoryInfo}.
     */
    public static class MemoryInfo implements Parcelable {
        /**
         * The total available memory on the system.  This number should not
         * be considered absolute: due to the nature of the kernel, a significant
         * portion of this memory is actually in use and needed for the overall
         * system to run well.
         */
        public long availMem;
        
        /**
         * The threshold of {@link #availMem} at which we consider memory to be
         * low and start killing background services and other non-extraneous
         * processes.
         */
        public long threshold;
        
        /**
         * Set to true if the system considers itself to currently be in a low
         * memory situation.
         */
        public boolean lowMemory;

        /** @hide */
        public long hiddenAppThreshold;
        /** @hide */
        public long secondaryServerThreshold;
        /** @hide */
        public long visibleAppThreshold;
        /** @hide */
        public long foregroundAppThreshold;

        public MemoryInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(availMem);
            dest.writeLong(threshold);
            dest.writeInt(lowMemory ? 1 : 0);
            dest.writeLong(hiddenAppThreshold);
            dest.writeLong(secondaryServerThreshold);
            dest.writeLong(visibleAppThreshold);
            dest.writeLong(foregroundAppThreshold);
        }
        
        public void readFromParcel(Parcel source) {
            availMem = source.readLong();
            threshold = source.readLong();
            lowMemory = source.readInt() != 0;
            hiddenAppThreshold = source.readLong();
            secondaryServerThreshold = source.readLong();
            visibleAppThreshold = source.readLong();
            foregroundAppThreshold = source.readLong();
        }

        public static final Creator<MemoryInfo> CREATOR
                = new Creator<MemoryInfo>() {
            public MemoryInfo createFromParcel(Parcel source) {
                return new MemoryInfo(source);
            }
            public MemoryInfo[] newArray(int size) {
                return new MemoryInfo[size];
            }
        };

        private MemoryInfo(Parcel source) {
            readFromParcel(source);
        }
    }

    public void getMemoryInfo(MemoryInfo outInfo) {
        try {
            ActivityManagerNative.getDefault().getMemoryInfo(outInfo);
        } catch (RemoteException e) {
        }
    }
    
    /**
     * @hide
     */
    public boolean clearApplicationUserData(String packageName, IPackageDataObserver observer) {
        try {
            return ActivityManagerNative.getDefault().clearApplicationUserData(packageName, 
                    observer);
        } catch (RemoteException e) {
            return false;
        }
    }
    
    /**
     * Information you can retrieve about any processes that are in an error condition.
     */
    public static class ProcessErrorStateInfo implements Parcelable {
        /**
         * Condition codes
         */
        public static final int NO_ERROR = 0;
        public static final int CRASHED = 1;
        public static final int NOT_RESPONDING = 2;

        /**
         * The condition that the process is in.
         */
        public int condition;

        /**
         * The process name in which the crash or error occurred.
         */
        public String processName;
        
        /**
         * The pid of this process; 0 if none
         */
        public int pid;

        /**
         * The kernel user-ID that has been assigned to this process;
         * currently this is not a unique ID (multiple applications can have
         * the same uid).
         */
        public int uid;
        
        /**
         * The activity name associated with the error, if known.  May be null.
         */
        public String tag;

        /**
         * A short message describing the error condition.
         */
        public String shortMsg;

        /**
         * A long message describing the error condition.
         */
        public String longMsg;

        /**
         * The stack trace where the error originated.  May be null.
         */
        public String stackTrace;

        /**
         * to be deprecated: This value will always be null.
         */
        public byte[] crashData = null;

        public ProcessErrorStateInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(condition);
            dest.writeString(processName);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeString(tag);
            dest.writeString(shortMsg);
            dest.writeString(longMsg);
            dest.writeString(stackTrace);
        }
        
        public void readFromParcel(Parcel source) {
            condition = source.readInt();
            processName = source.readString();
            pid = source.readInt();
            uid = source.readInt();
            tag = source.readString();
            shortMsg = source.readString();
            longMsg = source.readString();
            stackTrace = source.readString();
        }
        
        public static final Creator<ProcessErrorStateInfo> CREATOR = 
                new Creator<ProcessErrorStateInfo>() {
            public ProcessErrorStateInfo createFromParcel(Parcel source) {
                return new ProcessErrorStateInfo(source);
            }
            public ProcessErrorStateInfo[] newArray(int size) {
                return new ProcessErrorStateInfo[size];
            }
        };

        private ProcessErrorStateInfo(Parcel source) {
            readFromParcel(source);
        }
    }
    
    /**
     * Returns a list of any processes that are currently in an error condition.  The result 
     * will be null if all processes are running properly at this time.
     * 
     * @return Returns a list of ProcessErrorStateInfo records, or null if there are no
     * current error conditions (it will not return an empty list).  This list ordering is not
     * specified.
     */
    public List<ProcessErrorStateInfo> getProcessesInErrorState() {
        try {
            return ActivityManagerNative.getDefault().getProcessesInErrorState();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Information you can retrieve about a running process.
     */
    public static class RunningAppProcessInfo implements Parcelable {        
        /**
         * The name of the process that this object is associated with
         */
        public String processName;

        /**
         * The pid of this process; 0 if none
         */
        public int pid;
        
        /**
         * The user id of this process.
         */
        public int uid;
        
        /**
         * All packages that have been loaded into the process.
         */
        public String pkgList[];
        
        /**
         * Constant for {@link #flags}: this is an app that is unable to
         * correctly save its state when going to the background,
         * so it can not be killed while in the background.
         * @hide
         */
        public static final int FLAG_CANT_SAVE_STATE = 1<<0;
        
        /**
         * Constant for {@link #flags}: this process is associated with a
         * persistent system app.
         * @hide
         */
        public static final int FLAG_PERSISTENT = 1<<1;

        /**
         * Flags of information.  May be any of
         * {@link #FLAG_CANT_SAVE_STATE}.
         * @hide
         */
        public int flags;
        
        /**
         * Constant for {@link #importance}: this process is running the
         * foreground UI.
         */
        public static final int IMPORTANCE_FOREGROUND = 100;
        
        /**
         * Constant for {@link #importance}: this process is running something
         * that is actively visible to the user, though not in the immediate
         * foreground.
         */
        public static final int IMPORTANCE_VISIBLE = 200;
        
        /**
         * Constant for {@link #importance}: this process is running something
         * that is considered to be actively perceptible to the user.  An
         * example would be an application performing background music playback.
         */
        public static final int IMPORTANCE_PERCEPTIBLE = 130;
        
        /**
         * Constant for {@link #importance}: this process is running an
         * application that can not save its state, and thus can't be killed
         * while in the background.
         * @hide
         */
        public static final int IMPORTANCE_CANT_SAVE_STATE = 170;
        
        /**
         * Constant for {@link #importance}: this process is contains services
         * that should remain running.
         */
        public static final int IMPORTANCE_SERVICE = 300;
        
        /**
         * Constant for {@link #importance}: this process process contains
         * background code that is expendable.
         */
        public static final int IMPORTANCE_BACKGROUND = 400;
        
        /**
         * Constant for {@link #importance}: this process is empty of any
         * actively running code.
         */
        public static final int IMPORTANCE_EMPTY = 500;
        
        /**
         * The relative importance level that the system places on this
         * process.  May be one of {@link #IMPORTANCE_FOREGROUND},
         * {@link #IMPORTANCE_VISIBLE}, {@link #IMPORTANCE_SERVICE},
         * {@link #IMPORTANCE_BACKGROUND}, or {@link #IMPORTANCE_EMPTY}.  These
         * constants are numbered so that "more important" values are always
         * smaller than "less important" values.
         */
        public int importance;
        
        /**
         * An additional ordering within a particular {@link #importance}
         * category, providing finer-grained information about the relative
         * utility of processes within a category.  This number means nothing
         * except that a smaller values are more recently used (and thus
         * more important).  Currently an LRU value is only maintained for
         * the {@link #IMPORTANCE_BACKGROUND} category, though others may
         * be maintained in the future.
         */
        public int lru;
        
        /**
         * Constant for {@link #importanceReasonCode}: nothing special has
         * been specified for the reason for this level.
         */
        public static final int REASON_UNKNOWN = 0;
        
        /**
         * Constant for {@link #importanceReasonCode}: one of the application's
         * content providers is being used by another process.  The pid of
         * the client process is in {@link #importanceReasonPid} and the
         * target provider in this process is in
         * {@link #importanceReasonComponent}.
         */
        public static final int REASON_PROVIDER_IN_USE = 1;
        
        /**
         * Constant for {@link #importanceReasonCode}: one of the application's
         * content providers is being used by another process.  The pid of
         * the client process is in {@link #importanceReasonPid} and the
         * target provider in this process is in
         * {@link #importanceReasonComponent}.
         */
        public static final int REASON_SERVICE_IN_USE = 2;
        
        /**
         * The reason for {@link #importance}, if any.
         */
        public int importanceReasonCode;
        
        /**
         * For the specified values of {@link #importanceReasonCode}, this
         * is the process ID of the other process that is a client of this
         * process.  This will be 0 if no other process is using this one.
         */
        public int importanceReasonPid;
        
        /**
         * For the specified values of {@link #importanceReasonCode}, this
         * is the name of the component that is being used in this process.
         */
        public ComponentName importanceReasonComponent;
        
        /**
         * When {@link importanceReasonPid} is non-0, this is the importance
         * of the other pid. @hide
         */
        public int importanceReasonImportance;

        public RunningAppProcessInfo() {
            importance = IMPORTANCE_FOREGROUND;
            importanceReasonCode = REASON_UNKNOWN;
        }
        
        public RunningAppProcessInfo(String pProcessName, int pPid, String pArr[]) {
            processName = pProcessName;
            pid = pPid;
            pkgList = pArr;
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(processName);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeStringArray(pkgList);
            dest.writeInt(this.flags);
            dest.writeInt(importance);
            dest.writeInt(lru);
            dest.writeInt(importanceReasonCode);
            dest.writeInt(importanceReasonPid);
            ComponentName.writeToParcel(importanceReasonComponent, dest);
            dest.writeInt(importanceReasonImportance);
        }

        public void readFromParcel(Parcel source) {
            processName = source.readString();
            pid = source.readInt();
            uid = source.readInt();
            pkgList = source.readStringArray();
            flags = source.readInt();
            importance = source.readInt();
            lru = source.readInt();
            importanceReasonCode = source.readInt();
            importanceReasonPid = source.readInt();
            importanceReasonComponent = ComponentName.readFromParcel(source);
            importanceReasonImportance = source.readInt();
        }

        public static final Creator<RunningAppProcessInfo> CREATOR = 
            new Creator<RunningAppProcessInfo>() {
            public RunningAppProcessInfo createFromParcel(Parcel source) {
                return new RunningAppProcessInfo(source);
            }
            public RunningAppProcessInfo[] newArray(int size) {
                return new RunningAppProcessInfo[size];
            }
        };

        private RunningAppProcessInfo(Parcel source) {
            readFromParcel(source);
        }
    }
    
    /**
     * Returns a list of application processes installed on external media
     * that are running on the device.
     *
     * @return Returns a list of ApplicationInfo records, or null if none
     * This list ordering is not specified.
     * @hide
     */
    public List<ApplicationInfo> getRunningExternalApplications() {
        try {
            return ActivityManagerNative.getDefault().getRunningExternalApplications();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns a list of application processes that are running on the device.
     * 
     * @return Returns a list of RunningAppProcessInfo records, or null if there are no
     * running processes (it will not return an empty list).  This list ordering is not
     * specified.
     */
    public List<RunningAppProcessInfo> getRunningAppProcesses() {
        try {
            return ActivityManagerNative.getDefault().getRunningAppProcesses();
        } catch (RemoteException e) {
            return null;
        }
    }
    
    /**
     * Return information about the memory usage of one or more processes.
     * 
     * @param pids The pids of the processes whose memory usage is to be
     * retrieved.
     * @return Returns an array of memory information, one for each
     * requested pid.
     */
    public Debug.MemoryInfo[] getProcessMemoryInfo(int[] pids) {
        try {
            return ActivityManagerNative.getDefault().getProcessMemoryInfo(pids);
        } catch (RemoteException e) {
            return null;
        }
    }
    
    /**
     * @deprecated This is now just a wrapper for
     * {@link #killBackgroundProcesses(String)}; the previous behavior here
     * is no longer available to applications because it allows them to
     * break other applications by removing their alarms, stopping their
     * services, etc.
     */
    @Deprecated
    public void restartPackage(String packageName) {
        killBackgroundProcesses(packageName);
    }
    
    /**
     * Have the system immediately kill all background processes associated
     * with the given package.  This is the same as the kernel killing those
     * processes to reclaim memory; the system will take care of restarting
     * these processes in the future as needed.
     * 
     * <p>You must hold the permission
     * {@link android.Manifest.permission#KILL_BACKGROUND_PROCESSES} to be able to
     * call this method.
     * 
     * @param packageName The name of the package whose processes are to
     * be killed.
     */
    public void killBackgroundProcesses(String packageName) {
        try {
            ActivityManagerNative.getDefault().killBackgroundProcesses(packageName);
        } catch (RemoteException e) {
        }
    }
    
    /**
     * Have the system perform a force stop of everything associated with
     * the given application package.  All processes that share its uid
     * will be killed, all services it has running stopped, all activities
     * removed, etc.  In addition, a {@link Intent#ACTION_PACKAGE_RESTARTED}
     * broadcast will be sent, so that any of its registered alarms can
     * be stopped, notifications removed, etc.
     * 
     * <p>You must hold the permission
     * {@link android.Manifest.permission#FORCE_STOP_PACKAGES} to be able to
     * call this method.
     * 
     * @param packageName The name of the package to be stopped.
     * 
     * @hide This is not available to third party applications due to
     * it allowing them to break other applications by stopping their
     * services, removing their alarms, etc.
     */
    public void forceStopPackage(String packageName) {
        try {
            ActivityManagerNative.getDefault().forceStopPackage(packageName);
        } catch (RemoteException e) {
        }
    }
    
    /**
     * Get the device configuration attributes.
     */
    public ConfigurationInfo getDeviceConfigurationInfo() {
        try {
            return ActivityManagerNative.getDefault().getDeviceConfigurationInfo();
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Get the preferred density of icons for the launcher. This is used when
     * custom drawables are created (e.g., for shortcuts).
     *
     * @return density in terms of DPI
     */
    public int getLauncherLargeIconDensity() {
        final Resources res = mContext.getResources();
        final int density = res.getDisplayMetrics().densityDpi;

        if ((res.getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK)
                != Configuration.SCREENLAYOUT_SIZE_XLARGE) {
            return density;
        }

        switch (density) {
            case DisplayMetrics.DENSITY_LOW:
                return DisplayMetrics.DENSITY_MEDIUM;
            case DisplayMetrics.DENSITY_MEDIUM:
                return DisplayMetrics.DENSITY_HIGH;
            case DisplayMetrics.DENSITY_HIGH:
                return DisplayMetrics.DENSITY_XHIGH;
            case DisplayMetrics.DENSITY_XHIGH:
                return DisplayMetrics.DENSITY_MEDIUM * 2;
            default:
                return density;
        }
    }

    /**
     * Get the preferred launcher icon size. This is used when custom drawables
     * are created (e.g., for shortcuts).
     *
     * @return dimensions of square icons in terms of pixels
     */
    public int getLauncherLargeIconSize() {
        final Resources res = mContext.getResources();
        final int size = res.getDimensionPixelSize(android.R.dimen.app_icon_size);

        if ((res.getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK)
                != Configuration.SCREENLAYOUT_SIZE_XLARGE) {
            return size;
        }

        final int density = res.getDisplayMetrics().densityDpi;

        switch (density) {
            case DisplayMetrics.DENSITY_LOW:
                return (size * DisplayMetrics.DENSITY_MEDIUM) / DisplayMetrics.DENSITY_LOW;
            case DisplayMetrics.DENSITY_MEDIUM:
                return (size * DisplayMetrics.DENSITY_HIGH) / DisplayMetrics.DENSITY_MEDIUM;
            case DisplayMetrics.DENSITY_HIGH:
                return (size * DisplayMetrics.DENSITY_XHIGH) / DisplayMetrics.DENSITY_HIGH;
            case DisplayMetrics.DENSITY_XHIGH:
                return (size * DisplayMetrics.DENSITY_MEDIUM * 2) / DisplayMetrics.DENSITY_XHIGH;
            default:
                return size;
        }
    }

    /**
     * Returns "true" if the user interface is currently being messed with
     * by a monkey.
     */
    public static boolean isUserAMonkey() {
        try {
            return ActivityManagerNative.getDefault().isUserAMonkey();
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * Returns "true" if device is running in a test harness.
     */
    public static boolean isRunningInTestHarness() {
        return SystemProperties.getBoolean("ro.test_harness", false);
    }

    /**
     * Returns the launch count of each installed package.
     *
     * @hide
     */
    public Map<String, Integer> getAllPackageLaunchCounts() {
        try {
            IUsageStats usageStatsService = IUsageStats.Stub.asInterface(
                    ServiceManager.getService("usagestats"));
            if (usageStatsService == null) {
                return new HashMap<String, Integer>();
            }

            PkgUsageStats[] allPkgUsageStats = usageStatsService.getAllPkgUsageStats();
            if (allPkgUsageStats == null) {
                return new HashMap<String, Integer>();
            }

            Map<String, Integer> launchCounts = new HashMap<String, Integer>();
            for (PkgUsageStats pkgUsageStats : allPkgUsageStats) {
                launchCounts.put(pkgUsageStats.packageName, pkgUsageStats.launchCount);
            }

            return launchCounts;
        } catch (RemoteException e) {
            Log.w(TAG, "Could not query launch counts", e);
            return new HashMap<String, Integer>();
        }
    }
    /**
     * @hide
     */
    public Configuration getConfiguration() {
        try {
            return ActivityManagerNative.getDefault().getConfiguration();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns the usage statistics of each installed package.
     *
     * @hide
     */
    public PkgUsageStats[] getAllPackageUsageStats() {
        try {
            IUsageStats usageStatsService = IUsageStats.Stub.asInterface(
                    ServiceManager.getService("usagestats"));
            if (usageStatsService != null) {
                return usageStatsService.getAllPkgUsageStats();
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Could not query usage stats", e);
        }
        return new PkgUsageStats[0];
    }

    /**
     * @param userid the user's id. Zero indicates the default user 
     * @hide
     */
    public boolean switchUser(int userid) {
        try {
            return ActivityManagerNative.getDefault().switchUser(userid);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * @throws SecurityException Throws SecurityException if the caller does
     * not hold the {@link android.Manifest.permission#CHANGE_CONFIGURATION} permission.
     *
     * @hide
     */
    public void updateConfiguration(Configuration values) throws SecurityException {
        try {
            ActivityManagerNative.getDefault().updateConfiguration(values);
        } catch (RemoteException e) {
        }
    }
}
