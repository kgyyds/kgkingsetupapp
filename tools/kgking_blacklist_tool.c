#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

struct BlacklistCmd {
    unsigned int uid;
};

struct BlacklistGetCmd {
    unsigned int count;
    unsigned int uids[64];
};

#define KSU_IOCTL_BLACKLIST_ADD _IOW('K', 21, struct BlacklistCmd)
#define KSU_IOCTL_BLACKLIST_REMOVE _IOW('K', 22, struct BlacklistCmd)
#define KSU_IOCTL_BLACKLIST_GET _IOWR('K', 23, struct BlacklistGetCmd)
#define KSU_IOCTL_HIDE_KGKING _IO('K', 100)
#define KGKING_DEV "/dev/kgking"

static int open_dev() {
    int fd = open(KGKING_DEV, O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "无法打开 %s: %s\n", KGKING_DEV, strerror(errno));
    }
    return fd;
}

static int add_uid(int fd, unsigned int uid) {
    struct BlacklistCmd cmd = {.uid = uid};
    if (ioctl(fd, KSU_IOCTL_BLACKLIST_ADD, &cmd) < 0) {
        fprintf(stderr, "BLACKLIST_ADD 失败: %s\n", strerror(errno));
        return 1;
    }
    printf("已添加 UID: %u\n", uid);
    return 0;
}

static int list_uid(int fd) {
    struct BlacklistGetCmd cmd;
    memset(&cmd, 0, sizeof(cmd));
    cmd.count = 64;

    if (ioctl(fd, KSU_IOCTL_BLACKLIST_GET, &cmd) < 0) {
        fprintf(stderr, "BLACKLIST_GET 失败: %s\n", strerror(errno));
        return 1;
    }

    printf("COUNT: %u\n", cmd.count);
    for (unsigned int i = 0; i < cmd.count; ++i) {
        printf("UID: %u\n", cmd.uids[i]);
    }
    return 0;
}

static int hide_dev(int fd) {
    if (ioctl(fd, KSU_IOCTL_HIDE_KGKING) < 0) {
        fprintf(stderr, "HIDE_KGKING 失败: %s\n", strerror(errno));
        return 1;
    }
    printf("/dev/kgking 已隐藏\n");
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "用法: %s list | add <uid> | hide\n", argv[0]);
        return 1;
    }

    int fd = open_dev();
    if (fd < 0) {
        return 2;
    }

    int rc = 0;
    if (strcmp(argv[1], "list") == 0) {
        rc = list_uid(fd);
    } else if (strcmp(argv[1], "add") == 0) {
        if (argc < 3) {
            fprintf(stderr, "add 需要 uid 参数\n");
            rc = 1;
        } else {
            unsigned int uid = (unsigned int)strtoul(argv[2], NULL, 10);
            rc = add_uid(fd, uid);
        }
    } else if (strcmp(argv[1], "hide") == 0) {
        rc = hide_dev(fd);
    } else {
        fprintf(stderr, "未知命令: %s\n", argv[1]);
        rc = 1;
    }

    close(fd);
    return rc;
}
