#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <elf.h>
#include <signal.h>

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

#ifndef PTRACE_GETREGSET
#define PTRACE_GETREGSET 0x4204
#endif

#ifndef PTRACE_SETREGSET
#define PTRACE_SETREGSET 0x4205
#endif

#ifndef PTRACE_SETOPTIONS
#define PTRACE_SETOPTIONS 0x4200
#endif

#ifndef PTRACE_GETEVENTMSG
#define PTRACE_GETEVENTMSG 0x4201
#endif

#ifndef PTRACE_O_TRACESYSGOOD
#define PTRACE_O_TRACESYSGOOD 0x00000001
#endif

#ifndef PTRACE_O_TRACEFORK
#define PTRACE_O_TRACEFORK 0x00000002
#endif

#ifndef PTRACE_O_TRACEVFORK
#define PTRACE_O_TRACEVFORK 0x00000004
#endif

#ifndef PTRACE_O_TRACECLONE
#define PTRACE_O_TRACECLONE 0x00000008
#endif

#ifndef PTRACE_O_TRACEEXEC
#define PTRACE_O_TRACEEXEC 0x00000010
#endif

#ifndef PTRACE_EVENT_FORK
#define PTRACE_EVENT_FORK 1
#endif

#ifndef PTRACE_EVENT_VFORK
#define PTRACE_EVENT_VFORK 2
#endif

#ifndef PTRACE_EVENT_CLONE
#define PTRACE_EVENT_CLONE 3
#endif

#ifndef PTRACE_EVENT_EXEC
#define PTRACE_EVENT_EXEC 4
#endif

#ifndef __WALL
#define __WALL 0x40000000
#endif

#ifndef AT_FDCWD
#define AT_FDCWD -100
#endif

#ifndef __NR_openat
#if defined(__x86_64__)
#define __NR_openat 257
#elif defined(__aarch64__)
#define __NR_openat 56
#elif defined(__i386__)
#define __NR_openat 295
#elif defined(__arm__)
#define __NR_openat 322
#endif
#endif

#ifndef __NR_newfstatat
#if defined(__x86_64__)
#define __NR_newfstatat 262
#elif defined(__aarch64__)
#define __NR_newfstatat 79
#elif defined(__i386__)
#define __NR_newfstatat 327
#elif defined(__arm__)
#define __NR_newfstatat 327
#endif
#endif

#ifndef __NR_execve
#if defined(__x86_64__)
#define __NR_execve 59
#elif defined(__aarch64__)
#define __NR_execve 221
#elif defined(__i386__)
#define __NR_execve 11
#elif defined(__arm__)
#define __NR_execve 11
#endif
#endif

#define FAKE_PATH "/system/vportable_fake_entry.txt"
#define MAX_TRACKED_PIDS 64

extern char **environ;

typedef struct {
    pid_t pid;
    int in_syscall;
    long last_syscall;
    int is_active;
} pid_state_t;

static pid_state_t pid_table[MAX_TRACKED_PIDS];

static pid_state_t* find_or_create_pid_state(pid_t pid) {
    for (int i = 0; i < MAX_TRACKED_PIDS; i++) {
        if (pid_table[i].is_active && pid_table[i].pid == pid) {
            return &pid_table[i];
        }
    }
    for (int i = 0; i < MAX_TRACKED_PIDS; i++) {
        if (!pid_table[i].is_active) {
            pid_table[i].pid = pid;
            pid_table[i].in_syscall = 0;
            pid_table[i].last_syscall = -1;
            pid_table[i].is_active = 1;
            return &pid_table[i];
        }
    }
    return NULL;
}

static void remove_pid_state(pid_t pid) {
    for (int i = 0; i < MAX_TRACKED_PIDS; i++) {
        if (pid_table[i].is_active && pid_table[i].pid == pid) {
            pid_table[i].is_active = 0;
            break;
        }
    }
}

static int get_active_pid_count(void) {
    int count = 0;
    for (int i = 0; i < MAX_TRACKED_PIDS; i++) {
        if (pid_table[i].is_active) {
            count++;
        }
    }
    return count;
}

