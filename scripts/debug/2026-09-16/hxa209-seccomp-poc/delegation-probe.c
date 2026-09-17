/* Synthetic same-UID delegation: guarded child alters an unfiltered parent's
 * flag through process_vm_writev. Only this probe's process/memory is targeted.
 * A blocked result is evidence for this device/domain only, not global safety.
 */
#define _GNU_SOURCE
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <errno.h>
#include <string.h>
static volatile int request;
int main(int argc,char **argv) {
    if(argc==4 && !strcmp(argv[1],"child")) {
        int one=1;
        struct iovec local={&one,sizeof(one)}, remote={(void*)(uintptr_t)strtoull(argv[3],NULL,16),sizeof(one)};
        ssize_t n=process_vm_writev((pid_t)atoi(argv[2]),&local,1,&remote,1,0);
        printf("process_vm_writev result=%zd errno=%d\n",n,errno);
        return n==sizeof(one)?0:1;
    }
    if(argc!=2) return 2;
    char pid[32],addr[32];
    snprintf(pid,sizeof(pid),"%d",getpid());
    snprintf(addr,sizeof(addr),"%llx",(unsigned long long)(uintptr_t)&request);
    pid_t child=fork(); if(child<0)return 3;
    if(!child){execl("/system/bin/linker64","/system/bin/linker64",argv[1],"/system/bin/linker64",argv[0],"child",pid,addr,(char*)NULL);_exit(4);}
    int status; if(waitpid(child,&status,0)<0)return 5;
    printf("synthetic unfiltered parent request=%d; child status=%d\n",request,status);
    puts(request ? "BYPASS: filtered process can alter unfiltered process memory" : "BLOCKED in this UID/SELinux/kernel context");
    return 0;
}
