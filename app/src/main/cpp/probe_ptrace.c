#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <string.h>
#include <errno.h>

int main() {
    pid_t child = fork();
    if (child == 0) {
        if (ptrace(PTRACE_TRACEME, 0, NULL, NULL) < 0) {
            fprintf(stderr, "PTRACE_TRACEME_FAIL errno=%d (%s)\n", errno, strerror(errno));
            exit(1);
        }
        execl("/system/bin/true", "true", NULL);
        exit(0);
    } else if (child > 0) {
        int status;
        waitpid(child, &status, 0);
        if (WIFSTOPPED(status)) {
            printf("GATE_1_RESULT: PTRACE_TRACEME_SUCCESS\n");
            ptrace(PTRACE_CONT, child, NULL, NULL);
            waitpid(child, &status, 0);
            return 0;
        } else {
            fprintf(stderr, "CHILD_NOT_STOPPED status=%d\n", status);
            return 1;
        }
    } else {
        fprintf(stderr, "FORK_FAILED errno=%d (%s)\n", errno, strerror(errno));
        return 1;
    }
}
