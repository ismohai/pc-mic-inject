/*
 * pcmic-daemon: PC Audio Receiver Daemon
 *
 * Listens on TCP port for PCM audio from PC.
 * Stores audio in ring buffer.
 * Serves audio to Zygisk module via Unix domain socket.
 *
 * Audio format from PC: 48kHz, stereo, 16-bit signed LE
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>

#define TAG "PcMic-Daemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Ring buffer: 48000 * 2ch * 2bytes * 2sec = 384KB */
#define RING_SIZE (384 * 1024)
#define UNIX_SOCK_PATH "/dev/socket/pcmic"
#define MAX_CLIENTS 32

static unsigned char g_ring[RING_SIZE];
static int g_write_pos = 0;
static int g_available = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_running = 1;
static volatile int g_pc_connected = 0;

/* ---- Ring buffer operations ---- */

static void ring_write(const unsigned char *data, int len) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < len; i++) {
        g_ring[g_write_pos] = data[i];
        g_write_pos = (g_write_pos + 1) % RING_SIZE;
    }
    g_available += len;
    if (g_available > RING_SIZE) g_available = RING_SIZE;
    pthread_mutex_unlock(&g_lock);
}

static int ring_read(unsigned char *buf, int len) {
    pthread_mutex_lock(&g_lock);
    if (g_available < len) len = g_available;
    if (len <= 0) {
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    int read_pos = (g_write_pos - g_available + RING_SIZE) % RING_SIZE;
    for (int i = 0; i < len; i++) {
        buf[i] = g_ring[read_pos];
        read_pos = (read_pos + 1) % RING_SIZE;
    }
    g_available -= len;
    pthread_mutex_unlock(&g_lock);
    return len;
}

/* ---- TCP listener: receives audio from PC ---- */

static void *tcp_thread(void *arg) {
    int port = *(int *)arg;
    int server_fd, client_fd;
    struct sockaddr_in addr;
    unsigned char buf[4096];

    while (g_running) {
        server_fd = socket(AF_INET, SOCK_STREAM, 0);
        if (server_fd < 0) {
            LOGE("TCP socket error: %s", strerror(errno));
            sleep(2);
            continue;
        }

        int opt = 1;
        setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = INADDR_ANY;
        addr.sin_port = htons(port);

        if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            LOGE("TCP bind error: %s", strerror(errno));
            close(server_fd);
            sleep(2);
            continue;
        }

        listen(server_fd, 1);
        LOGI("Listening on TCP port %d", port);

        while (g_running) {
            struct sockaddr_in client_addr;
            socklen_t client_len = sizeof(client_addr);
            client_fd = accept(server_fd, (struct sockaddr *)&client_addr, &client_len);
            if (client_fd < 0) {
                if (g_running) LOGE("accept error: %s", strerror(errno));
                continue;
            }

            char ip[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &client_addr.sin_addr, ip, sizeof(ip));
            LOGI("PC connected from %s", ip);
            g_pc_connected = 1;

            /* Receive audio data */
            while (g_running) {
                int n = recv(client_fd, buf, sizeof(buf), 0);
                if (n <= 0) break;
                ring_write(buf, n);
            }

            LOGI("PC disconnected");
            g_pc_connected = 0;
            close(client_fd);
        }

        close(server_fd);
    }
    return NULL;
}

/* ---- Unix socket server: serves audio to Zygisk module ---- */

static void *unix_client_thread(void *arg) {
    int fd = *(int *)arg;
    free(arg);
    unsigned char buf[4096];
    unsigned char silence[4096];
    memset(silence, 0, sizeof(silence));

    while (g_running) {
        /* Read request: client sends 4 bytes (requested length, little-endian) */
        unsigned char req[4];
        int n = recv(fd, req, 4, MSG_WAITALL);
        if (n != 4) break;

        int requested = req[0] | (req[1] << 8) | (req[2] << 16) | (req[3] << 24);
        if (requested <= 0 || requested > (int)sizeof(buf)) requested = sizeof(buf);

        /* Read from ring buffer */
        int got = ring_read(buf, requested);

        /* If not enough data, pad with silence */
        if (got < requested) {
            memset(buf + got, 0, requested - got);
            got = requested;
        }

        /* Send response: 4 bytes status + data */
        unsigned char resp_hdr[4];
        resp_hdr[0] = g_pc_connected ? 1 : 0; /* connection status */
        resp_hdr[1] = 0;
        resp_hdr[2] = 0;
        resp_hdr[3] = 0;
        send(fd, resp_hdr, 4, 0);
        send(fd, buf, got, 0);
    }

    close(fd);
    return NULL;
}

static void *unix_thread(void *arg) {
    (void)arg;
    int server_fd;
    struct sockaddr_un addr;

    unlink(UNIX_SOCK_PATH);

    server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        LOGE("Unix socket error: %s", strerror(errno));
        return NULL;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, UNIX_SOCK_PATH, sizeof(addr.sun_path) - 1);

    if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOGE("Unix bind error: %s", strerror(errno));
        close(server_fd);
        return NULL;
    }

    /* Allow all processes to connect */
    chmod(UNIX_SOCK_PATH, 0777);
    listen(server_fd, MAX_CLIENTS);
    LOGI("Unix socket ready at %s", UNIX_SOCK_PATH);

    while (g_running) {
        int client_fd = accept(server_fd, NULL, NULL);
        if (client_fd < 0) {
            if (g_running) LOGE("Unix accept error: %s", strerror(errno));
            continue;
        }

        int *fd_ptr = malloc(sizeof(int));
        *fd_ptr = client_fd;

        pthread_t t;
        pthread_create(&t, NULL, unix_client_thread, fd_ptr);
        pthread_detach(t);
    }

    close(server_fd);
    unlink(UNIX_SOCK_PATH);
    return NULL;
}

/* ---- Signal handler ---- */

static void sig_handler(int sig) {
    (void)sig;
    g_running = 0;
}

/* ---- Main ---- */

int main(int argc, char *argv[]) {
    int port = 9876;
    if (argc > 1) port = atoi(argv[1]);
    if (port <= 0 || port > 65535) port = 9876;

    signal(SIGTERM, sig_handler);
    signal(SIGINT, sig_handler);
    signal(SIGPIPE, SIG_IGN);

    LOGI("pcmic-daemon starting, port=%d", port);

    pthread_t tcp_tid, unix_tid;
    pthread_create(&tcp_tid, NULL, tcp_thread, &port);
    pthread_create(&unix_tid, NULL, unix_thread, NULL);

    /* Wait forever */
    while (g_running) {
        sleep(10);
    }

    LOGI("pcmic-daemon shutting down");
    pthread_join(tcp_tid, NULL);
    pthread_join(unix_tid, NULL);
    return 0;
}
