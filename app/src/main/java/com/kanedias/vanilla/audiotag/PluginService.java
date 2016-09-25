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

import android.app.IntentService;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

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
import static com.kanedias.vanilla.audiotag.PluginConstants.*;

/**
 * Main service of Plugin system.
 * This service must be able to handle ACTION_WAKE_PLUGIN, ACTION_REQUEST_PLUGIN_PARAMS and ACTION_LAUNCH_PLUGIN
 * intents coming from VanillaMusic.
 * <p/>
 * Casual conversation looks like this:
 * <pre>
 *     VanillaMusic                                 Plugin
 *          |                                         |
 *          |       ACTION_WAKE_PLUGIN broadcast      |
 *          |---------------------------------------->| (plugin init if just installed)
 *          |                                         |
 *          | ACTION_REQUEST_PLUGIN_PARAMS broadcast  |
 *          |---------------------------------------->| (this is handled by BroadcastReceiver first)
 *          |                                         |
 *          |      ACTION_HANDLE_PLUGIN_PARAMS        |
 *          |<----------------------------------------| (plugin answer with name and desc)
 *          |                                         |
 *          |           ACTION_LAUNCH_PLUGIN          |
 *          |---------------------------------------->| (plugin is allowed to show window)
 * </pre>
 *
 * @see PluginConstants
 * @see TagEditActivity
 *
 * @author Oleg Chernovskiy
 */
public class PluginService extends Service {

    private Intent mLaunchIntent;
    private AudioFile mAudioFile;
    private Tag mTag;

    public class PluginBinder extends Binder {

        public PluginService getService() {
            return PluginService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (loadFile()) {
            return new PluginBinder();
        }
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            final String action = intent.getAction();
            switch (action) {
                case ACTION_WAKE_PLUGIN:
                    Log.i(LOG_TAG, "Plugin enabled!");
                    break;
                case ACTION_REQUEST_PLUGIN_PARAMS:
                    handleRequestPluginParams();
                    break;
                case ACTION_LAUNCH_PLUGIN:
                    mLaunchIntent = intent;
                    handleLaunchPlugin();
                    break;
                default:
                    Log.e(LOG_TAG, "Unknown intent action received!" + action);
            }
        }
        return START_NOT_STICKY;
    }

    /**
     * Sends plugin info back to Vanilla Music service.
     */
    private void handleRequestPluginParams() {
        Intent answer = new Intent(ACTION_HANDLE_PLUGIN_PARAMS);
        answer.setComponent(new ComponentName(VANILLA_PACKAGE_NAME, VANILLA_PACKAGE_NAME + VANILLA_SERVICE_NAME));
        answer.putExtra(EXTRA_PARAM_PLUGIN_NAME, getString(R.string.tag_editor));
        answer.putExtra(EXTRA_PARAM_PLUGIN_APP, getApplicationInfo());
        answer.putExtra(EXTRA_PARAM_PLUGIN_DESC, getString(R.string.plugin_desc));
        getApplicationContext().startService(answer);
    }

    private void handleLaunchPlugin() {
        int permResponse = ContextCompat.checkSelfPermission(this, WRITE_EXTERNAL_STORAGE);
        boolean hasAccessToSd = permResponse == PackageManager.PERMISSION_GRANTED;
        if (hasAccessToSd && mLaunchIntent.hasExtra(EXTRA_PARAM_P2P)) {
            if(loadFile()) {
                handleP2pIntent();
            }
            return;
        }

        Intent dialogIntent = new Intent(this, TagEditActivity.class);
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        dialogIntent.putExtras(mLaunchIntent);
        startActivity(dialogIntent);
    }

    public Tag getTag() {
        return mTag;
    }

