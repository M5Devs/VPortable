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
#include <elf.h>
#include <signal.h>

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

#ifndef PTRACE_GETREGSET
#define PTRACE_GETREGSET 0x4204
#endif

#ifndef PTRACE_SETOPTIONS
#define PTRACE_SETOPTIONS 0x4200
#endif

#ifndef PTRACE_O_TRACESYSGOOD
#define PTRACE_O_TRACESYSGOOD 0x00000001
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

#define FAKE_PATH "/system/vportable_fake_entry.txt"

static int get_syscall_and_path_ptr(pid_t child, long *out_syscall_nr, uintptr_t *out_path_addr) {
#if defined(__x86_64__)
    struct user_regs_struct regs;
    struct iovec iov = {
        .iov_base = &regs,
        .iov_len = sizeof(regs)
    };
    if (ptrace(PTRACE_GETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov) < 0) {
        return -1;
    }
    *out_syscall_nr = (long)regs.orig_rax;
    *out_path_addr = (uintptr_t)regs.rsi;
    return 0;
#elif defined(__aarch64__)
    struct user_pt_regs regs;
    struct iovec iov = {
        .iov_base = &regs,
        .iov_len = sizeof(regs)
    };
    if (ptrace(PTRACE_GETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov) < 0) {
        return -1;
    }
    *out_syscall_nr = (long)regs.regs[8];
    *out_path_addr = (uintptr_t)regs.regs[1];
    return 0;
#elif defined(__arm__)
    struct user_regs regs;
    struct iovec iov = {
        .iov_base = &regs,
        .iov_len = sizeof(regs)
    };
    if (ptrace(PTRACE_GETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov) < 0) {
        return -1;
    }
    *out_syscall_nr = (long)regs.uregs[7];
    *out_path_addr = (uintptr_t)regs.uregs[1];
    return 0;
#elif defined(__i386__)
    struct user_regs_struct regs;
    struct iovec iov = {
        .iov_base = &regs,
        .iov_len = sizeof(regs)
    };
    if (ptrace(PTRACE_GETREGSET, child, (void*)(uintptr_t)NT_PRSTATUS, &iov) < 0) {
        return -1;
    }
    *out_syscall_nr = (long)regs.orig_eax;
    *out_path_addr = (uintptr_t)regs.ecx;
    return 0;
#else
#error "Unsupported architecture"
#endif
}

int main(int argc, char *argv[]) {
    if (argc < 2 || argv[1] == NULL || argv[1][0] == '\0') {
        fprintf(stderr, "USAGE: %s <target_file_path>\n", argv[0]);
        return 1;
    }

    const char *real_path = argv[1];
    if (access(real_path, R_OK) != 0) {
        fprintf(stderr, "SUPERVISOR_ERROR: Target file '%s' is not accessible: errno=%d (%s)\n",
                real_path, errno, strerror(errno));
        return 1;
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
        // Supervisor (Parent) Process
        int status;
        if (waitpid(child, &status, 0) < 0) {
            perror("waitpid initial");
            return 1;
        }

        if (!WIFSTOPPED(status)) {
            fprintf(stderr, "CHILD_NOT_STOPPED status=%d\n", status);
            return 1;
        }

        if (ptrace(PTRACE_SETOPTIONS, child, NULL, (void*)(uintptr_t)PTRACE_O_TRACESYSGOOD) < 0) {
            perror("ptrace SETOPTIONS");
            return 1;
        }

        int in_syscall = 0;
        int next_sig = 0;

        while (1) {
            if (ptrace(PTRACE_SYSCALL, child, NULL, (void*)(uintptr_t)next_sig) < 0) {
                if (errno == ESRCH) {
                    // Child process terminated
                    break;
                }
                perror("ptrace SYSCALL");
                return 1;
            }
            next_sig = 0;

            if (waitpid(child, &status, 0) < 0) {
                perror("waitpid loop");
                return 1;
            }

            if (WIFEXITED(status)) {
                return WEXITSTATUS(status);
            }
            if (WIFSIGNALED(status)) {
                return 1;
            }

            if (WIFSTOPPED(status)) {
                int sig = WSTOPSIG(status);
                if (sig == (SIGTRAP | 0x80)) {
                    if (!in_syscall) {
                        in_syscall = 1;
                        long syscall_nr = -1;
                        uintptr_t path_addr = 0;
                        if (get_syscall_and_path_ptr(child, &syscall_nr, &path_addr) == 0) {
                            if (syscall_nr == __NR_openat && path_addr != 0) {
                                char child_path[512] = {0};
                                struct iovec local_iov = {
                                    .iov_base = child_path,
                                    .iov_len = sizeof(child_path) - 1
                                };
                                struct iovec remote_iov = {
                                    .iov_base = (void*)path_addr,
                                    .iov_len = sizeof(child_path) - 1
                                };

                                ssize_t nread = process_vm_readv(child, &local_iov, 1, &remote_iov, 1, 0);
                                if (nread > 0) {
                                    child_path[nread] = '\0';
                                    if (strcmp(child_path, FAKE_PATH) == 0) {
                                        size_t real_path_len = strlen(real_path) + 1;
                                        struct iovec w_local = {
                                            .iov_base = (void*)real_path,
                                            .iov_len = real_path_len
                                        };
                                        struct iovec w_remote = {
                                            .iov_base = (void*)path_addr,
                                            .iov_len = real_path_len
                                        };

                                        ssize_t nwritten = process_vm_writev(child, &w_local, 1, &w_remote, 1, 0);
                                        if (nwritten == (ssize_t)real_path_len) {
                                            printf("SUPERVISOR: Intercepted %s -> Rewrote to %s\n", FAKE_PATH, real_path);
                                            fflush(stdout);
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        in_syscall = 0;
                    }
                } else if (sig != SIGTRAP && sig != SIGSTOP) {
                    next_sig = sig;
                }
            }
        }
        return 0;
    }
}