typedef struct {
    const char *guest_prefix;
    char host_target[1024];
} mount_entry_t;

static mount_entry_t mount_table[] = {
    { "/bin", "" },
    { "/etc", "" },
    { "/system", "" }
};
#define NUM_MOUNT_ENTRIES (sizeof(mount_table) / sizeof(mount_table[0]))

static void init_mount_table_targets(const char *rootfs_dir) {
    for (size_t i = 0; i < NUM_MOUNT_ENTRIES; i++) {
        snprintf(mount_table[i].host_target, sizeof(mount_table[i].host_target),
                 "%s%s", rootfs_dir, mount_table[i].guest_prefix);
    }
}

static int resolve_vfs_path(const char *in_path, char *out_path, size_t out_size) {
    if (in_path == NULL || out_path == NULL) return 0;

    for (size_t i = 0; i < NUM_MOUNT_ENTRIES; i++) {
        if (mount_table[i].host_target[0] == '\0') continue;

        const char *gprefix = mount_table[i].guest_prefix;
        size_t gprefix_len = strlen(gprefix);

        if (strcmp(in_path, gprefix) == 0) {
            snprintf(out_path, out_size, "%s", mount_table[i].host_target);
            return 1;
        } else if (strncmp(in_path, gprefix, gprefix_len) == 0 && in_path[gprefix_len] == '/') {
            snprintf(out_path, out_size, "%s%s", mount_table[i].host_target, in_path + gprefix_len);
            return 1;
        }
    }
    return 0;
}

#if defined(__x86_64__)
typedef struct user_regs_struct arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).rsp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).orig_rax)
#define ARCH_RET(regs) ((long)(regs).rax)
#define ARCH_ARG1(regs) ((uintptr_t)(regs).rdi)
#define ARCH_ARG2(regs) ((uintptr_t)(regs).rsi)
#define ARCH_ARG3(regs) ((uintptr_t)(regs).rdx)

#define SET_ARCH_ARG1(regs, val) ((regs).rdi = (unsigned long long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).rsi = (unsigned long long)(val))
#define SET_ARCH_ARG3(regs, val) ((regs).rdx = (unsigned long long)(val))

#elif defined(__aarch64__)
typedef struct user_pt_regs arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).sp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).regs[8])
#define ARCH_RET(regs) ((long)(regs).regs[0])
#define ARCH_ARG1(regs) ((uintptr_t)(regs).regs[0])
#define ARCH_ARG2(regs) ((uintptr_t)(regs).regs[1])
#define ARCH_ARG3(regs) ((uintptr_t)(regs).regs[2])

#define SET_ARCH_ARG1(regs, val) ((regs).regs[0] = (__u64)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).regs[1] = (__u64)(val))
#define SET_ARCH_ARG3(regs, val) ((regs).regs[2] = (__u64)(val))

#elif defined(__arm__)
typedef struct user_regs arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).uregs[13])
#define ARCH_SYSCALL_NR(regs) ((long)(regs).uregs[7])
#define ARCH_RET(regs) ((long)(regs).uregs[0])
#define ARCH_ARG1(regs) ((uintptr_t)(regs).uregs[0])
#define ARCH_ARG2(regs) ((uintptr_t)(regs).uregs[1])
#define ARCH_ARG3(regs) ((uintptr_t)(regs).uregs[2])

#define SET_ARCH_ARG1(regs, val) ((regs).uregs[0] = (unsigned long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).uregs[1] = (unsigned long)(val))
#define SET_ARCH_ARG3(regs, val) ((regs).uregs[2] = (unsigned long)(val))

#elif defined(__i386__)
typedef struct user_regs_struct arch_regs_t;
#define ARCH_SP(regs) ((uintptr_t)(regs).esp)
#define ARCH_SYSCALL_NR(regs) ((long)(regs).orig_eax)
#define ARCH_RET(regs) ((long)(regs).eax)
#define ARCH_ARG1(regs) ((uintptr_t)(regs).ebx)
#define ARCH_ARG2(regs) ((uintptr_t)(regs).ecx)
#define ARCH_ARG3(regs) ((uintptr_t)(regs).edx)

