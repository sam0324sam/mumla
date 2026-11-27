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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.FragmentManager;

import se.lublin.humla.IHumlaService;
import se.lublin.humla.model.IChannel;
import se.lublin.mumla.R;
import se.lublin.mumla.db.MumlaDatabase;

/**
 * Simplified channel popup menu for walkie-talkie channel switching.
 */
public class ChannelMenu implements PermissionsPopupMenu.IOnMenuPrepareListener, PopupMenu.OnMenuItemClickListener {
    private final Context mContext;
    private final IChannel mChannel;
    private final IHumlaService mService;

    public ChannelMenu(Context context, IChannel channel, IHumlaService service,
                       MumlaDatabase database, FragmentManager fragmentManager) {
        mContext = context;
        mChannel = channel;
        mService = service;
    }

    @Override
    public void onMenuPrepare(Menu menu, int permissions) {
        // Only join action is exposed for ski walkie-talkie use.
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (!mService.isConnected())
            return false;

        if (item.getItemId() == R.id.context_channel_join) {
            mService.HumlaSession().joinChannel(mChannel.getId());
            return true;
        }
        return false;
    }

    public void showPopup(View anchor) {
        PermissionsPopupMenu popupMenu = new PermissionsPopupMenu(mContext, anchor,
                R.menu.context_channel, this, this, mChannel, mService);
        popupMenu.show();
    }
}
