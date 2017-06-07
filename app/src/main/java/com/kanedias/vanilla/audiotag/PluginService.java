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
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.content.FileProvider;
import android.support.v4.provider.DocumentFile;
import android.util.Log;
import android.widget.Toast;

import com.kanedias.vanilla.plugins.PluginConstants;
import com.kanedias.vanilla.plugins.PluginUtils;
import com.kanedias.vanilla.plugins.saf.SafRequestActivity;
import com.kanedias.vanilla.plugins.saf.SafUtils;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.CannotWriteException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.audio.generic.Utils;
import org.jaudiotagger.tag.*;
import org.jaudiotagger.tag.id3.valuepair.ImageFormats;
import org.jaudiotagger.tag.images.AndroidArtwork;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.reference.PictureTypes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static com.kanedias.vanilla.plugins.PluginConstants.*;

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

    private AtomicInteger mBindCounter = new AtomicInteger(0);

    private SharedPreferences mPrefs;

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
        mBindCounter.incrementAndGet();
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
        if(mBindCounter.decrementAndGet() == 0) {
            stopSelf();
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        TagOptionSingleton.getInstance().setAndroid(true);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
    }

    /**
     * Main plugin service operation entry point. This is called each time plugins are quieried
     * and requested by main Vanilla Music app and also when plugins communicate with each other through P2P-intents.
     * @param intent intent provided by broadcast or request
     * @param flags - not used
     * @param startId - not used
     * @return always constant {@link #START_NOT_STICKY}
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            final String action = intent.getAction();
            switch (action) {
                case ACTION_WAKE_PLUGIN:
                    Log.i(LOG_TAG, "Plugin enabled!");
                    break;
                case ACTION_REQUEST_PLUGIN_PARAMS:
                    handleRequestPluginParams(intent);
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
     * @param intent intent from player
     */
    private void handleRequestPluginParams(Intent intent) {
        Intent answer = new Intent(ACTION_HANDLE_PLUGIN_PARAMS);
        answer.setPackage(intent.getPackage());
        answer.putExtra(EXTRA_PARAM_PLUGIN_NAME, getString(R.string.tag_editor));
        answer.putExtra(EXTRA_PARAM_PLUGIN_APP, getApplicationInfo());
        answer.putExtra(EXTRA_PARAM_PLUGIN_DESC, getString(R.string.plugin_desc));
        sendBroadcast(answer);
    }

    private void handleLaunchPlugin() {
        if (mLaunchIntent.hasExtra(EXTRA_PARAM_SAF_P2P)) {
            // it's SAF intent that is returned from SAF activity, should have URI inside
            persistThroughSaf(mLaunchIntent);
            return;
        }

        // if it's P2P intent, just try to read/write file as requested
        if (PluginUtils.havePermissions(this, WRITE_EXTERNAL_STORAGE) && mLaunchIntent.hasExtra(EXTRA_PARAM_P2P)) {
            if(loadFile()) {
                handleP2pIntent();
            }
            stopSelf();
            return;
        }

        // either we have no permissions to write to SD and activity is requested
        // or this is normal user-requested operation (non-P2P)
        // start activity!
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
        if (mTag != null) {
            return true; // don't reload same file
        }

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
                    String.format(getString(R.string.error_audio_file), file.getAbsolutePath()), e);
            Toast.makeText(this,
                    String.format(getString(R.string.error_audio_file) + ", %s",
                            file.getAbsolutePath(),
                            e.getLocalizedMessage()),
                    Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    public void writeFile() {
        if (SafUtils.isSafNeeded(mAudioFile.getFile())) {
            if (mPrefs.contains(PREF_SDCARD_URI)) {
                // we already got the permission!
                persistThroughSaf(null);
                return;
            }

            // request SAF permissions in SAF activity
            Intent safIntent = new Intent(this, SafRequestActivity.class);
            safIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            safIntent.putExtra(PluginConstants.EXTRA_PARAM_PLUGIN_APP, getApplicationInfo());
            safIntent.putExtras(mLaunchIntent);
            startActivity(safIntent);
            // it will pass us URI back after the work is done
        } else {
            persistThroughFile();
        }
    }

    /**
     * Writes changes in tags directly into file and closes activity.
     * Call this if you're absolutely sure everything is right with file and tag.
     */
    private void persistThroughFile() {
        try {
            AudioFileIO.write(mAudioFile);
            Toast.makeText(this, R.string.file_written_successfully, Toast.LENGTH_SHORT).show();

            // update media database
            File persisted = mAudioFile.getFile();
            MediaScannerConnection.scanFile(this, new String[]{persisted.getAbsolutePath()}, null, null);
        } catch (CannotWriteException e) {
            Log.e(LOG_TAG,
                    String.format(getString(R.string.error_audio_file), mAudioFile.getFile().getPath()), e);
            Toast.makeText(this,
                    String.format(getString(R.string.error_audio_file) + ", %s",
                            mAudioFile.getFile().getPath(),
                            e.getLocalizedMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Write changes through SAF framework - the only way to do it in Android > 4.4 when working with SD card
     * @param activityResponse response with URI contained in. Can be null if tree permission is already given.
     */
    private void persistThroughSaf(Intent activityResponse) {
        Uri safUri;
        if (mPrefs.contains(PREF_SDCARD_URI)) {
            // no sorcery can allow you to gain URI to the document representing file you've been provided with
            // you have to find it again using now Document API

            // /storage/volume/Music/some.mp3 will become [storage, volume, music, some.mp3]
            List<String> pathSegments = new ArrayList<>(Arrays.asList(mAudioFile.getFile().getAbsolutePath().split("/")));
            Uri allowedSdRoot = Uri.parse(mPrefs.getString(PREF_SDCARD_URI, ""));
            safUri = findInDocumentTree(DocumentFile.fromTreeUri(this, allowedSdRoot), pathSegments);
        } else {
            Intent originalSafResponse = activityResponse.getParcelableExtra(EXTRA_PARAM_SAF_P2P);
            safUri = originalSafResponse.getData();
        }

        if (safUri == null) {
            // nothing selected or invalid file?
            Toast.makeText(this, R.string.saf_nothing_selected, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            // we don't have fd-related audiotagger write functions, have to use workaround
            // write audio file to temp cache dir
            // jaudiotagger can't work through file descriptor, sadly
            File original = mAudioFile.getFile();
            File temp = File.createTempFile("tmp-media", '.' + Utils.getExtension(original));
            Utils.copy(original, temp); // jtagger writes only header, we should copy the rest
            temp.deleteOnExit(); // in case of exception it will be deleted too
            mAudioFile.setFile(temp);
            AudioFileIO.write(mAudioFile);

            // retrieve FD from SAF URI
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(safUri, "rw");
            if (pfd == null) {
                // should not happen
                Log.e(LOG_TAG, "SAF provided incorrect URI!" + safUri);
                return;
            }

            // now read persisted data and write it to real FD provided by SAF
            FileInputStream fis = new FileInputStream(temp);
            byte[] audioContent = TagEditorUtils.readFully(fis);
            FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
            fos.write(audioContent);
            fos.close();

            // delete temporary file used
            temp.delete();

            // rescan original file
            MediaScannerConnection.scanFile(this, new String[]{original.getAbsolutePath()}, null, null);
            Toast.makeText(this, R.string.file_written_successfully, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.saf_write_error) + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            Log.e(LOG_TAG, "Failed to write to file descriptor provided by SAF!", e);
        }
    }

    /**
     * Finds needed file through Document API for SAF. It's not optimized yet - you can still gain wrong URI on
     * files such as "/a/b/c.mp3" and "/b/a/c.mp3", but I consider it complete enough to be usable.
     * @param currentDir - document file representing current dir of search
     * @param remainingPathSegments - path segments that are left to find
     * @return URI for found file. Null if nothing found.
     */
    @Nullable
    private Uri findInDocumentTree(DocumentFile currentDir, List<String> remainingPathSegments) {
        for (DocumentFile file : currentDir.listFiles()) {
            int index = remainingPathSegments.indexOf(file.getName());
            if (index == -1) {
                continue;
            }

            if (file.isDirectory()) {
                remainingPathSegments.remove(file.getName());
                return findInDocumentTree(file, remainingPathSegments);
            }

            if (file.isFile() && index == remainingPathSegments.size() - 1) {
                // got to the last part
                return file.getUri();
            }
        }

        return null;
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
                writeFile();
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
                    byte[] imgBytes = TagEditorUtils.readFully(fis);

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

                writeFile();
                break;
            }
        }

    }
}
