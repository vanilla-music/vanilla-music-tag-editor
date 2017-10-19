/*
 * Copyright (C) 2016 Oleg Chernovskiy <adonai@xaker.ru>
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
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.Toast;

import com.kanedias.vanilla.audiotag.misc.HintSpinnerAdapter;

import com.kanedias.vanilla.plugins.DialogActivity;
import com.kanedias.vanilla.plugins.PluginUtils;
import org.jaudiotagger.tag.FieldDataInvalidException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.ID3v22Tag;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static com.kanedias.vanilla.plugins.PluginConstants.*;

/**
 * Main activity of Tag Editor plugin. This will be presented as a dialog to the user
 * if one chooses it as the requested plugin.
 * <p/>
 * After tag editing is done, this activity updates media info of this file and exits.
 *
 * @see PluginService service that launches this
 *
 * @author Oleg Chernovskiy
 */
public class TagEditActivity extends DialogActivity {

    private static final int PERMISSIONS_REQUEST_CODE = 0;

    private EditText mTitleEdit;
    private EditText mArtistEdit;
    private EditText mAlbumEdit;
    private Spinner mCustomTagSelector;
    private EditText mCustomTagEdit;
    private Button mConfirm, mCancel;

    private PluginService mService;
    private Tag mTag;

    private ServiceConnection mServiceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service == null) { // couldn't load file
                finish();
                return;
            }

            mService = ((PluginService.PluginBinder) service).getService();
            mTag = mService.getTag();
            fillInitialValues();
            miscellaneousChecks();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tag_edit);

        mTitleEdit = (EditText) findViewById(R.id.song_title_edit);
        mArtistEdit = (EditText) findViewById(R.id.song_artist_edit);
        mAlbumEdit = (EditText) findViewById(R.id.song_album_edit);
        mCustomTagSelector = (Spinner) findViewById(R.id.song_custom_tag_selector);
        mCustomTagEdit = (EditText) findViewById(R.id.song_custom_tag_edit);
        mConfirm = (Button) findViewById(R.id.confirm_tags_button);
        mCancel = (Button) findViewById(R.id.cancel_tags_button);

        setupUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mService != null) {
            unbindService(mServiceConn);
        }
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
                mService.loadFile(true);
                mTag = mService.getTag();
                fillInitialValues();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Initialize UI elements with handlers and action listeners
     */
    private void setupUI() {
        mCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mService.writeFile();
                finish();
            }
        });

        SpinnerAdapter origAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, FieldKey.values());
        HintSpinnerAdapter hintAdapter = new HintSpinnerAdapter(origAdapter, R.layout.view_select_custom_tag, this);
        mCustomTagSelector.setAdapter(hintAdapter);
        mCustomTagSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

            private TextWatcher mCustomFieldListener;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                FieldKey key = (FieldKey) mCustomTagSelector.getItemAtPosition(position);
                if (key == null) {
                    return;
                }

                mCustomTagEdit.removeTextChangedListener(mCustomFieldListener); // don't trigger old field rewrite
                mCustomTagEdit.setText(mTag.getFirst(key));
                mCustomFieldListener = new FieldKeyListener(key);
                mCustomTagEdit.addTextChangedListener(mCustomFieldListener); // re-register with new field
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                mCustomTagEdit.removeTextChangedListener(mCustomFieldListener); // don't trigger old field rewrite
                mCustomTagEdit.setText("");
            }
        });

        mTitleEdit.addTextChangedListener(new FieldKeyListener(FieldKey.TITLE));
        mArtistEdit.addTextChangedListener(new FieldKeyListener(FieldKey.ARTIST));
        mAlbumEdit.addTextChangedListener(new FieldKeyListener(FieldKey.ALBUM));
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
            // and service passed P2P intent to activity in hope that it will sort it out.
            // We need to pass this intent back to service as user had approved permission request
            Intent serviceStart = new Intent(this, PluginService.class);
            serviceStart.setAction(ACTION_LAUNCH_PLUGIN);
            serviceStart.putExtras(getIntent());
            startService(serviceStart); // pass intent back to the service
            finish();
        } else {
            // it's non-P2P intent, prepare interaction and fire full-blown activity window
            // we'll need service at hand while editing
            Intent bind = new Intent(this, PluginService.class);
            bindService(bind, mServiceConn, 0);
        }
    }

    /**
     * Fills UI with initial values from loaded file. At this point {@link #mTag} must be initialized.
     */
    private void fillInitialValues() {
        mTitleEdit.setText(mTag.getFirst(FieldKey.TITLE));
        mArtistEdit.setText(mTag.getFirst(FieldKey.ARTIST));
        mAlbumEdit.setText(mTag.getFirst(FieldKey.ALBUM));
        mCustomTagSelector.setSelection(0);
        mCustomTagEdit.setText("");
    }

    /**
     * Miscellaneous checks, e.g. re-tag requests etc.
     */
    private void miscellaneousChecks() {
        // check we need a re-tag
        if (mTag instanceof ID3v22Tag) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.re_tag)
                    .setMessage(R.string.id3_v22_to_v24)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            mService.upgradeID3v2();
                            mTag = mService.getTag(); // tag was updated, refresh it from service
                        }
                    })
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

    private final class FieldKeyListener implements TextWatcher {

        private final FieldKey key;

        private FieldKeyListener(FieldKey key) {
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
                mTag.setField(key, s.toString());
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
