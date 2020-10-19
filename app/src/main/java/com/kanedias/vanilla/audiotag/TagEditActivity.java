/*
 * Copyright (C) 2016-2018 Oleg Chernovskiy <adonai@xaker.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.kanedias.vanilla.audiotag;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.kanedias.vanilla.audiotag.misc.HintSpinnerAdapter;
import com.kanedias.vanilla.plugins.DialogActivity;
import com.kanedias.vanilla.plugins.PluginUtils;

import org.jaudiotagger.tag.FieldDataInvalidException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.id3.ID3v22Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static com.kanedias.vanilla.plugins.PluginConstants.*;

/**
 * Main activity of Tag Editor plugin. This will be presented as a dialog to the user
 * if one chooses it as the requested plugin.
 *
 * This activity must be able to handle ACTION_WAKE_PLUGIN and ACTION_LAUNCH_PLUGIN
 * intents coming from VanillaMusic. In case these intents are coming from other plugins
 * the activity must try to just silently do required operations without showing actual
 * activity window.
 *
 * <p/>
 * Casual conversation looks like this:
 * <pre>
 *     VanillaMusic                                 Plugin
 *          |                                         |
 *          |       ACTION_WAKE_PLUGIN broadcast      |
 *          |---------------------------------------->| (plugin init if just installed)
 *          |                                         |
 *          | ACTION_REQUEST_PLUGIN_PARAMS broadcast  |
 *          |---------------------------------------->| (this is handled by BroadcastReceiver)
 *          |                                         |
 *          |      ACTION_HANDLE_PLUGIN_PARAMS        |
 *          |<----------------------------------------| (plugin answer with name and desc)
 *          |                                         |
 *          |           ACTION_LAUNCH_PLUGIN          |
 *          |---------------------------------------->| (plugin is allowed to show window)
 * </pre>
 *
 * <p/>
 * After tag editing is done, this activity updates media info of this file and exits.
 *
 * @author Oleg Chernovskiy
 */
public class TagEditActivity extends DialogActivity {

    private static final int PERMISSIONS_REQUEST_CODE = 0;

    private ScrollView mParentScroll;
    private LinearLayout mTagArea;
    private Spinner mCustomTagSelector;
    private Button mConfirm, mCancel;

    private PluginTagWrapper mWrapper;
    private List<FieldKey> mShownTags = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mWrapper = new PluginTagWrapper(getIntent(), this);
        if (handleLaunchPlugin()) {
            // no UI was required for handling the intent
            return;
        }

        setContentView(R.layout.activity_tag_edit);

        mParentScroll = findViewById(R.id.parent_scroll);
        mTagArea = findViewById(R.id.tag_edit_area);
        mCustomTagSelector = findViewById(R.id.song_custom_tag_selector);
        mConfirm = findViewById(R.id.confirm_tags_button);
        mCancel = findViewById(R.id.cancel_tags_button);

        setupUI();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = new MenuInflater(this);
        inflater.inflate(R.menu.tag_options, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.reload_option:
                mWrapper.loadFile(true);
                fillInitialValues();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Initialize UI elements with handlers and action listeners
     */
    private void setupUI() {
        mCancel.setOnClickListener(v -> finish());
        mConfirm.setOnClickListener(v -> {
            boolean done = mWrapper.writeFile();
            if (done) {
                finish();
            }
        });

        SpinnerAdapter origAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, FieldKey.values());
        HintSpinnerAdapter hintAdapter = new HintSpinnerAdapter(origAdapter, R.layout.view_select_custom_tag, this);
        mCustomTagSelector.setAdapter(hintAdapter);
        mCustomTagSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                FieldKey key = (FieldKey) mCustomTagSelector.getItemAtPosition(position);
                addCustomTagField(key);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }

