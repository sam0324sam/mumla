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

package se.lublin.mumla.channel;

import se.lublin.mumla.app.DialogUtils;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static android.view.inputmethod.EditorInfo.IME_NULL;

import static androidx.core.content.ContextCompat.checkSelfPermission;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.User;
import se.lublin.humla.util.HumlaDisconnectedException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.service.IChatMessage;
import se.lublin.mumla.util.HumlaServiceFragment;

public class ChannelChatFragment extends HumlaServiceFragment implements ChatTargetProvider.OnChatTargetSelectedListener {
    private static final String TAG = ChannelChatFragment.class.getName();

    private static final Pattern LINK_PATTERN = Pattern.compile("(https?://\\S+)");

    private IHumlaObserver mServiceObserver = new HumlaObserver() {

        @Override
        public void onMessageLogged(IMessage message) {
            addChatMessage(new IChatMessage.TextMessage(message), true);
        }

        @Override
        public void onLogInfo(String message) {
            addChatMessage(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message), true);
        }

        @Override
        public void onLogWarning(String message) {
            addChatMessage(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message), true);
        }

        @Override
        public void onLogError(String message) {
            addChatMessage(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message), true);
        }

        @Override
        public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {
            IHumlaService service = getService();
            if (service != null && service.isConnected()) {
                IHumlaSession session = service.HumlaSession();
                if (user != null && session.getSessionUser() != null &&
                        user.equals(session.getSessionUser()) &&
                        mTargetProvider.getChatTarget() == null) {
                    // Update chat target when user changes channels without a target.
                    updateChatTargetText(null);
                }
            }
        }
    };

    private ListView mChatList;
    private ChannelChatAdapter mChatAdapter;
    private EditText mChatTextEdit;
    private ImageButton mSendButton;
    private ChatTargetProvider mTargetProvider;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mTargetProvider = (ChatTargetProvider) getParentFragment();
        } catch (ClassCastException e) {
            throw new ClassCastException(getParentFragment().toString() + " must implement ChatTargetProvider");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mTargetProvider.registerChatTargetListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        mTargetProvider.unregisterChatTargetListener(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_chat, container, false);
        mChatList = view.findViewById(R.id.chat_list);
        mChatTextEdit = view.findViewById(R.id.chatTextEdit);

        mSendButton = view.findViewById(R.id.chatTextSend);
        mSendButton.setOnClickListener(this::sendMessageFromEditor);

        mChatTextEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == IME_NULL && event != null && event.getKeyCode() == KEYCODE_ENTER) {
                sendMessageFromEditor(v);
                return true;
            }
            return false;
        });

        mChatTextEdit.addTextChangedListener(new TextWatcher() {

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mSendButton.setEnabled(mChatTextEdit.getText().length() > 0);
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        updateChatTargetText(mTargetProvider.getChatTarget());
        return view;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.fragment_chat, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_roster_locations) {
            if (getActivity() != null) {
                DialogUtils.showRosterLocationsDialog(getActivity());
            }
            return true;
        }
        if (item.getItemId() == R.id.menu_clear_chat) {
            clear();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Adds the passed text to the fragment chat body.
     *
     * @param message The message to add.
     * @param scroll  Whether to scroll to the bottom after adding the message.
     */
    public void addChatMessage(IChatMessage message, boolean scroll) {
        if (mChatAdapter == null) return;

        // Prune older roster summary messages to prevent chat clutter and save memory
        if (message instanceof IChatMessage.TextMessage) {
            IChatMessage.TextMessage tm = (IChatMessage.TextMessage) message;
            String text = tm.getMessage() != null ? tm.getMessage().getMessage() : null;
            if (text != null && text.contains("【全員最近定位紀錄總表】")) {
                for (int i = mChatAdapter.getCount() - 1; i >= 0; i--) {
                    IChatMessage item = mChatAdapter.getItem(i);
                    if (item instanceof IChatMessage.TextMessage) {
                        IMessage oldMsg = ((IChatMessage.TextMessage) item).getMessage();
                        if (oldMsg != null && oldMsg.getMessage() != null && oldMsg.getMessage().contains("【全員最近定位紀錄總表】")) {
                            mChatAdapter.remove(item);
                        }
                    }
                }
            }
        }

        mChatAdapter.add(message);

        // Cap maximum chat items at 200 to guarantee OOM safety on low-memory devices
        while (mChatAdapter.getCount() > 200) {
            IChatMessage oldest = mChatAdapter.getItem(0);
            if (oldest != null) {
                mChatAdapter.remove(oldest);
            } else {
                break;
            }
        }

        if (scroll) {
            mChatList.post(new Runnable() {

                @Override
                public void run() {
                    mChatList.smoothScrollToPosition(mChatAdapter.getCount() - 1);
                }
            });
        }
    }

    /**
     * Sends the message currently in
     * {@link se.lublin.mumla.channel.ChannelChatFragment#mChatTextEdit}
     * to the remote server. Clears the message box if the message was sent successfully.
     */
    private void sendMessageFromEditor(View v) {
        if (mChatTextEdit.length() == 0) {
            return;
        }
        String message = mChatTextEdit.getText().toString();
        String trimmed = message.trim();
        if ("loc".equalsIgnoreCase(trimmed) || "/loc".equalsIgnoreCase(trimmed) || "定位".equals(trimmed)) {
            mChatTextEdit.setText("");
            if (getActivity() != null) {
                DialogUtils.showRosterLocationsDialog(getActivity());
            }
            return;
        }
        try {
            sendMessage(message);
            mChatTextEdit.setText("");
        } catch (HumlaDisconnectedException e) {
            Log.d(TAG, "exception from sendMessage: " + e);
        }
    }

    /**
     * Sends a message to the remote server.
     *
     * @param message The message to send.
     * @throws HumlaDisconnectedException If the service is disconnected.
     */
    private void sendMessage(String message) throws HumlaDisconnectedException {
        if (getService() == null) {
            Log.d(TAG,"getService()==null in sendMessage");
            return;
        }
        IHumlaSession session = getService().HumlaSession();

        String formattedMessage = markupOutgoingMessage(message);
        ChatTargetProvider.ChatTarget target = mTargetProvider.getChatTarget();
        IMessage responseMessage = null;
        if (target == null)
            responseMessage = session.sendChannelTextMessage(session.getSessionChannel().getId(), formattedMessage, false);
        else if (target.getUser() != null)
            responseMessage = session.sendUserTextMessage(target.getUser().getSession(), formattedMessage);
        else if (target.getChannel() != null)
            responseMessage = session.sendChannelTextMessage(target.getChannel().getId(), formattedMessage, false);
        addChatMessage(new IChatMessage.TextMessage(responseMessage), true);
    }

    /**
     * Adds HTML markup to the message, replacing links and newlines.
     *
     * @param message The message to markup.
     * @return HTML data.
     */
    private String markupOutgoingMessage(String message) {
        String formattedBody = message;
        Matcher matcher = LINK_PATTERN.matcher(formattedBody);
        formattedBody = matcher.replaceAll("<a href=\"$1\">$1</a>")
                .replaceAll("\n", "<br>");
        return formattedBody;
    }

    public void clear() {
        if (mChatAdapter != null) {
            mChatAdapter.clear();
        }
        if (getService() != null) {
            getService().clearMessageLog();
        }
    }

    /**
     * Updates hint displaying chat target.
     */
    public void updateChatTargetText(final ChatTargetProvider.ChatTarget target) {
        if (getService() == null || !getService().isConnected()) return;

        IHumlaSession session = getService().HumlaSession();
        String hint = null;
        if (target == null && session.getSessionChannel() != null) {
            hint = getString(R.string.messageToChannel, session.getSessionChannel().getName());
        } else if (target != null && target.getUser() != null) {
            hint = getString(R.string.messageToUser, target.getUser().getName());
        } else if (target != null && target.getChannel() != null) {
            hint = getString(R.string.messageToChannel, target.getChannel().getName());
        }
        mChatTextEdit.setHint(hint);
        mChatTextEdit.requestLayout(); // Needed to update bounds after hint change.
    }


    @Override
    public void onServiceBound(IHumlaService service) {
        if (getService() == null) {
            return;
        }

        mChatAdapter = new ChannelChatAdapter(getActivity(), service, getService().getMessageLog());
        mChatList.setAdapter(mChatAdapter);
        mChatList.post(new Runnable() {
            @Override
            public void run() {
                mChatList.setSelection(mChatAdapter.getCount() - 1);
            }
        });
    }

    @Override
    public IHumlaObserver getServiceObserver() {
        return mServiceObserver;
    }

    @Override
    public void onChatTargetSelected(ChatTargetProvider.ChatTarget target) {
        updateChatTargetText(target);
    }

    private static class ChannelChatAdapter extends ArrayAdapter<IChatMessage> {
        private final IHumlaService mService;
        private final DateFormat mDateFormat;

        public ChannelChatAdapter(Context context, IHumlaService service, List<IChatMessage> messages) {
            super(context, 0, new ArrayList<>(messages));
            mService = service;
            mDateFormat = SimpleDateFormat.getTimeInstance();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(getContext()).inflate(R.layout.list_chat_item, parent, false);
            }

            final LinearLayout chatBox = (LinearLayout) v.findViewById(R.id.list_chat_item_box);
            final TextView targetText = (TextView) v.findViewById(R.id.list_chat_item_target);
            final TextView messageText = (TextView) v.findViewById(R.id.list_chat_item_text);
            TextView timeText = (TextView) v.findViewById(R.id.list_chat_item_time);

            IChatMessage message = getItem(position);
            message.accept(new IChatMessage.Visitor() {
                @Override
                public void visit(IChatMessage.TextMessage message) {
                    IMessage textMessage = message.getMessage();
                    String targetMessage = getContext().getString(R.string.unknown);
                    boolean selfAuthored;
                    try {
                        selfAuthored = textMessage.getActor() == mService.HumlaSession().getSessionId();
                    } catch (HumlaDisconnectedException e) {
                        selfAuthored = false;
                    }

                    if (textMessage.getTargetChannels() != null && !textMessage.getTargetChannels().isEmpty()) {
                        IChannel currentChannel = (IChannel) textMessage.getTargetChannels().get(0);
                        if (currentChannel != null && currentChannel.getName() != null) {
                            targetMessage = getContext().getString(R.string.chat_message_to, textMessage.getActorName(), currentChannel.getName());
                        }
                    } else if (textMessage.getTargetTrees() != null && !textMessage.getTargetTrees().isEmpty()) {
                        IChannel currentChannel = (IChannel) textMessage.getTargetTrees().get(0);
                        if (currentChannel != null && currentChannel.getName() != null) {
                            targetMessage = getContext().getString(R.string.chat_message_to, textMessage.getActorName(), currentChannel.getName());
                        }
                    } else if (textMessage.getTargetUsers() != null && !textMessage.getTargetUsers().isEmpty()) {
                        User user = textMessage.getTargetUsers().get(0);
                        if (user != null && user.getName() != null) {
                            targetMessage = getContext().getString(R.string.chat_message_to, textMessage.getActorName(), user.getName());
                        }
                    } else {
                        targetMessage = textMessage.getActorName();
                    }

                    int gravity = selfAuthored ? Gravity.RIGHT : Gravity.LEFT;
                    chatBox.setGravity(gravity);
                    messageText.setGravity(gravity);
                    targetText.setText(targetMessage);
                    targetText.setVisibility(View.VISIBLE);
                }

                @Override
                public void visit(IChatMessage.InfoMessage message) {
                    targetText.setVisibility(View.GONE);
                    chatBox.setGravity(Gravity.LEFT);
                    messageText.setGravity(Gravity.LEFT);
                }
            });
            timeText.setText(mDateFormat.format(new Date(message.getReceivedTime())));
            messageText.setText(Html.fromHtml(message.getBody(), null, null));
            messageText.setMovementMethod(LinkMovementMethod.getInstance());

            return v;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return false; // Makes links clickable.
        }
    }
}
