package com.m5dev.vportable.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class TerminalBridge {

    companion object {
        init {
            try {
                System.loadLibrary("vportable_pty")
            } catch (e: UnsatisfiedLinkError) {
                // Ignore in JVM unit tests
            }
        }
    }

    external fun nativeStartPty(rootfsPath: String, nativeDir: String, cols: Int, rows: Int): Int
    external fun nativeRead(masterFd: Int, buffer: ByteArray): Int
    external fun nativeWrite(masterFd: Int, data: ByteArray, len: Int): Int
    external fun nativeResize(masterFd: Int, cols: Int, rows: Int): Int
    external fun nativeClose(masterFd: Int): Int

    fun readStream(masterFd: Int, bufferSize: Int = 4096): Flow<String> = flow {
        val buffer = ByteArray(bufferSize)
        while (true) {
            val bytesRead = nativeRead(masterFd, buffer)
            if (bytesRead > 0) {
                val text = String(buffer, 0, bytesRead)
                emit(text)
            } else if (bytesRead == 0) {
                // Non-blocking read, no data currently available
                delay(20)
            } else {
                // EOF or masterFd error
                break
            }
        }
    }.flowOn(Dispatchers.IO)
}
