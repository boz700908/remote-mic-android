/*
 * uinputd —— 以 root 身份运行的虚拟键盘守护进程。
 *
 * Android 没有 SendInput / CGEventPost 这类注入接口，`input` 命令又只能发
 * 「按下+抬起」整事件，做不出长按与真实组合键。这里直接写 /dev/uinput，
 * 由内核产生和物理键盘完全一样的按键流。
 *
 * 协议：标准输入一行一条命令，执行完回一行 ok
 *   p            心跳
 *   d <code>     按下
 *   u <code>     抬起
 *   t <code>     点按（30ms）
 *   k <c1> <c2>  组合：全部按下 40ms 后全部抬起（最多 8 个）
 *   q            退出并销毁设备
 * code 为 Linux KEY_* 码，不是 Android KeyEvent 码。
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define DEVICE_NAME "遥控麦 Virtual Remote"
#define MAX_CODES 8

static int g_fd = -1;

static int emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    return write(g_fd, &ev, sizeof(ev)) == (ssize_t) sizeof(ev) ? 0 : -1;
}

static void sync_events(void) {
    emit(EV_SYN, SYN_REPORT, 0);
}

static int create_device(void) {
    g_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_fd < 0) {
        fprintf(stderr, "open /dev/uinput: %s\n", strerror(errno));
        return -1;
    }
    if (ioctl(g_fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(g_fd, UI_SET_EVBIT, EV_SYN) < 0) {
        fprintf(stderr, "UI_SET_EVBIT: %s\n", strerror(errno));
        return -1;
    }
    for (int code = 1; code <= KEY_MAX; code++) {
        /* 个别码某些内核不接受，跳过即可 */
        ioctl(g_fd, UI_SET_KEYBIT, code);
    }

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x0f0f;
    setup.id.product = 0x00a1;
    setup.id.version = 1;
    snprintf(setup.name, sizeof(setup.name), "%s", DEVICE_NAME);

    if (ioctl(g_fd, UI_DEV_SETUP, &setup) < 0) {
        /* 4.5 之前的内核没有 UI_DEV_SETUP，用 legacy 结构体 */
        struct uinput_user_dev legacy;
        memset(&legacy, 0, sizeof(legacy));
        snprintf(legacy.name, UINPUT_MAX_NAME_SIZE, "%s", DEVICE_NAME);
        legacy.id.bustype = BUS_VIRTUAL;
        legacy.id.vendor = 0x0f0f;
        legacy.id.product = 0x00a1;
        legacy.id.version = 1;
        if (write(g_fd, &legacy, sizeof(legacy)) != (ssize_t) sizeof(legacy)) {
            fprintf(stderr, "legacy uinput setup: %s\n", strerror(errno));
            return -1;
        }
    }
    if (ioctl(g_fd, UI_DEV_CREATE) < 0) {
        fprintf(stderr, "UI_DEV_CREATE: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

static void destroy_device(void) {
    if (g_fd >= 0) {
        ioctl(g_fd, UI_DEV_DESTROY);
        close(g_fd);
        g_fd = -1;
    }
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    if (create_device() < 0) return 1;

    printf("ready\n");
    fflush(stdout);

    char *line = NULL;
    size_t cap = 0;
    ssize_t len;
    while ((len = getline(&line, &cap, stdin)) > 0) {
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) line[--len] = '\0';
        if (len == 0) continue;

        const char cmd = line[0];
        if (cmd == 'q') break;
        if (cmd == 'p') {
            printf("ok\n");
            fflush(stdout);
            continue;
        }

        int codes[MAX_CODES];
        int count = 0;
        for (char *p = line + 1; *p && count < MAX_CODES;) {
            char *end = NULL;
            long value = strtol(p, &end, 10);
            if (end == p) {
                p++;
                continue;
            }
            codes[count++] = (int) value;
            p = end;
        }
        if (count == 0) {
            printf("err\n");
            fflush(stdout);
            continue;
        }

        switch (cmd) {
            case 'd':
                emit(EV_KEY, codes[0], 1);
                sync_events();
                break;
            case 'u':
                emit(EV_KEY, codes[0], 0);
                sync_events();
                break;
            case 't':
                emit(EV_KEY, codes[0], 1);
                sync_events();
                usleep(30000);
                emit(EV_KEY, codes[0], 0);
                sync_events();
                break;
            case 'k':
                for (int i = 0; i < count; i++) emit(EV_KEY, codes[i], 1);
                sync_events();
                usleep(40000);
                for (int i = 0; i < count; i++) emit(EV_KEY, codes[i], 0);
                sync_events();
                break;
            default:
                printf("err\n");
                fflush(stdout);
                continue;
        }
        printf("ok\n");
        fflush(stdout);
    }

    free(line);
    destroy_device();
    return 0;
}
