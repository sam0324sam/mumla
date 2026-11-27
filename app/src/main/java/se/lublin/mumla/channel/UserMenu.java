/*
 * Copyright (C) 2015 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.mumla.channel;

import android.content.Context;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.FragmentManager;

import se.lublin.humla.IHumlaService;
import se.lublin.humla.model.IUser;
import se.lublin.mumla.R;

/**
 * Simplified user popup menu (local mute and ignore messages only).
 */
public class UserMenu implements PermissionsPopupMenu.IOnMenuPrepareListener, PopupMenu.OnMenuItemClickListener {
    private static final String TAG = UserMenu.class.getName();

    private final Context mContext;
    private final IUser mUser;
    private final IHumlaService mService;
    private final IUserLocalStateListener mStateListener;

    public UserMenu(Context context, IUser user, IHumlaService service,
                    FragmentManager fragmentManager, IUserLocalStateListener stateListener) {
        mContext = context;
        mUser = user;
        mService = service;
        mStateListener = stateListener;
    }

    @Override
    public void onMenuPrepare(Menu menu, int permissions) {
        boolean self;
        try {
            self = mUser.getSession() == mService.HumlaSession().getSessionId();
        } catch (IllegalStateException e) {
            Log.d(TAG, "exception in onMenuPrepare: " + e);
            return;
        }
        menu.findItem(R.id.context_local_mute).setVisible(!self);
        menu.findItem(R.id.context_ignore_messages).setVisible(!self);
        menu.findItem(R.id.context_local_mute).setChecked(mUser.isLocalMuted());
        menu.findItem(R.id.context_ignore_messages).setChecked(mUser.isLocalIgnored());
    }

    @Override
    public boolean onMenuItemClick(final MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.context_local_mute) {
            mUser.setLocalMuted(!mUser.isLocalMuted());
            mStateListener.onLocalUserStateUpdated(mUser);
            return true;
        } else if (itemId == R.id.context_ignore_messages) {
            mUser.setLocalIgnored(!mUser.isLocalIgnored());
            mStateListener.onLocalUserStateUpdated(mUser);
            return true;
        }
        return false;
    }

    public void showPopup(View anchor) {
        PermissionsPopupMenu popupMenu = new PermissionsPopupMenu(mContext, anchor,
                R.menu.context_user, this, this, mUser.getChannel(), mService);
        popupMenu.show();
    }

    public interface IUserLocalStateListener {
        void onLocalUserStateUpdated(IUser user);
    }
}
