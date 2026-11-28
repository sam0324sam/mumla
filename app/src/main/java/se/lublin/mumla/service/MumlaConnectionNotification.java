/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.content.Context.RECEIVER_NOT_EXPORTED;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import se.lublin.mumla.R;
import se.lublin.mumla.app.DrawerAdapter;
import se.lublin.mumla.app.MumlaActivity;

/**
 * Wrapper to create Mumla notifications.
 * Created by andrew on 08/08/14.
 */
public class MumlaConnectionNotification {
    private static final String TAG = "MumlaConnNotif";
    private static final int NOTIFICATION_ID = 1;
    private static final String BROADCAST_MUTE = "b_mute";
    private static final String BROADCAST_DEAFEN = "b_deafen";

    private Service mService;
    private OnActionListener mListener;
    private String mCustomContentText;
    private boolean mActionsShown;
    private boolean mShowing;
    private boolean mReceiverRegistered;
    private boolean mChannelCreated;
    private String mLastRenderedContentText;
    private boolean mLastRenderedActionsShown;

    private BroadcastReceiver mNotificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BROADCAST_MUTE.equals(intent.getAction())) {
                mListener.onMuteToggled();
            } else if (BROADCAST_DEAFEN.equals(intent.getAction())) {
                mListener.onDeafenToggled();
            }
        }
    };

    /**
     * Creates a foreground Mumla notification for the given service.
     * @param service The service to register a foreground notification for.
     * @param listener An listener for notification actions.
     * @return A new MumlaNotification instance.
     */
    public static MumlaConnectionNotification create(Service service, String contentText,
                                                     OnActionListener listener) {
        return new MumlaConnectionNotification(service, contentText, listener);
    }

    private MumlaConnectionNotification(Service service, String contentText,
                                        OnActionListener listener) {
        mService = service;
        mListener = listener;
        mCustomContentText = contentText;
        mActionsShown = false;
    }

    public void setCustomContentText(String text) {
        mCustomContentText = text;
    }

    public void setActionsShown(boolean actionsShown) {
        mActionsShown = actionsShown;
    }

    /**
     * Shows or updates the notification and registers the notification action button receiver.
     * Skips redundant startForeground() IPC when content and actions have not changed.
     */
    public void show() {
        boolean contentUnchanged = (mCustomContentText == null)
                ? (mLastRenderedContentText == null)
                : mCustomContentText.equals(mLastRenderedContentText);
        if (mShowing && contentUnchanged && mActionsShown == mLastRenderedActionsShown) {
            return;
        }

        createNotification();
        mShowing = true;
        mLastRenderedContentText = mCustomContentText;
        mLastRenderedActionsShown = mActionsShown;

        if (!mReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BROADCAST_DEAFEN);
            filter.addAction(BROADCAST_MUTE);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    mService.registerReceiver(mNotificationReceiver, filter, RECEIVER_NOT_EXPORTED);
                } else {
                    mService.registerReceiver(mNotificationReceiver, filter);
                }
                mReceiverRegistered = true;
            } catch (IllegalArgumentException e) {
                // Thrown if receiver is already registered.
                mReceiverRegistered = true;
            }
        }
    }

    /**
     * Hides the notification and unregisters the action receiver.
     */
    public void hide() {
        if (mReceiverRegistered) {
            try {
                mService.unregisterReceiver(mNotificationReceiver);
            } catch (IllegalArgumentException ignored) {
                // Thrown if receiver is not registered.
            }
            mReceiverRegistered = false;
        }
        mShowing = false;
        mLastRenderedContentText = null;
        mService.stopForeground(true);
        NotificationManager manager = (NotificationManager) mService.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    /**
     * Called to update/create the service's foreground Mumla notification.
     */
    private Notification createNotification() {
        String channelId = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelId = "connected_channel";
            if (!mChannelCreated) {
                String channelName = mService.getString(R.string.connected);
                NotificationChannel chan = new NotificationChannel(channelId, channelName,
                        NotificationManager.IMPORTANCE_LOW);
                chan.setSound(null, null);
                chan.enableVibration(false);
                NotificationManager manager = mService.getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(chan);
                }
                mChannelCreated = true;
            }
        }
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(mService, channelId);

        // app name is always displayed in notification on >= O
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            builder.setContentTitle(mService.getString(R.string.app_name));
        }
        builder.setContentText(mCustomContentText);
        builder.setSmallIcon(R.drawable.ic_stat_notify);
        builder.setPriority(NotificationCompat.PRIORITY_LOW);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        builder.setShowWhen(false);
        builder.setOngoing(true);
        builder.setOnlyAlertOnce(true);
        builder.setSound(null);
        builder.setVibrate(null);

        if (mActionsShown) {
            // Add notification triggers
            Intent muteIntent = new Intent(BROADCAST_MUTE);
            muteIntent.setPackage(mService.getPackageName());
            Intent deafenIntent = new Intent(BROADCAST_DEAFEN);
            deafenIntent.setPackage(mService.getPackageName());

            builder.addAction(R.drawable.ic_action_microphone,
                    mService.getString(R.string.mute), PendingIntent.getBroadcast(mService, 1,
                            muteIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE));
            builder.addAction(R.drawable.ic_action_audio,
                    mService.getString(R.string.deafen), PendingIntent.getBroadcast(mService, 1,
                            deafenIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE));
        }

        Intent channelListIntent = new Intent(mService, MumlaActivity.class);
        channelListIntent.putExtra(MumlaActivity.EXTRA_DRAWER_FRAGMENT, DrawerAdapter.ITEM_SERVER);
        // FLAG_CANCEL_CURRENT ensures that the extra always gets sent.
        PendingIntent pendingIntent = PendingIntent.getActivity(mService, 0,
                channelListIntent, FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);
        builder.setContentIntent(pendingIntent);

        Notification notification = builder.build();
        startForegroundSafely(notification);
        return notification;
    }

    /**
     * Starts foreground service with graceful fallback for Android 14-16 (SDK 34+) background FGS restrictions.
     * On background reconnects or before RECORD_AUDIO is granted, FOREGROUND_SERVICE_TYPE_MICROPHONE throws
     * SecurityException; fall back to MEDIA_PLAYBACK and finally to a regular notification instead of crashing.
     */
    private void startForegroundSafely(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            boolean hasMicPermission = ContextCompat.checkSelfPermission(
                    mService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (hasMicPermission) {
                try {
                    mService.startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE);
                    return;
                } catch (SecurityException | IllegalStateException e) {
                    Log.w(TAG, "Failed to start MICROPHONE FGS (likely background reconnect), falling back to MEDIA_PLAYBACK", e);
                }
            }
            try {
                mService.startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                return;
            } catch (SecurityException | IllegalStateException e) {
                Log.w(TAG, "Failed to start MEDIA_PLAYBACK FGS, posting regular notification instead", e);
            }
        } else {
            try {
                mService.startForeground(NOTIFICATION_ID, notification);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to start foreground service, posting regular notification instead", e);
            }
        }

        try {
            NotificationManager manager = (NotificationManager) mService.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to post fallback notification", e);
        }
    }

    public interface OnActionListener {
        void onMuteToggled();
        void onDeafenToggled();
    }
}
