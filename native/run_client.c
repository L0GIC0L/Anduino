/*
 * Anduino relay client — runs inside proot in place of a compiler command:
 *     anduino-run <program> <args...>
 * It asks the app (outside proot) to run the command natively, which avoids proot's
 * per-syscall cost, and relays the command's stdout, stderr and exit code. If the
 * app can't run it natively (no server, unsupported binary), it runs the command
 * itself, i.e. under proot as before.
 *
 * The app writes "<port> <token>\n" to /opt/anduino/run.conf (inside the rootfs).
 *
 * Protocol over TCP 127.0.0.1 (all integers big-endian u32, strings length-prefixed):
 *   -> "ANR1", token, cwd, argc, argv[0..argc-1]
 *   <- frames: 'O' len data (stdout) | 'E' len data (stderr) | 'X' 4 code | 'F' 0 (fall back)
 */
#include <arpa/inet.h>
#include <errno.h>
#include <limits.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define CONF "/opt/anduino/run.conf"

static int write_all(int fd, const void *buf, size_t n) {
    const char *p = buf;
    while (n > 0) {
        ssize_t w = write(fd, p, n);
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += w;
        n -= (size_t)w;
    }
    return 0;
}

static int read_all(int fd, void *buf, size_t n) {
    char *p = buf;
    while (n > 0) {
        ssize_t r = read(fd, p, n);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return -1;
        p += r;
        n -= (size_t)r;
    }
    return 0;
}

static int send_u32(int fd, uint32_t v) {
    uint32_t be = htonl(v);
    return write_all(fd, &be, 4);
}

static int send_str(int fd, const char *s) {
    size_t n = strlen(s);
    return send_u32(fd, (uint32_t)n) || write_all(fd, s, n);
}

static int fallback(char **cmd) {
    execv(cmd[0], cmd);
    fprintf(stderr, "anduino-run: cannot run %s: %s\n", cmd[0], strerror(errno));
    return 127;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: anduino-run <program> [args...]\n");
        return 2;
    }
    char **cmd = argv + 1;

    const char *conf_path = getenv("ANDUINO_RUN_CONF"); /* tests run without a rootfs */
    FILE *conf = fopen(conf_path ? conf_path : CONF, "r");
    unsigned port = 0;
    char token[128] = {0};
    if (!conf) return fallback(cmd);
    int ok = fscanf(conf, "%u %127s", &port, token) == 2;
    fclose(conf);
    if (!ok || port == 0 || port > 65535) return fallback(cmd);

    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (fd < 0 || connect(fd, (struct sockaddr *)&addr, sizeof addr) != 0) {
        if (fd >= 0) close(fd);
        return fallback(cmd);
    }

    char cwd[PATH_MAX];
    if (!getcwd(cwd, sizeof cwd)) strcpy(cwd, "/");
    int bad = write_all(fd, "ANR1", 4) || send_str(fd, token) || send_str(fd, cwd) ||
              send_u32(fd, (uint32_t)(argc - 1));
    for (int i = 1; i < argc && !bad; i++) bad = send_str(fd, argv[i]);
    if (bad) {
        close(fd);
        return fallback(cmd);
    }

    static char buf[65536];
    for (;;) {
        unsigned char type;
        uint32_t len_be;
        if (read_all(fd, &type, 1) || read_all(fd, &len_be, 4)) {
            fprintf(stderr, "anduino-run: lost connection to the app\n");
            return 1;
        }
        uint32_t len = ntohl(len_be);
        if (type == 'F') {
            close(fd);
            return fallback(cmd);
        }
        if (type == 'X') {
            uint32_t code_be = 0;
            if (len != 4 || read_all(fd, &code_be, 4)) return 1;
            close(fd);
            return (int)ntohl(code_be);
        }
        int out = type == 'E' ? 2 : 1;
        while (len > 0) {
            uint32_t chunk = len < sizeof buf ? len : (uint32_t)sizeof buf;
            if (read_all(fd, buf, chunk)) return 1;
            write_all(out, buf, chunk);
            len -= chunk;
        }
    }
}
