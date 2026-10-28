#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <signal.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "TerminalPTY"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_com_m5dev_vportable_terminal_TerminalBridge_nativeStartPty(
        JNIEnv *env,
        jobject thiz,
        jstring rootfsPath,
        jstring nativeDir,
        jint cols,
        jint rows) {

    const char *c_rootfs = (*env)->GetStringUTFChars(env, rootfsPath, NULL);
    const char *c_native_dir = (*env)->GetStringUTFChars(env, nativeDir, NULL);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);

    int master_fd = -1;
    pid_t pid = forkpty(&master_fd, NULL, NULL, &ws);

    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, rootfsPath, c_rootfs);
        (*env)->ReleaseStringUTFChars(env, nativeDir, c_native_dir);
        return -1;
    }

    if (pid == 0) {
        // Child Process: setup environment & execute VFS supervisor /bin/sh -i
        if (c_rootfs && strlen(c_rootfs) > 0) {
            chdir(c_rootfs);
        }

        setenv("TERM", "xterm-256color", 1);
        setenv("HOME", "/root", 1);
        setenv("PATH", "/bin:/sbin:/usr/bin:/usr/sbin:/system/bin", 1);
        setenv("PROOT_NO_SECCOMP", "1", 1);

        char supervisor_path[1024];
        snprintf(supervisor_path, sizeof(supervisor_path), "%s/libprobe_ptrace.so", c_native_dir ? c_native_dir : "");

        char *supervisor_args[] = {
            supervisor_path,
            (char *) c_rootfs,
            (char *) c_native_dir,
            "/bin/sh",
            "-i",
            NULL
        };

        if (access(supervisor_path, R_OK | X_OK) == 0) {
            LOGI("Child executing supervisor: %s %s %s /bin/sh -i", supervisor_path, c_rootfs, c_native_dir);
            execv(supervisor_path, supervisor_args);
        }

        char busybox_path[1024];
        snprintf(busybox_path, sizeof(busybox_path), "%s/libbusybox.so", c_native_dir ? c_native_dir : "");
        char *sh_args[] = { "sh", "-i", NULL };

        if (access(busybox_path, R_OK | X_OK) == 0) {
            LOGI("Child executing busybox interactive shell: %s sh -i", busybox_path);
            execv(busybox_path, sh_args);
        }

        // Fallback to system shell
        LOGI("Child executing system shell: /system/bin/sh -i");
        execv("/system/bin/sh", sh_args);

        LOGE("exec failed: %s", strerror(errno));
        _exit(127);
    }

    // Parent Process
    (*env)->ReleaseStringUTFChars(env, rootfsPath, c_rootfs);
    (*env)->ReleaseStringUTFChars(env, nativeDir, c_native_dir);

    // Set master_fd to non-blocking
    int flags = fcntl(master_fd, F_GETFL, 0);
    if (flags != -1) {
        fcntl(master_fd, F_SETFL, flags | O_NONBLOCK);
    }

    LOGI("Pty master_fd initialized: %d for child PID: %d", master_fd, pid);
    return master_fd;
}

JNIEXPORT jint JNICALL
Java_com_m5dev_vportable_terminal_TerminalBridge_nativeRead(
        JNIEnv *env,
        jobject thiz,
        jint masterFd,
        jbyteArray buffer) {

    if (masterFd < 0 || buffer == NULL) return -1;

    jsize buf_len = (*env)->GetArrayLength(env, buffer);
    if (buf_len <= 0) return 0;

    char *c_buf = (char *) malloc(buf_len);
    if (!c_buf) return -1;

    ssize_t bytes_read = read(masterFd, c_buf, buf_len);

    if (bytes_read < 0) {
        free(c_buf);
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return 0; // No data available right now (non-blocking)
        }
        return -1; // Genuine error
    }

    if (bytes_read == 0) {
        free(c_buf);
        return -1; // EOF
    }

    (*env)->SetByteArrayRegion(env, buffer, 0, (jsize) bytes_read, (jbyte *) c_buf);
    free(c_buf);

    return (jint) bytes_read;
}

JNIEXPORT jint JNICALL
Java_com_m5dev_vportable_terminal_TerminalBridge_nativeWrite(
        JNIEnv *env,
        jobject thiz,
        jint masterFd,
        jbyteArray data,
        jint len) {

    if (masterFd < 0 || data == NULL || len <= 0) return -1;

    jsize array_len = (*env)->GetArrayLength(env, data);
    if (len > array_len) len = array_len;

    jbyte *c_data = (*env)->GetByteArrayElements(env, data, NULL);
    if (!c_data) return -1;

    ssize_t bytes_written = write(masterFd, c_data, len);

    (*env)->ReleaseByteArrayElements(env, data, c_data, JNI_ABORT);

    return (jint) bytes_written;
}

JNIEXPORT jint JNICALL
Java_com_m5dev_vportable_terminal_TerminalBridge_nativeResize(
        JNIEnv *env,
        jobject thiz,
        jint masterFd,
        jint cols,
        jint rows) {

    if (masterFd < 0) return -1;

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);

    return ioctl(masterFd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_m5dev_vportable_terminal_TerminalBridge_nativeClose(
        JNIEnv *env,
        jobject thiz,
        jint masterFd) {

    if (masterFd >= 0) {
        close(masterFd);
        return 0;
    }
    return -1;
}
