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
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
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
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.CannotWriteException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldDataInvalidException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;

import java.io.File;
import java.io.IOException;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

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

    private EditText mTitleEdit;
    private EditText mArtistEdit;
    private EditText mAlbumEdit;
    private Spinner mCustomTagSelector;
    private EditText mCustomTagEdit;
    private Button mConfirm, mCancel;

    private AudioFile mAudioFile;
    private Tag mTag;

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
                try {
                    AudioFileIO.write(mAudioFile);
                    Toast.makeText(TagEditActivity.this, R.string.file_written_successfully, Toast.LENGTH_SHORT).show();

                    // update media database
                    MediaScannerConnection.scanFile(TagEditActivity.this,
                            new String[]{mAudioFile.getFile().getAbsolutePath()},
                            null,
                            null);
                    finish();
                } catch (CannotWriteException e) {
                    Log.e(PluginConstants.LOG_TAG,
                            String.format(getString(R.string.error_audio_file), mAudioFile.getFile().getPath()), e);
                    Toast.makeText(TagEditActivity.this,
                            String.format(getString(R.string.error_audio_file) + ", details: %s",
                                mAudioFile.getFile().getPath(),
                                e.getLocalizedMessage()),
                            Toast.LENGTH_SHORT).show();
                }
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

    @Override
    protected void onResume() {
        super.onResume();

        // onResume will fire both on first launch and on return from permission request
        if (checkAndRequestPermissions(WRITE_EXTERNAL_STORAGE)) {
            if (loadFile()) {
                fillInitialValues();
            } else {
                finish(); // couldn't load file, exiting
            }
        }
    }

    /**
     * Fills UI with initial values from loaded file. At this point both {@link #mAudioFile}
     * and {@link #mTag} must be initialized.
     */
    private void fillInitialValues() {
        mTitleEdit.setText(mTag.getFirst(FieldKey.TITLE));
        mArtistEdit.setText(mTag.getFirst(FieldKey.ARTIST));
        mAlbumEdit.setText(mTag.getFirst(FieldKey.ALBUM));
    }

    /**
     * Loads file as {@link AudioFile} and performs initial tag creation if it's absent.
     * If error happens while loading, shows popup indicating error details.
     * @return true if and only if file was successfully read and initialized in tag system, false otherwise
     */
    private boolean loadFile() {
        // we need only path passed to us
        String filePath = getIntent().getStringExtra(PluginConstants.EXTRA_PARAM_FILE_PATH);
        if (TextUtils.isEmpty(filePath)) {
            return false;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return false;
        }

        try {
            mAudioFile = AudioFileIO.read(file);
            mTag = mAudioFile.getTagOrCreateAndSetDefault();
        } catch (CannotReadException | IOException | TagException | ReadOnlyFileException | InvalidAudioFrameException e) {
            Log.e(PluginConstants.LOG_TAG,
                    String.format(getString(R.string.error_audio_file), filePath), e);
            Toast.makeText(this,
                    String.format(getString(R.string.error_audio_file) + ", details: %s",
                            filePath,
                            e.getLocalizedMessage()),
                    Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    /**
     * We're the good guys, we catch it back from {@link #checkAndRequestPermissions(String)} here.
     * So, if user declined our request, just close the activity entirely.
     * @param requestCode request code that was entered in {@link ActivityCompat#requestPermissions(Activity, String[], int)}
     * @param permissions permission array that was entered in {@link ActivityCompat#requestPermissions(Activity, String[], int)}
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

    /**
     * Checks for permission and requests it if needed.
     * You should catch answer back in {@link #onRequestPermissionsResult(int, String[], int[])}
     * <br/>
     * (Or don't. This way request will appear forever as {@link #onResume()} will never end)
     * @param perm permission to request
     * @return true if this app had this permission prior to check, false otherwise.
     */
    private boolean checkAndRequestPermissions(String perm) {
        int permissionCheck = ContextCompat.checkSelfPermission(this, perm);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{perm}, PERMISSIONS_REQUEST_CODE);
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
                Log.e(PluginConstants.LOG_TAG, "Error writing tag", e);
            }
        }
    }
}
