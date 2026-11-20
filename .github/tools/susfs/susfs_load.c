/* Minimal finit_module loader: ignores vermagic and modversions so a GKI 6.1
 * SUSFS guard LKM can be tried on a 6.1 vendor kernel. */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef MODULE_INIT_IGNORE_MODVERSIONS
#define MODULE_INIT_IGNORE_MODVERSIONS 1
#endif
#ifndef MODULE_INIT_IGNORE_VERMAGIC
#define MODULE_INIT_IGNORE_VERMAGIC 2
#endif

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <module.ko>\n", argv[0]);
        return 2;
    }
    int fd = open(argv[1], O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "open %s: %s\n", argv[1], strerror(errno));
        return 1;
    }
    long rc = syscall(__NR_finit_module, fd, "", MODULE_INIT_IGNORE_MODVERSIONS | MODULE_INIT_IGNORE_VERMAGIC);
    if (rc < 0) {
        fprintf(stderr, "finit_module: %s\n", strerror(errno));
        close(fd);
        return 1;
    }
    close(fd);
    printf("loaded %s\n", argv[1]);
    return 0;
}