#define SET_ARCH_ARG1(regs, val) ((regs).ebx = (unsigned long)(val))
#define SET_ARCH_ARG2(regs, val) ((regs).ecx = (unsigned long)(val))
#define SET_ARCH_ARG3(regs, val) ((regs).edx = (unsigned long)(val))

#else
#error "Unsupported architecture"
#endif

static int get_child_regs(pid_t child, arch_regs_t *regs) {
    struct iovec iov = {
        .iov_base = regs,
        .iov_len = sizeof(*regs)
    };
    return ptrace(PTRACE_GETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov);
}

static int set_child_regs(pid_t child, arch_regs_t *regs) {
    struct iovec iov = {
        .iov_base = regs,
        .iov_len = sizeof(*regs)
    };
    return ptrace(PTRACE_SETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov);
}

static uintptr_t write_to_child_scratch(pid_t child, uintptr_t sp, const void *data, size_t len) {
    uintptr_t scratch_addr = (sp - 2048) & ~15UL;
    struct iovec local_iov = { .iov_base = (void*)data, .iov_len = len };
    struct iovec remote_iov = { .iov_base = (void*)scratch_addr, .iov_len = len };
    ssize_t nwritten = process_vm_writev(child, &local_iov, 1, &remote_iov, 1, 0);
    if (nwritten != (ssize_t)len) {
        return 0;
    }
    return scratch_addr;
}

static void ensure_argv0_is_applet(pid_t child, uintptr_t argv_ptr, uintptr_t scratch_sp, const char *applet) {
    if (argv_ptr == 0 || applet == NULL) return;

    uintptr_t applet_addr = scratch_sp + 512;
    size_t applet_len = strlen(applet) + 1;
    struct iovec applet_l = { .iov_base = (void*)applet, .iov_len = applet_len };
    struct iovec applet_r = { .iov_base = (void*)applet_addr, .iov_len = applet_len };
    if (process_vm_writev(child, &applet_l, 1, &applet_r, 1, 0) == (ssize_t)applet_len) {
#if defined(__x86_64__) || defined(__aarch64__)
        uint64_t new_ptr = (uint64_t)applet_addr;
        struct iovec w_l = { .iov_base = &new_ptr, .iov_len = sizeof(new_ptr) };
        struct iovec w_r = { .iov_base = (void*)argv_ptr, .iov_len = sizeof(new_ptr) };
        process_vm_writev(child, &w_l, 1, &w_r, 1, 0);
#else
        uint32_t new_ptr = (uint32_t)applet_addr;
        struct iovec w_l = { .iov_base = &new_ptr, .iov_len = sizeof(new_ptr) };
        struct iovec w_r = { .iov_base = (void*)argv_ptr, .iov_len = sizeof(new_ptr) };
        process_vm_writev(child, &w_l, 1, &w_r, 1, 0);
#endif
    }
}

