/*
 * Copyright (C) 2017 Oleg Chernovskiy <adonai@xaker.ru>
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

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import org.jaudiotagger.audio.AudioFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Compat utilities mostly
 *
 * @author Oleg Chernovskiy
 */
public class TagEditorUtils {

    /**
     * Checks if all required permissions have been granted
     *
     * @param context The context to use
     * @return boolean true if all permissions have been granded
     */
    public static boolean havePermissions(Context context, String perm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        } // else: granted during installation
        return true;
    }

    // Reads an InputStream fully to byte array
    public static byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            baos.write(buffer, 0, count);
        }

        stream.close();
        return baos.toByteArray();
    }


    /**
     * Shortcut for {@link #isSafNeeded(File)} for {@link AudioFile}
     */
    public static boolean isSafNeeded(AudioFile af) {
        return isSafNeeded(af.getFile());
    }

    /**
     * Check if Android Storage Access Framework routines apply here
     * @return true if document seems to be SAF-accessible only, false otherwise
     */
    public static boolean isSafNeeded(File file) {
        // on external SD card after KitKat this will return false
        return !file.canWrite() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
    }

}