    /**
     * Add editable text area for filling custom tag field.
     * Listener will be already attached to it.
     *
     * Calling this method with the key that is already present in the
     * activity view will do nothing
     *
     * @param key tag key to add edit text for
     */
    private void addCustomTagField(@Nullable FieldKey key) {
        if (key == null) {
            return;
        }

        // cover art is not editable from tag editor, we have other plugins for this
        // don't add this view
        if (key == FieldKey.COVER_ART) {
            return;
        }

        // check that we don't have such layout
        if (mShownTags.contains(key)) {
            // we have such layout, focus on it
            View shownLayout = mTagArea.getChildAt(mShownTags.indexOf(key));
            EditText shownEdit = shownLayout.findViewById(R.id.song_custom_edit);
            shownEdit.requestFocus();
            return;
        }

        View customLayout = LayoutInflater.from(this).inflate(R.layout.view_tag_field_item, mTagArea, false);
        TextView label = customLayout.findViewById(R.id.song_custom_label);
        EditText edit = customLayout.findViewById(R.id.song_custom_edit);

        if (key == FieldKey.LYRICS) {
            edit.setInputType(InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edit.setSingleLine(false);
            edit.setLines(3);
            edit.setMaxLines(6);
        }

        label.setText(capitalize(key.name().replace('_', ' ').toLowerCase()));
        edit.setText(mWrapper.getTag().getFirst(key));
        edit.addTextChangedListener(new FieldKeyListener(key, customLayout));

        mTagArea.addView(customLayout);
        customLayout.requestFocus();
    }

    public static String capitalize(String input) {
        return input != null && input.length() != 0
                ? input.substring(0, 1).toUpperCase(Locale.ENGLISH) + input.substring(1)
                : input;
    }

    /**
     * Handle Vanilla Music player intents. This will show activity window (in most cases) and load
     * all needed info from file.
     */
    @Override
    protected void onResume() {
        super.onResume();

        // onResume will fire both on first launch and on return from permission request
        if (!PluginUtils.checkAndRequestPermissions(this, WRITE_EXTERNAL_STORAGE)) {
            return;
        }

        if (getIntent().hasExtra(EXTRA_PARAM_P2P)) {
            // if we're here, then user didn't grant tag editor "write to SD" permission before
            // We need to handle this intent again
            handleLaunchPlugin();
            return;
        }

        // if we're here the user requested the tag editor directly
        mWrapper.loadFile(true);
        fillInitialValues();
        miscellaneousChecks();
    }

    /**
     * Handle incoming intent that may possible be ping, other plugin request or user-interactive plugin request
     * @return true if intent was handled internally, false if activity startup is required
     */
    private boolean handleLaunchPlugin() {
        if (TextUtils.equals(getIntent().getAction(), ACTION_WAKE_PLUGIN)) {
            // just show that we're okay
            Log.i(LOG_TAG, "Plugin enabled!");
            finish();
            return true;
        }

        boolean fromPlugin = getIntent().hasExtra(EXTRA_PARAM_P2P); // requested from other plugin
        if (PluginUtils.havePermissions(this, WRITE_EXTERNAL_STORAGE) && fromPlugin) {
            // it's P2P intent, just try to read/write file as requested
            if(mWrapper.loadFile(false)) {
                mWrapper.handleP2pIntent();
            }

            if (!mWrapper.isWaitingSafResponse()) {
                finish();
            }
            return true;
        }

        // continue startup
        return false;
    }

    /**
     * Fills UI with initial values from loaded file.
     */
    private void fillInitialValues() {
        // predefined fields
        addCustomTagField(FieldKey.TITLE);
        addCustomTagField(FieldKey.ARTIST);
        addCustomTagField(FieldKey.ALBUM);

        for (FieldKey key: FieldKey.values()) {
            if (mWrapper.getTag().hasField(key)) {
                addCustomTagField(key);
            }
        }

        mCustomTagSelector.setSelection(0);
    }

    /**
     * Miscellaneous checks, e.g. re-tag requests etc.
     */
    private void miscellaneousChecks() {
        // check we need a re-tag
        if (mWrapper.getTag() instanceof ID3v22Tag) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.re_tag)
                    .setMessage(R.string.id3_v22_to_v24)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> mWrapper.upgradeID3v2())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    /**
     * We're the good guys, we catch it back from {@link PluginUtils#checkAndRequestPermissions(Activity, String)} here.
     * So, if user declined our request, just close the activity entirely.
     * @param requestCode request code that was entered in {@link Activity#requestPermissions(String[], int)}
     * @param permissions permission array that was entered in {@link Activity#requestPermissions(String[], int)}
     * @param grantResults results of permission request. Indexes of permissions array are linked with these
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != PERMISSIONS_REQUEST_CODE) {
            return;
        }

        for (int i = 0; i < permissions.length; ++i) {
            if (TextUtils.equals(permissions[i], WRITE_EXTERNAL_STORAGE)
                    && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                finish(); // user denied our request, don't bother again on resume
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // handle SAF response
        mWrapper.onActivityResult(requestCode, resultCode, data);
    }

    private final class FieldKeyListener implements TextWatcher {

        private final FieldKey key;
        private View layout = null;

        /**
         * Constructor for custom fields
         * @param key tag key this listener should track
         * @param layout, layout that this listener should remove if tag is empty
         */
        private FieldKeyListener(FieldKey key, View layout) {
            mShownTags.add(key);
            this.key = key;
            this.layout = layout;
        }

        /**
         * Constructor for default fields (title, artist, etc.)
         * @param key tag key this listener should track
         */
        private FieldKeyListener(FieldKey key) {
            mShownTags.add(key);
            this.key = key;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            try {
                if (s.toString().isEmpty()) {
                    mWrapper.getTag().deleteField(key);
                    if (layout != null) {
                        // if it was custom field, remove it altogether
                        mShownTags.remove(key);
                        int idx = mTagArea.indexOfChild(layout);

                        if (idx > 0) {
                            // focus on previous field
                            mTagArea.getChildAt(idx - 1).requestFocus();
                        }
                        mTagArea.removeView(layout);
                    }
                    return;
                }

                mWrapper.getTag().setField(key, s.toString());
            } catch (FieldDataInvalidException e) {
                // should not happen
                Log.e(LOG_TAG, "Error writing tag", e);
            } catch (UnsupportedOperationException uoe) {
                // e.g. key == FieldKey.COVER_ART
                Toast.makeText(TagEditActivity.this, R.string.not_supported_use_other_plugin,
                        Toast.LENGTH_LONG).show();
                Log.e(LOG_TAG, "Unsupported writing text to this tag", uoe);
            }
        }
    }
}