int main(int argc, char *argv[]) {
    if (argc < 2 || argv[1] == NULL || argv[1][0] == '\0') {
        fprintf(stderr, "USAGE: %s <rootfs_base_path/target_file_path> [native_lib_dir] [cmd]\n", argv[0]);
        return 1;
    }

    int is_gate2 = (argc >= 3 && argv[2] != NULL && argv[2][0] != '\0');
    const char *param1 = argv[1];
    const char *param2 = is_gate2 ? argv[2] : NULL;

    if (!is_gate2) {
        if (access(param1, R_OK) != 0) {
            fprintf(stderr, "SUPERVISOR_ERROR: Target file '%s' is not accessible: errno=%d (%s)\n",
                    param1, errno, strerror(errno));
            return 1;
        }
    }

    pid_t child = fork();
    if (child < 0) {
        fprintf(stderr, "FORK_FAILED errno=%d (%s)\n", errno, strerror(errno));
        return 1;
    }

    if (child == 0) {
        // Child Process
        if (ptrace(PTRACE_TRACEME, 0, NULL, NULL) < 0) {
            fprintf(stderr, "PTRACE_TRACEME_FAIL errno=%d (%s)\n", errno, strerror(errno));
            exit(1);
        }

        raise(SIGSTOP);

        if (!is_gate2) {
            // Gate 1.5 single openat test
            char path_buf[512] = FAKE_PATH;
            int fd = openat(AT_FDCWD, path_buf, O_RDONLY);
            if (fd >= 0) {
                char read_buf[256] = {0};
                ssize_t bytes = read(fd, read_buf, sizeof(read_buf) - 1);
                if (bytes >= 0) {
                    read_buf[bytes] = '\0';
                    printf("CHILD_READ: %s\n", read_buf);
                    printf("GATE_1_5_RESULT: VFS_REWRITE_SUCCESS\n");
                    fflush(stdout);
                } else {
                    printf("CHILD_READ_FAILED errno=%d (%s)\n", errno, strerror(errno));
                    fflush(stdout);
                }
                close(fd);
                exit(0);
            } else {
                printf("CHILD_OPEN_FAILED errno=%d (%s)\n", errno, strerror(errno));
                fflush(stdout);
                exit(1);
            }
        } else {
            // Multi-process / pipeline test
            struct stat st;
            int fstat_res = syscall(__NR_newfstatat, AT_FDCWD, "/bin/sh", &st, 0);
            printf("CHILD: fstatat(\"/bin/sh\") res=%d\n", fstat_res);
            fflush(stdout);

            int fd = syscall(__NR_openat, AT_FDCWD, "/bin/sh", O_RDONLY, 0);
            printf("CHILD: openat(\"/bin/sh\") fd=%d\n", fd);
            fflush(stdout);
            if (fd >= 0) {
                close(fd);
            }

            const char *cmd = "echo VPORTABLE_GATE_3_1_SUCCESS | cat";
            if (argc >= 4 && argv[3] != NULL && argv[3][0] != '\0') {
                cmd = argv[3];
            }

            char *exec_args[] = { "sh", "-c", (char*)cmd, NULL };
            printf("CHILD: Invoking /bin/sh -c \"%s\"...\n", cmd);
            fflush(stdout);

            execve("/bin/sh", exec_args, environ);

            int err = errno;
            printf("CHILD_EXECVE_FAILED errno=%d (%s)\n", err, strerror(err));
            fflush(stdout);
            fflush(stderr);
            exit(1);
        }
    } else {
        // Supervisor (Parent) Process
        memset(pid_table, 0, sizeof(pid_table));
        find_or_create_pid_state(child);

        if (is_gate2) {
            init_mount_table_targets(param1);
        }

        int status;
        pid_t initial_pid = waitpid(child, &status, __WALL);
        if (initial_pid < 0) {
            perror("waitpid initial");
            return 1;
        }

        if (!WIFSTOPPED(status)) {
            fprintf(stderr, "CHILD_NOT_STOPPED status=%d\n", status);
            return 1;
        }

        if (ptrace(PTRACE_SETOPTIONS, child, NULL,
                   (void*)(uintptr_t)(PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEFORK |
                                      PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE |
                                      PTRACE_O_TRACEEXEC)) < 0) {
            perror("ptrace SETOPTIONS");
            return 1;
        }

        while (get_active_pid_count() > 0) {
            pid_t current_pid = waitpid(-1, &status, __WALL);
            if (current_pid < 0) {
                if (errno == ECHILD) {
                    break;
                }
                perror("waitpid loop");
                return 1;
            }

            if (WIFEXITED(status) || WIFSIGNALED(status)) {
                remove_pid_state(current_pid);
                printf("SUPERVISOR: PID %d exited (status=%d)\n", current_pid,
                       WIFEXITED(status) ? WEXITSTATUS(status) : WTERMSIG(status));
                fflush(stdout);
                continue;
            }

            if (WIFSTOPPED(status)) {
                int sig = WSTOPSIG(status);
                int event = (status >> 16);
                int next_sig = 0;

                pid_state_t *st = find_or_create_pid_state(current_pid);
                if (!st) {
                    ptrace(PTRACE_SYSCALL, current_pid, NULL, NULL);
                    continue;
                }

                if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_CLONE) {
                    unsigned long new_pid_val = 0;
                    if (ptrace(PTRACE_GETEVENTMSG, current_pid, NULL, &new_pid_val) == 0 && new_pid_val > 0) {
                        pid_t new_pid = (pid_t)new_pid_val;
                        pid_state_t *new_st = find_or_create_pid_state(new_pid);
                        if (new_st) {
                            ptrace(PTRACE_SETOPTIONS, new_pid, NULL,
                                   (void*)(uintptr_t)(PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEFORK |
                                                      PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE |
                                                      PTRACE_O_TRACEEXEC));
                            printf("SUPERVISOR: Tracked new child PID %d (parent PID %d)\n", new_pid, current_pid);
                            fflush(stdout);
                        }
                    }
                    ptrace(PTRACE_SYSCALL, current_pid, NULL, NULL);
                    continue;
                }

                if (event == PTRACE_EVENT_EXEC) {
                    st->in_syscall = 0;
                    printf("SUPERVISOR [PID %d]: Exec stop (PTRACE_EVENT_EXEC)\n", current_pid);
                    fflush(stdout);
                    ptrace(PTRACE_SYSCALL, current_pid, NULL, NULL);
                    continue;
                }

                if (sig == (SIGTRAP | 0x80)) {
                    if (!st->in_syscall) {
                        st->in_syscall = 1;
                        arch_regs_t regs;
                        if (get_child_regs(current_pid, &regs) == 0) {
                            long syscall_nr = ARCH_SYSCALL_NR(regs);
                            st->last_syscall = syscall_nr;
                            uintptr_t sp = ARCH_SP(regs);

                            if (!is_gate2) {
                                // Gate 1.5 mode
                                if (syscall_nr == __NR_openat) {
                                    uintptr_t path_addr = ARCH_ARG2(regs);
                                    if (path_addr != 0) {
                                        char child_path[512] = {0};
                                        struct iovec local_iov = { .iov_base = child_path, .iov_len = sizeof(child_path) - 1 };
                                        struct iovec remote_iov = { .iov_base = (void*)path_addr, .iov_len = sizeof(child_path) - 1 };

                                        if (process_vm_readv(current_pid, &local_iov, 1, &remote_iov, 1, 0) > 0) {
                                            if (strcmp(child_path, FAKE_PATH) == 0) {
                                                size_t real_path_len = strlen(param1) + 1;
                                                struct iovec w_local = { .iov_base = (void*)param1, .iov_len = real_path_len };
                                                struct iovec w_remote = { .iov_base = (void*)path_addr, .iov_len = real_path_len };

                                                if (process_vm_writev(current_pid, &w_local, 1, &w_remote, 1, 0) == (ssize_t)real_path_len) {
                                                    printf("SUPERVISOR [PID %d]: Intercepted %s -> Rewrote to %s\n", current_pid, FAKE_PATH, param1);
                                                    printf("GATE_1_5_RESULT: VFS_REWRITE_SUCCESS\n");
                                                    fflush(stdout);
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Gate 2 / Gate 3.1 mode
                                if (syscall_nr == __NR_openat || syscall_nr == __NR_newfstatat) {
                                    uintptr_t path_addr = ARCH_ARG2(regs);
                                    if (path_addr != 0) {
                                        char child_path[1024] = {0};
                                        struct iovec local_iov = { .iov_base = child_path, .iov_len = sizeof(child_path) - 1 };
                                        struct iovec remote_iov = { .iov_base = (void*)path_addr, .iov_len = sizeof(child_path) - 1 };

                                        if (process_vm_readv(current_pid, &local_iov, 1, &remote_iov, 1, 0) > 0) {
                                            char rewritten_path[2048] = {0};
                                            if (resolve_vfs_path(child_path, rewritten_path, sizeof(rewritten_path))) {
                                                size_t rew_len = strlen(rewritten_path) + 1;
                                                uintptr_t scratch = write_to_child_scratch(current_pid, sp, rewritten_path, rew_len);
                                                if (scratch != 0) {
                                                    SET_ARCH_ARG2(regs, scratch);
                                                    set_child_regs(current_pid, &regs);
                                                    printf("SUPERVISOR [PID %d]: Intercepted %s path=%s -> Rewrote to %s\n",
                                                           current_pid,
                                                           (syscall_nr == __NR_openat ? "openat" : "newfstatat"),
                                                           child_path, rewritten_path);
                                                    fflush(stdout);
                                                }
                                            }
                                        }
                                    }
                                } else if (syscall_nr == __NR_execve) {
                                    uintptr_t path_addr = ARCH_ARG1(regs);
                                    if (path_addr != 0) {
                                        char child_path[1024] = {0};
                                        struct iovec local_iov = { .iov_base = child_path, .iov_len = sizeof(child_path) - 1 };
                                        struct iovec remote_iov = { .iov_base = (void*)path_addr, .iov_len = sizeof(child_path) - 1 };

                                        if (process_vm_readv(current_pid, &local_iov, 1, &remote_iov, 1, 0) > 0) {
                                            char resolved_exec_path[2048] = {0};
                                            int is_vfs_exec = 0;
                                            char applet_name[256] = {0};

                                            if (strncmp(child_path, "/bin/", 5) == 0) {
                                                snprintf(applet_name, sizeof(applet_name), "%s", child_path + 5);
                                                is_vfs_exec = 1;
                                            } else if (resolve_vfs_path(child_path, resolved_exec_path, sizeof(resolved_exec_path))) {
                                                const char *bname = strrchr(child_path, '/');
                                                if (bname) {
                                                    snprintf(applet_name, sizeof(applet_name), "%s", bname + 1);
                                                } else {
                                                    snprintf(applet_name, sizeof(applet_name), "%s", child_path);
                                                }
                                                is_vfs_exec = 1;
                                            }

                                            if (is_vfs_exec) {
                                                char busybox_path[2048] = {0};
                                                snprintf(busybox_path, sizeof(busybox_path), "%s/libbusybox.so", param2);
                                                size_t bb_len = strlen(busybox_path) + 1;

                                                uintptr_t scratch = write_to_child_scratch(current_pid, sp, busybox_path, bb_len);
                                                if (scratch != 0) {
                                                    SET_ARCH_ARG1(regs, scratch);
                                                    ensure_argv0_is_applet(current_pid, ARCH_ARG2(regs), scratch, applet_name);
                                                    set_child_regs(current_pid, &regs);
                                                    printf("SUPERVISOR [PID %d]: Intercepted execve path=%s -> Rewrote to %s (argv[0]=%s)\n",
                                                           current_pid, child_path, busybox_path, applet_name);
                                                    fflush(stdout);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        st->in_syscall = 0;
                        if (is_gate2 && st->last_syscall == __NR_execve) {
                            arch_regs_t regs;
                            if (get_child_regs(current_pid, &regs) == 0) {
                                long ret = ARCH_RET(regs);
                                if (ret < 0) {
                                    printf("SUPERVISOR [PID %d]: execve returned error=%ld (%s)\n",
                                           current_pid, -ret, strerror((int)-ret));
                                    fflush(stdout);
                                }
                            }
                        }
                    }
                } else if (sig == SIGTRAP) {
                    st->in_syscall = 0;
                } else if (sig == SIGSTOP) {
                    ptrace(PTRACE_SETOPTIONS, current_pid, NULL,
                           (void*)(uintptr_t)(PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEFORK |
                                              PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE |
                                              PTRACE_O_TRACEEXEC));
                    next_sig = 0;
                } else {
                    next_sig = sig;
                }

                ptrace(PTRACE_SYSCALL, current_pid, NULL, (void*)(uintptr_t)next_sig);
            }
        }
        return 0;
    }
}
