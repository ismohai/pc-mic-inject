/*
 * pcmic-daemon: Receives PCM audio from PC via TCP, serves to Zygisk module.
 * Audio: 48kHz stereo 16-bit signed LE (raw PCM, no headers).
 * PID file at /data/adb/pcmic/daemon.pid for service management.
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
#include <sys/stat.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>

#define TAG "PcMic-Daemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define RING_SIZE (384 * 1024)
#define UNIX_SOCK_PATH "/dev/socket/pcmic"
#define PID_FILE "/data/adb/pcmic/daemon.pid"
#define MAX_CLIENTS 32

static unsigned char g_ring[RING_SIZE];
static int g_write_pos = 0;
static int g_available = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_running = 1;
static volatile int g_pc_connected = 0;

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
    if (len <= 0) { pthread_mutex_unlock(&g_lock); return 0; }
    int rp = (g_write_pos - g_available + RING_SIZE) % RING_SIZE;
    for (int i = 0; i < len; i++) {
        buf[i] = g_ring[rp];
        rp = (rp + 1) % RING_SIZE;
    }
    g_available -= len;
    pthread_mutex_unlock(&g_lock);
    return len;
}

/* TCP: receive raw PCM from PC */
static void *tcp_thread(void *arg) {
    int port = *(int *)arg;
    unsigned char buf[4096];
    while (g_running) {
        int sfd = socket(AF_INET, SOCK_STREAM, 0);
        if (sfd < 0) { sleep(2); continue; }
        int opt = 1;
        setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
        struct sockaddr_in addr = {0};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = INADDR_ANY;
        addr.sin_port = htons(port);
        if (bind(sfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            LOGE("bind: %s", strerror(errno));
            close(sfd); sleep(2); continue;
        }
        listen(sfd, 1);
        LOGI("TCP listening on port %d", port);
        while (g_running) {
            struct sockaddr_in ca; socklen_t cl = sizeof(ca);
            int cfd = accept(sfd, (struct sockaddr *)&ca, &cl);
            if (cfd < 0) { if (g_running) sleep(1); continue; }
            char ip[64];
            inet_ntop(AF_INET, &ca.sin_addr, ip, sizeof(ip));
            LOGI("PC connected: %s", ip);
            g_pc_connected = 1;
            /* Clear ring buffer on new connection */
            pthread_mutex_lock(&g_lock);
            g_available = 0; g_write_pos = 0;
            memset(g_ring, 0, RING_SIZE);
            pthread_mutex_unlock(&g_lock);
            while (g_running) {
                int n = recv(cfd, buf, sizeof(buf), 0);
                if (n <= 0) break;
                ring_write(buf, n);
            }
            LOGI("PC disconnected");
            g_pc_connected = 0;
            close(cfd);
        }
        close(sfd);
    }
    return NULL;
}

/* Unix socket: serve audio to Zygisk module */
static void *unix_client(void *arg) {
    int fd = *(int *)arg; free(arg);
    unsigned char buf[4096];
    while (g_running) {
        unsigned char req[4];
        if (recv(fd, req, 4, MSG_WAITALL) != 4) break;
        int wanted = req[0] | (req[1]<<8) | (req[2]<<16) | (req[3]<<24);
        if (wanted <= 0 || wanted > (int)sizeof(buf)) wanted = sizeof(buf);
        int got = ring_read(buf, wanted);
        if (got < wanted) memset(buf + got, 0, wanted - got);
        unsigned char hdr[4] = {(unsigned char)(g_pc_connected ? 1 : 0), 0, 0, 0};
        send(fd, hdr, 4, 0);
        send(fd, buf, wanted, 0);
    }
    close(fd);
    return NULL;
}

static void *unix_thread(void *arg) {
    (void)arg;
    unlink(UNIX_SOCK_PATH);
    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) { LOGE("unix socket: %s", strerror(errno)); return NULL; }
    struct sockaddr_un addr = {0};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, UNIX_SOCK_PATH, sizeof(addr.sun_path) - 1);
    if (bind(sfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOGE("unix bind: %s", strerror(errno));
        close(sfd); return NULL;
    }
    chmod(UNIX_SOCK_PATH, 0777);
    listen(sfd, MAX_CLIENTS);
    LOGI("Unix socket ready: %s", UNIX_SOCK_PATH);
    while (g_running) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) continue;
        int *p = malloc(sizeof(int)); *p = cfd;
        pthread_t t; pthread_create(&t, NULL, unix_client, p);
        pthread_detach(t);
    }
    close(sfd); unlink(UNIX_SOCK_PATH);
    return NULL;
}

static void write_pid() {
    FILE *f = fopen(PID_FILE, "w");
    if (f) { fprintf(f, "%d\n", getpid()); fclose(f); }
}

static void cleanup(int sig) {
    (void)sig;
    g_running = 0;
    unlink(PID_FILE);
    unlink(UNIX_SOCK_PATH);
}

int main(int argc, char *argv[]) {
    int port = 9876;
    if (argc > 1) port = atoi(argv[1]);
    if (port <= 0 || port > 65535) port = 9876;

    signal(SIGTERM, cleanup);
    signal(SIGINT, cleanup);
    signal(SIGPIPE, SIG_IGN);

    /* Kill old instance if PID file exists */
    FILE *pf = fopen(PID_FILE, "r");
    if (pf) {
        int old_pid = 0;
        fscanf(pf, "%d", &old_pid);
        fclose(pf);
        if (old_pid > 0 && old_pid != getpid()) {
            kill(old_pid, SIGTERM);
            usleep(500000);
        }
    }

    write_pid();
    memset(g_ring, 0, RING_SIZE);
    LOGI("Starting on port %d, PID %d", port, getpid());

    pthread_t t1, t2;
    pthread_create(&t1, NULL, tcp_thread, &port);
    pthread_create(&t2, NULL, unix_thread, NULL);

    while (g_running) sleep(5);

    LOGI("Shutting down");
    unlink(PID_FILE);
    return 0;
}
