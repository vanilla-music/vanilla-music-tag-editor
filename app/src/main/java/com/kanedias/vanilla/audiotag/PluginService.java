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

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.support.copied.FileProvider;
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
import org.jaudiotagger.tag.id3.valuepair.ImageFormats;
import org.jaudiotagger.tag.images.AndroidArtwork;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.reference.PictureTypes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

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

    /**
     * If this is called, then tag edit activity requested bind procedure for this service
     * Usually service is already started and has file field initialized.
     *
     * @param intent intent passed to start this service
     * @return null if file load failed, plugin binder object otherwise
     */
    @Override
    public IBinder onBind(Intent intent) {
        if (loadFile()) {
            return new PluginBinder();
        }
        return null;
    }

    /**
     * If this is called, then tag edit activity is finished with its user interaction and
     * service is safe to be stopped too.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        // we need to stop this service or ServiceConnection will remain active and onBind won't be called again
        // activity will see old file loaded in such case!
        stopSelf();
        return false;
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
        answer.setPackage(VANILLA_PACKAGE_NAME);
        answer.putExtra(EXTRA_PARAM_PLUGIN_NAME, getString(R.string.tag_editor));
        answer.putExtra(EXTRA_PARAM_PLUGIN_APP, getApplicationInfo());
        answer.putExtra(EXTRA_PARAM_PLUGIN_DESC, getString(R.string.plugin_desc));
        sendBroadcast(answer);
    }

    private void handleLaunchPlugin() {
        if (Utils.havePermissions(this, WRITE_EXTERNAL_STORAGE) && mLaunchIntent.hasExtra(EXTRA_PARAM_P2P)) {
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
        Uri fileUri = mLaunchIntent.getParcelableExtra(EXTRA_PARAM_URI);
        if (fileUri == null) {
            return false;
        }

        File file = new File(fileUri.getPath());
        if (!file.exists()) {
            return false;
        }

        try {
            mAudioFile = AudioFileIO.read(file);
            mTag = mAudioFile.getTagOrCreateAndSetDefault();
        } catch (CannotReadException | IOException | TagException | ReadOnlyFileException | InvalidAudioFrameException e) {
            Log.e(LOG_TAG,
                    String.format(getString(R.string.error_audio_file), fileUri), e);
            Toast.makeText(this,
                    String.format(getString(R.string.error_audio_file) + ", %s",
                            fileUri,
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
            // TODO: this does not conform to our new media-db project
            // TODO: we should create a way to update file via intent to vanilla-music
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
            case P2P_READ_ART: {
                ApplicationInfo responseApp = mLaunchIntent.getParcelableExtra(EXTRA_PARAM_PLUGIN_APP);
                Artwork cover = mTag.getFirstArtwork();
                Uri uri = null;
                try {
                    if (cover == null) {
                        Log.w(LOG_TAG, "Artwork is not present for file " + mAudioFile.getFile().getName());
                        break;
                    }

                    File coversDir = new File(getCacheDir(), "covers");
                    if (!coversDir.exists() && !coversDir.mkdir()) {
                        Log.e(LOG_TAG, "Couldn't create dir for covers! Path " + getCacheDir());
                        break;
                    }

                    // cleanup old images
                    for (File oldImg : coversDir.listFiles()) {
                        if (!oldImg.delete()) {
                            Log.w(LOG_TAG, "Couldn't delete old image file! Path " + oldImg);
                        }
                    }

                    // write artwork to file
                    File coverTmpFile = new File(coversDir, UUID.randomUUID().toString());
                    FileOutputStream fos = new FileOutputStream(coverTmpFile);
                    fos.write(cover.getBinaryData());
                    fos.close();

                    // create sharable uri
                    uri = FileProvider.getUriForFile(this, "com.kanedias.vanilla.audiotag.fileprovider", coverTmpFile);
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Couldn't write to cache file", e);
                    Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
                } finally {
                    // share uri if created successfully
                    Intent response = new Intent(ACTION_LAUNCH_PLUGIN);
                    response.putExtra(EXTRA_PARAM_P2P, P2P_READ_ART);
                    response.setPackage(responseApp.packageName);
                    if (uri != null) {
                        grantUriPermission(responseApp.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        response.putExtra(EXTRA_PARAM_P2P_VAL, uri);
                    }
                    startService(response);
                }
                break;
            }
            case P2P_WRITE_ART: {
                Uri imgLink = mLaunchIntent.getParcelableExtra(EXTRA_PARAM_P2P_VAL);

                try {
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(imgLink, "r");
                    if (pfd == null) {
                        return;
                    }

                    FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                    byte[] imgBytes = Utils.readFully(fis);

                    Artwork cover = new AndroidArtwork();
                    cover.setBinaryData(imgBytes);
                    cover.setMimeType(ImageFormats.getMimeTypeForBinarySignature(imgBytes));
                    cover.setDescription("");
                    cover.setPictureType(PictureTypes.DEFAULT_ID);

                    mTag.setField(cover);
                } catch (IOException | IllegalArgumentException | FieldDataInvalidException e) {
                    Log.e(LOG_TAG, "Invalid artwork!", e);
                    Toast.makeText(this, R.string.invalid_artwork_provided, Toast.LENGTH_SHORT).show();
                }

                persistChanges();
                break;
            }
        }

    }
}
