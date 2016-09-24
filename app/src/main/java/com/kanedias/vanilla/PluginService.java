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
package com.kanedias.vanilla;

import android.app.IntentService;
import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;

import static com.kanedias.vanilla.PluginConstants.*;

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
public class PluginService extends IntentService {

    public PluginService() {
        super("PluginService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            switch (action) {
                case ACTION_WAKE_PLUGIN:
                    Log.i(PluginConstants.LOG_TAG, "Plugin enabled!");
                    return;
                case ACTION_REQUEST_PLUGIN_PARAMS:
                    handleRequestPluginParams();
                    return;
                case ACTION_LAUNCH_PLUGIN:
                    handleLaunchPlugin(intent);
                    return;
                default:
                    Log.e(PluginConstants.LOG_TAG, "Unknown intent action received!" + action);
            }
        }
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

    /**
     * Handle action Baz in the provided background thread with the provided
     * parameters.
     */
    private void handleLaunchPlugin(Intent intent) {
        Intent dialogIntent = new Intent(this, TagEditActivity.class);
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        dialogIntent.putExtras(intent);
        startActivity(dialogIntent);
    }
}