    /**
     * Loads file as {@link AudioFile} and performs initial tag creation if it's absent.
     * If error happens while loading, shows popup indicating error details.
     * @return true if and only if file was successfully read and initialized in tag system, false otherwise
     */
    public boolean loadFile() {
        // we need only path passed to us
        String filePath = mLaunchIntent.getStringExtra(EXTRA_PARAM_FILE_PATH);
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
            Log.e(LOG_TAG,
                    String.format(getString(R.string.error_audio_file), filePath), e);
            Toast.makeText(this,
                    String.format(getString(R.string.error_audio_file) + ", %s",
                            filePath,
                            e.getLocalizedMessage()),
                    Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    /**
     * Writes changes in tags into file and closes activity.
     * If something goes wrong, leaves activity intact.
     */
    public void persistChanges() {
        try {
            AudioFileIO.write(mAudioFile);
            Toast.makeText(this, R.string.file_written_successfully, Toast.LENGTH_SHORT).show();

            // update media database
            MediaScannerConnection.scanFile(this,
                    new String[]{mAudioFile.getFile().getAbsolutePath()},
                    null,
                    null);
        } catch (CannotWriteException e) {
            Log.e(LOG_TAG,
                    String.format(getString(R.string.error_audio_file), mAudioFile.getFile().getPath()), e);
            Toast.makeText(this,
                    String.format(getString(R.string.error_audio_file) + ", %s",
                            mAudioFile.getFile().getPath(),
                            e.getLocalizedMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * This plugin also has P2P functionality with others. It provides generic way to
     * read and write tags for the file.
     * <br/>
     * If intent is passed with EXTRA_PARAM_P2P and READ then EXTRA_PARAM_P2P_KEY is considered
     * as an array of field keys to retrieve from file. The values read are written in the same order
     * into answer intent into EXTRA_PARAM_P2P_VAL.
     * <br/>
     * If intent is passed with EXTRA_PARAM_P2P and WRITE then EXTRA_PARAM_P2P_KEY is considered
     * as an array of field keys to write to file. EXTRA_PARAM_P2P_VAL represents values to be written in
     * the same order.
     *
     */
    private void handleP2pIntent() {
        String request = mLaunchIntent.getStringExtra(EXTRA_PARAM_P2P);
        switch (request) {
            case P2P_WRITE_TAG: {
                String[] fields = mLaunchIntent.getStringArrayExtra(EXTRA_PARAM_P2P_KEY);
                String[] values = mLaunchIntent.getStringArrayExtra(EXTRA_PARAM_P2P_VAL);
                for (int i = 0; i < fields.length; ++i) {
                    try {
                        FieldKey key = FieldKey.valueOf(fields[i]);
                        mTag.setField(key, values[i]);
                    } catch (IllegalArgumentException iae) {
                        Log.e(LOG_TAG, "Invalid tag requested: " + fields[i], iae);
                        Toast.makeText(this, R.string.invalid_tag_requested, Toast.LENGTH_SHORT).show();
                    } catch (FieldDataInvalidException e) {
                        // should not happen
                        Log.e(LOG_TAG, "Error writing tag", e);
                    }
                }
                persistChanges();
                break;
            }
            case P2P_READ_TAG: {
                String[] fields = mLaunchIntent.getStringArrayExtra(EXTRA_PARAM_P2P_KEY);
                ApplicationInfo responseApp = mLaunchIntent.getParcelableExtra(EXTRA_PARAM_PLUGIN_APP);

                String[] values = new String[fields.length];
                for (int i = 0; i < fields.length; ++i) {
                    try {
                        FieldKey key = FieldKey.valueOf(fields[i]);
                        values[i] = mTag.getFirst(key);
                    } catch (IllegalArgumentException iae) {
                        Log.e(LOG_TAG, "Invalid tag requested: " + fields[i], iae);
                        Toast.makeText(this, R.string.invalid_tag_requested, Toast.LENGTH_SHORT).show();
                    }
                }

                Intent response = new Intent(ACTION_LAUNCH_PLUGIN);
                response.putExtra(EXTRA_PARAM_P2P, P2P_READ_TAG);
                response.setPackage(responseApp.packageName);
                response.putExtra(EXTRA_PARAM_P2P_VAL, values);
                startService(response);
                break;
            }
        }

    }
}
