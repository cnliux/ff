package com.fongmi.android.tv.ui.dialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 设置中的采集源管理入口：快捷操作（打开网页管理页、测速排序、全部启用/停用）。
 */
public class SiteSourceDialog {

    public static void show(android.app.Activity activity) {
        String[] items = {
                activity.getString(R.string.source_manage_open_page),
                activity.getString(R.string.source_manage_test_sort),
                activity.getString(R.string.source_manage_enable_all),
                activity.getString(R.string.source_manage_disable_all)
        };
        new MaterialAlertDialogBuilder(activity).setTitle(R.string.source_manage).setItems(items, (dialog, which) -> {
            if (which == 0) {
                ManagePageDialog.show(activity);
            } else {
                runRemoteAction(activity, which);
            }
        }).show();
    }

    private static void runRemoteAction(android.app.Activity activity, int which) {
        // 快捷操作通过本地管理接口执行，结果在网页管理页可见
        new Thread(() -> {
            try {
                String url = which == 1 ? "/manage/sources/test?auto=1" : which == 2 ? "/manage/sources/enable-all" : "/manage/sources/disable-all";
                com.github.catvod.net.OkHttp.newCall(com.github.catvod.net.OkHttp.client(30000), Server.get().getAddress(true) + url).execute().close();
            } catch (Exception ignored) {
            }
        }).start();
        Notify.show(R.string.source_manage_done);
    }
}
