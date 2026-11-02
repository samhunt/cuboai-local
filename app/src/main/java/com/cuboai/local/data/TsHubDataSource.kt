@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.cuboai.local.data

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

class TsHubDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var opened = false
    private val position = longArrayOf(0L)

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val hub = TsHub.INSTANCE
        position[0] = hub.head()
        opened = true
        Log.i("TsHubDS", "open head=${position[0]} available=${hub.available()} running=${hub.running}")
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val hub = TsHub.INSTANCE
        while (true) {
            val n = hub.read(position, buffer, offset, length, 500)
            if (n > 0) {
                bytesTransferred(n)
                return n
            }
            if (!hub.running) {
                Log.i("TsHubDS", "EOF at pos=${position[0]} available=${hub.available()}")
                return C.RESULT_END_OF_INPUT
            }
        }
    }

    override fun getUri(): Uri = URI

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = TsHubDataSource()
    }

    companion object {
        val URI: Uri = Uri.parse("cuboai://live.ts")
    }
}
