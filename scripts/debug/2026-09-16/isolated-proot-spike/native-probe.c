#include <stdio.h>
#include <errno.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <unistd.h>
int main(void) {
    printf("NATIVE_EXEC_OK uid=%d\n",getuid()); fflush(stdout);
    pid_t child=fork(); if(child<0){perror("fork");return 2;}
    if(!child){long r=ptrace(PTRACE_TRACEME,0,0,0);printf("TRACEME=%ld errno=%d\n",r,errno);fflush(stdout);_exit(r?3:0);}
    int s;if(waitpid(child,&s,0)<0)return 4;
    return WIFEXITED(s)?WEXITSTATUS(s):5;
}
