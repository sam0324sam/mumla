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

package se.lublin.mumla.service.ipc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import se.lublin.humla.IHumlaSession;
import se.lublin.mumla.service.IMumlaService;

public class TalkBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "TalkBroadcastReceiver";

    public static final String BROADCAST_TALK = "se.lublin.mumla.action.TALK";
    public static final String BROADCAST_PTT_DOWN = "unipro.hotkey.ptt.down";
    public static final String BROADCAST_PTT_UP = "unipro.hotkey.ptt.up";

    public static final String EXTRA_TALK_STATUS = "status";
    public static final String TALK_STATUS_ON = "on";
    public static final String TALK_STATUS_OFF = "off";
    public static final String TALK_STATUS_TOGGLE = "toggle";

    private final IMumlaService mService;

    public TalkBroadcastReceiver(IMumlaService service) {
        mService = service;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.d(TAG, "Received talk action: " + action);

        if (BROADCAST_PTT_DOWN.equals(action)) {
            mService.onTalkKeyDown();
        } else if (BROADCAST_PTT_UP.equals(action)) {
            mService.onTalkKeyUp();
        } else if (BROADCAST_TALK.equals(action)) {
            String status = intent.getStringExtra(EXTRA_TALK_STATUS);
            if (status == null) status = TALK_STATUS_TOGGLE;
            if (TALK_STATUS_ON.equals(status)) {
                mService.onTalkKeyDown();
            } else if (TALK_STATUS_OFF.equals(status)) {
                mService.onTalkKeyUp();
            } else if (TALK_STATUS_TOGGLE.equals(status)) {
                if (mService.isConnected()) {
                    try {
                        IHumlaSession session = mService.HumlaSession();
                        if (session != null && session.isTalking()) {
                            mService.onTalkKeyUp();
                        } else {
                            mService.onTalkKeyDown();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error toggling talk state", e);
                    }
                }
            }
        }
    }
}
