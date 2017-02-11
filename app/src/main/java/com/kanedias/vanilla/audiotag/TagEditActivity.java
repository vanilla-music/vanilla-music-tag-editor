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

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.Toast;

import com.kanedias.vanilla.audiotag.misc.HintSpinnerAdapter;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.tag.FieldDataInvalidException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;
import java.io.FileNotFoundException;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static com.kanedias.vanilla.audiotag.PluginConstants.*;

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
public class TagEditActivity extends Activity {

    private static final int PERMISSIONS_REQUEST_CODE = 0;
    private static final int SAF_REQUEST_CODE = 1;

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
                if (isSafRelated()) {
                    // will be handled after file is picked
                    return;
                }
                mService.persistChanges();
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
     * Check if Android Storage Access Framework routines apply here
     * @return true if document seems to be SAF-accessible only, false otherwise
     */
    private boolean isSafRelated() {
        // on external SD card this will return false, workaround it
        if (!mService.getAudioFile().getFile().canWrite() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Toast.makeText(this,R.string.file_on_external_sd_warning, Toast.LENGTH_LONG).show();
            Toast.makeText(this,R.string.file_on_external_sd_workaround, Toast.LENGTH_LONG).show();
            callSafFilePicker();
            return true;
        }

        return false;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void callSafFilePicker() {
        Intent selectFile = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        selectFile.addCategory(Intent.CATEGORY_OPENABLE);
        selectFile.setType("audio/*");
        startActivityForResult(selectFile, SAF_REQUEST_CODE);
    }

    /**
     * Handle Vanilla Music player intents. This will show activity window (in most cases) and load
     * all needed info from file.
     */
    @Override
    protected void onResume() {
        super.onResume();

        // onResume will fire both on first launch and on return from permission request
        if (!checkAndRequestPermissions(WRITE_EXTERNAL_STORAGE)) {
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
    }

    /**
     * We're the good guys, we catch it back from {@link #checkAndRequestPermissions(String)} here.
     * So, if user declined our request, just close the activity entirely.
     * @param requestCode request code that was entered in {@link Activity#requestPermissions(String[], int)}
     * @param permissions permission array that was entered in {@link Activity#requestPermissions(String[], int)}
     * @param grantResults results of permission request. Indexes of permissions array are linked with these
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
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
        if (requestCode == SAF_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            Uri safUri = data.getData();
            if (safUri == null) {
                // nothing selected
                return;
            }

            try {
                // we don't have fd-related audiotagger write functions, have to use workaround
                // some low-level Linux sorcery
                AudioFile af = mService.getAudioFile();
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(safUri, "rw");
                File fdLink = new File("/proc/" + android.os.Process.myPid() + "/fd/" + pfd.getFd());
                File realFile = new File(fdLink.getAbsolutePath());
                File virtFile = new File(getCacheDir().getAbsolutePath() + "/" + af.getFile().getName()); // f
                Runtime.getRuntime().exec("ln -s " + fdLink.getAbsolutePath() + " " + virtFile).waitFor();
                af.setFile(virtFile);
                mService.persistChanges();
                virtFile.delete();
                finish();
            } catch (Exception e) {
                // do nothing
            }
        }
    }

    /**
     * Checks for permission and requests it if needed.
     * You should catch answer back in {@link #onRequestPermissionsResult(int, String[], int[])}
     * <br/>
     * (Or don't. This way request will appear forever as {@link #onResume()} will never end)
     * @param perm permission to request
     * @return true if this app had this permission prior to check, false otherwise.
     */
    private boolean checkAndRequestPermissions(String perm) {
        if (!Utils.havePermissions(this, perm)  && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{perm}, PERMISSIONS_REQUEST_CODE);
            return false;
        }
        return true;
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
            }
        }
    }
}
