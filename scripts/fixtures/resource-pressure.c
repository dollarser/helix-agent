/* Bounded Android emulator fixture. Allocates real, poorly compressible anonymous pages. */
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t stopped;
static void stop(int sig) { (void)sig; stopped = 1; }
int main(int argc, char **argv) {
    if (argc != 3) return 2;
    int max_mib = atoi(argv[1]), seconds = atoi(argv[2]);
    if (max_mib < 16 || max_mib > 1536 || seconds < 1 || seconds > 60) return 2;
    signal(SIGTERM, stop); signal(SIGINT, stop); signal(SIGHUP, stop);
    printf("PID %ld\n", (long)getpid()); fflush(stdout);
    void *blocks[384] = {0};
    int count = 0;
    uint32_t random = 0x6d2b79f5;
    time_t started = time(NULL);
    while (!stopped && time(NULL) - started < seconds) {
        if (count < max_mib / 4) {
            volatile uint32_t *block = malloc(4 * 1024 * 1024);
            if (!block) break;
            blocks[count++] = (void *)block;
            for (size_t i = 0; i < 1024 * 1024; ++i) {
                random ^= random << 13; random ^= random >> 17; random ^= random << 5;
                block[i] = random;
            }
            printf("ALLOCATED_MIB %d\n", count * 4); fflush(stdout);
        }
        usleep(50000);
    }
    for (int i = 0; i < count; ++i) free(blocks[i]);
    printf("RELEASED_MIB %d\n", count * 4); fflush(stdout);
    return 0;
}
