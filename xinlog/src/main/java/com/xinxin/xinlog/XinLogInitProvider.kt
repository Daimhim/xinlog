package com.xinxin.xinlog

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** 通过 manifest 合并自动注册的 Provider；其 onCreate 在宿主 app 启动早期触发，实现"零代码自动初始化"。 */
class XinLogInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { XinLog.init(it) }
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
