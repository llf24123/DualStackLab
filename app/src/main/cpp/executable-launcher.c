#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>

/* execv returns only when the kernel did not start the target. Never retry a program exit. */
int main(int argc, char **argv) {
    if (argc < 3) { fputs("Missing executable path\n", stderr); return 126; }
    const char *original = argv[1], *staged = argv[2];
    char **arguments = calloc((size_t)argc, sizeof(char *));
    if (!arguments) return 126;
    for (int i = 3; i < argc; i++) arguments[i - 2] = argv[i];
    int original_error = 0;
    if (*original && geteuid() == 0) {
        int fd = open(original, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            struct stat attributes;
            struct statvfs mount;
            unsigned char magic[4];
            int changed = 0;
            if (fstat(fd, &attributes) == 0 && S_ISREG(attributes.st_mode)
                    && pread(fd, magic, sizeof(magic), 0) == sizeof(magic)
                    && memcmp(magic, "\177ELF", sizeof(magic)) == 0) {
                int noexec = fstatvfs(fd, &mount) == 0 && (mount.f_flag & ST_NOEXEC);
                if (!noexec) {
                    if (!(attributes.st_mode & S_IXUSR)) {
                        if (fchmod(fd, (attributes.st_mode & 07777) | S_IXUSR) == 0) changed = 1;
                    }
                    arguments[0] = (char *)original;
                    execv(original, arguments);
                    original_error = errno;
                    if (changed && fchmod(fd, attributes.st_mode & 07777) != 0)
                        fprintf(stderr, "Could not restore execute permission: %s\n", strerror(errno));
                } else original_error = EACCES;
            } else original_error = ENOEXEC;
            close(fd);
        } else original_error = errno;
        if (original_error != EACCES && original_error != EPERM && original_error != ENOENT
                && original_error != ENOTDIR && original_error != EROFS) {
            fprintf(stderr, "Cannot start %s: %s\n", original, strerror(original_error));
            free(arguments); return 126;
        }
    }
    arguments[0] = (char *)staged;
    execv(staged, arguments);
    fprintf(stderr, "Cannot start executable: %s", strerror(errno));
    if (original_error) fprintf(stderr, " (source: %s)", strerror(original_error));
    fputc('\n', stderr);
    free(arguments); return 126;
}
