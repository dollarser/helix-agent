/* ABI diagnostics; install checks live in filterprobe, not inferred from signals. */
#include "poc-filter.h"
#include <stdio.h>
struct incorrect_local_layout { unsigned char code, jt, jf; unsigned short k; };
_Static_assert(sizeof(struct incorrect_local_layout) == 6, "ordinary target layout, not packed");
int main(void) {
    printf("incorrect local struct=%zu; official sock_filter=%zu k.offset=%zu seccomp_data=%zu args0=%zu\n",
        sizeof(struct incorrect_local_layout), sizeof(struct sock_filter), offsetof(struct sock_filter, k),
        sizeof(struct seccomp_data), offsetof(struct seccomp_data, args[0]));
    printf("seccomp=%ld socket=%ld connect=%ld BPF_W=%x BPF_A=%x\n",
        (long)SYS_seccomp, (long)SYS_socket, (long)SYS_connect, BPF_W, BPF_A);
    for (size_t i = 0; i < POC_FILTER_LEN; i++) {
        const unsigned char *b = (const unsigned char *)&network_filter[i];
        printf("%zu:", i);
        for (size_t j = 0; j < sizeof(struct sock_filter); j++) printf(" %02x", b[j]);
        puts("");
    }
    return 0;
}
