/* Synthetic loopback-only proof: a socket opened before exec bypasses a
 * socket/connect-only filter. No external service or user data involved. */
#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/wait.h>
#include <netinet/in.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
int main(int argc, char **argv) {
    if (argc == 3 && !strcmp(argv[1], "child")) {
        int fd = atoi(argv[2]);
        return write(fd,"synthetic",9)==9 ? 0 : 1;
    }
    if (argc != 2) return 2;
    int server=socket(AF_INET,SOCK_DGRAM,0), client=socket(AF_INET,SOCK_DGRAM,0);
    if(server<0 || client<0) return 3;
    struct sockaddr_in addr={.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    socklen_t n=sizeof(addr);
    if(bind(server,(struct sockaddr*)&addr,n) || getsockname(server,(struct sockaddr*)&addr,&n) || connect(client,(struct sockaddr*)&addr,n)) return 4;
    struct timeval timeout={.tv_sec=5};
    if(setsockopt(server,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout))) return 5;
    char number[20]; snprintf(number,sizeof(number),"%d",client);
    pid_t p=fork(); if(p<0) return 6;
    if(!p) { close(server); execl("/system/bin/linker64","/system/bin/linker64",argv[1],"/system/bin/linker64",argv[0],"child",number,(char*)NULL); _exit(7); }
    close(client);
    char data[32]; int got=recv(server,data,sizeof(data),0), status;
    if(waitpid(p,&status,0)<0) return 8;
    close(server);
    if(got==9 && !memcmp(data,"synthetic",9) && WIFEXITED(status) && WEXITSTATUS(status)==0) {
        puts("FAIL: guarded child sent synthetic UDP through inherited connected FD"); return 1;
    }
    if (got < 0 && WIFEXITED(status) && WEXITSTATUS(status)==1) { puts("PASS: inherited socket cannot send after guard"); return 0; }
    fprintf(stderr,"unexpected: recv=%d status=%d\n",got,status); return 1;
}
