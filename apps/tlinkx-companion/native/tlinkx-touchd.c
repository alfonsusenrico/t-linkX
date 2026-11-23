#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/netlink.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define MAX_SLOTS 10
#define AXIS_MAX 65535
#define LOG_PATH "/data/local/tmp/tlinkx_touchd.log"
#define CFG_PATH "/data/local/tmp/tlinkx_touch.cfg"
#define PID_PATH "/data/local/tmp/tlinkx_touchd.pid"
#define SAVED_BL_PATH "/data/local/tmp/tlinkx_saved_bl"
#define BACKLIGHT_PATH "/sys/class/backlight/panel0-backlight/brightness"

#define VIRT_VENDOR  0x1d6b
#define VIRT_PRODUCT 0x7101
#define VIRT_VERSION 0x0100

static volatile sig_atomic_t g_running = 1;
static FILE *g_log = NULL;

typedef struct {
    int rot_deg;
    int x_min;
    int x_max;
    int y_min;
    int y_max;
    int auto_trigger;
    char wm_size[32];
    int wm_density;
} TouchCfg;

static TouchCfg g_cfg = { 270, 6946, 58561, 0, 65535, 1, "1080x1844", 280 };
static time_t g_cfg_mtime = 0;

static bool g_car_session_active = false;
static int64_t g_disconnect_pending_ms = 0;

typedef struct {
    int tracking_id;
    int raw_x;
    int raw_y;
    int pressure;
    bool active;
    bool dirty;
    bool down_edge;
    bool up_edge;
} SlotState;

static void handle_sig(int sig) {
    (void)sig;
    g_running = 0;
}

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000LL + (ts.tv_nsec / 1000000LL);
}

static void log_msg(const char *fmt, ...) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm_now;
    localtime_r(&ts.tv_sec, &tm_now);

    char tbuf[32];
    snprintf(tbuf, sizeof(tbuf), "%02d:%02d:%02d.%03ld",
             tm_now.tm_hour, tm_now.tm_min, tm_now.tm_sec, ts.tv_nsec / 1000000L);

    va_list ap;
    va_start(ap, fmt);
    if (g_log) {
        fprintf(g_log, "[%s] ", tbuf);
        vfprintf(g_log, fmt, ap);
        fprintf(g_log, "\n");
        fflush(g_log);
    }
    va_end(ap);

    va_start(ap, fmt);
    fprintf(stderr, "[%s] ", tbuf);
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
}

static void run_shell_async(const char *cmd) {
    pid_t pid = fork();
    if (pid == 0) {
        /* Double-fork so child is reparented to init and never becomes a zombie */
        pid_t pid2 = fork();
        if (pid2 == 0) {
            execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
            _exit(127);
        }
        _exit(0);
    } else if (pid > 0) {
        waitpid(pid, NULL, 0);
    }
}

static int clamp_axis(int v) {
    if (v < 0) return 0;
    if (v > AXIS_MAX) return AXIS_MAX;
    return v;
}

static void wake_phone_backlight_if_blacked_out(void) {
    struct stat st;
    if (stat(SAVED_BL_PATH, &st) != 0) {
        return;
    }
    int restore_val = 400;
    FILE *sf = fopen(SAVED_BL_PATH, "r");
    if (sf) {
        int v = 0;
        if (fscanf(sf, "%d", &v) == 1 && v > 20) {
            restore_val = v;
        }
        fclose(sf);
    }
    FILE *bf = fopen(BACKLIGHT_PATH, "w");
    if (bf) {
        fprintf(bf, "%d\n", restore_val);
        fclose(bf);
        unlink(SAVED_BL_PATH);
        log_msg("Woke physical OLED backlight to %d", restore_val);
    }
}

static void reload_config(bool force) {
    struct stat st;
    if (stat(CFG_PATH, &st) != 0) {
        return;
    }
    if (!force && st.st_mtime == g_cfg_mtime) {
        return;
    }
    g_cfg_mtime = st.st_mtime;

    FILE *f = fopen(CFG_PATH, "r");
    if (!f) return;

    TouchCfg next = g_cfg;
    char line[128];
    while (fgets(line, sizeof(line), f)) {
        char key[32] = {0};
        char sval[64] = {0};
        if (sscanf(line, "%31[^=]=%63s", key, sval) == 2) {
            int val = atoi(sval);
            if (strcmp(key, "rot") == 0 && (val == 0 || val == 90 || val == 180 || val == 270)) {
                next.rot_deg = val;
            } else if (strcmp(key, "x_min") == 0) {
                next.x_min = clamp_axis(val);
            } else if (strcmp(key, "x_max") == 0) {
                next.x_max = clamp_axis(val);
            } else if (strcmp(key, "y_min") == 0) {
                next.y_min = clamp_axis(val);
            } else if (strcmp(key, "y_max") == 0) {
                next.y_max = clamp_axis(val);
            } else if (strcmp(key, "auto_trigger") == 0) {
                next.auto_trigger = (val != 0) ? 1 : 0;
            } else if (strcmp(key, "wm_size") == 0) {
                snprintf(next.wm_size, sizeof(next.wm_size), "%s", sval);
            } else if (strcmp(key, "wm_density") == 0 && val >= 120 && val <= 600) {
                next.wm_density = val;
            }
        }
    }
    fclose(f);

    if (next.x_max <= next.x_min) {
        next.x_min = 0;
        next.x_max = AXIS_MAX;
    }
    if (next.y_max <= next.y_min) {
        next.y_min = 0;
        next.y_max = AXIS_MAX;
    }

    if (force || memcmp(&next, &g_cfg, sizeof(TouchCfg)) != 0) {
        g_cfg = next;
        log_msg("Config loaded: rot=%d x=[%d..%d] y=[%d..%d] auto=%d wm=%s@%d",
                g_cfg.rot_deg, g_cfg.x_min, g_cfg.x_max, g_cfg.y_min, g_cfg.y_max,
                g_cfg.auto_trigger, g_cfg.wm_size, g_cfg.wm_density);
    }
}

static void on_usb_accessory_start(void) {
    reload_config(false);
    g_disconnect_pending_ms = 0;
    if (!g_cfg.auto_trigger) {
        log_msg("UEVENT ACCESSORY=START ignored (auto_trigger=0)");
        return;
    }
    if (g_car_session_active) {
        return;
    }
    g_car_session_active = true;

    char cmd[512];
    snprintf(cmd, sizeof(cmd),
             "wm size %s && wm density %d; "
             "am start-foreground-service -n com.tlinkx.companion/.CarModeService "
             "-a com.tlinkx.companion.ACTION_AUTO_ATTACH >/dev/null 2>&1",
             g_cfg.wm_size, g_cfg.wm_density);
    log_msg("UEVENT ACCESSORY=START -> instant resize (%s @ %ddpi) + wake CarModeService!",
            g_cfg.wm_size, g_cfg.wm_density);
    run_shell_async(cmd);
}

static void check_pending_disconnect(void) {
    if (g_disconnect_pending_ms == 0) return;
    if ((now_ms() - g_disconnect_pending_ms) < 1800) return;

    g_disconnect_pending_ms = 0;
    if (!g_car_session_active) return;
    g_car_session_active = false;

    wake_phone_backlight_if_blacked_out();
    log_msg("USB disconnected (confirmed >1.8s) -> resetting display & stopping CarModeService!");
    run_shell_async(
            "wm size reset; wm density reset; "
            "am startservice -n com.tlinkx.companion/.CarModeService "
            "-a com.tlinkx.companion.ACTION_AUTO_DETACH >/dev/null 2>&1");
}

static int open_uevent_socket(void) {
    int fd = socket(PF_NETLINK, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, NETLINK_KOBJECT_UEVENT);
    if (fd < 0) {
        log_msg("WARN: NETLINK_KOBJECT_UEVENT socket failed: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_nl sa;
    memset(&sa, 0, sizeof(sa));
    sa.nl_family = AF_NETLINK;
    sa.nl_pid = (uint32_t)getpid();
    sa.nl_groups = 1;
    if (bind(fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
        log_msg("WARN: NETLINK bind failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static bool process_uevent(int uevent_fd) {
    char buf[4096];
    bool hid_added = false;
    while (1) {
        ssize_t n = recv(uevent_fd, buf, sizeof(buf) - 1, 0);
        if (n <= 0) break;
        buf[n] = '\0';

        bool has_acc_start = false;
        bool is_android_usb = false;
        bool is_usb_disc = false;
        bool is_usb_conn = false;
        bool is_hid_add = false;

        ssize_t pos = 0;
        while (pos < n) {
            const char *s = buf + pos;
            size_t len = strlen(s);
            if (strcmp(s, "ACCESSORY=START") == 0) {
                has_acc_start = true;
            } else if (strcmp(s, "SUBSYSTEM=android_usb") == 0) {
                is_android_usb = true;
            } else if (strcmp(s, "USB_STATE=DISCONNECTED") == 0) {
                is_usb_disc = true;
            } else if (strcmp(s, "USB_STATE=CONNECTED") == 0 || strcmp(s, "USB_STATE=CONFIGURED") == 0) {
                is_usb_conn = true;
            } else if (strstr(s, "HID_ID=0003:FFFFFFFF:FFFFFFFF") != NULL) {
                is_hid_add = true;
            }
            pos += (ssize_t)len + 1;
        }

        if (has_acc_start) {
            on_usb_accessory_start();
        }
        if (is_android_usb && is_usb_conn) {
            g_disconnect_pending_ms = 0;
        }
        if (is_android_usb && is_usb_disc && g_car_session_active) {
            if (g_disconnect_pending_ms == 0) {
                g_disconnect_pending_ms = now_ms();
            }
        }
        if (is_hid_add) {
            hid_added = true;
        }
    }
    return hid_added;
}

static void transform_xy(int rx, int ry, int *ox, int *oy) {
    rx = clamp_axis(rx);
    ry = clamp_axis(ry);

    int tx = rx;
    int ty = ry;
    switch (g_cfg.rot_deg) {
        case 90:
            tx = AXIS_MAX - ry;
            ty = rx;
            break;
        case 180:
            tx = AXIS_MAX - rx;
            ty = AXIS_MAX - ry;
            break;
        case 270:
            tx = ry;
            ty = AXIS_MAX - rx;
            break;
        default:
            break;
    }

    *ox = g_cfg.x_min + (int)(((long long)tx * (g_cfg.x_max - g_cfg.x_min)) / AXIS_MAX);
    *oy = g_cfg.y_min + (int)(((long long)ty * (g_cfg.y_max - g_cfg.y_min)) / AXIS_MAX);
}

static bool test_bit_u8(const uint8_t *bits, int bit) {
    return (bits[bit / 8] & (1u << (bit % 8))) != 0;
}

static bool has_mt_touch_axes(int fd) {
    uint8_t evbits[(EV_MAX + 7) / 8] = {0};
    uint8_t absbits[(ABS_MAX + 7) / 8] = {0};
    if (ioctl(fd, EVIOCGBIT(0, sizeof(evbits)), evbits) < 0) return false;
    if (!test_bit_u8(evbits, EV_ABS)) return false;
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) < 0) return false;
    return test_bit_u8(absbits, ABS_MT_POSITION_X) && test_bit_u8(absbits, ABS_MT_POSITION_Y);
}

static int find_phone_touchpanel(char *out_path, size_t out_len) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return -1;

    struct dirent *ent;
    int found_fd = -1;
    while ((ent = readdir(dir)) != NULL) {
        if (strncmp(ent->d_name, "event", 5) != 0) continue;
        char path[128];
        snprintf(path, sizeof(path), "/dev/input/%s", ent->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;

        char name[128] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0) {
            if (strstr(name, "touchpanel") != NULL || strstr(name, "fts_ts") != NULL || strstr(name, "goodix") != NULL) {
                if (has_mt_touch_axes(fd)) {
                    found_fd = fd;
                    if (out_path) snprintf(out_path, out_len, "%s", path);
                    break;
                }
            }
        }
        close(fd);
    }
    closedir(dir);
    return found_fd;
}

static int find_avanza_touch(char *out_path, size_t out_len) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return -1;

    struct dirent *ent;
    int found_fd = -1;
    while ((ent = readdir(dir)) != NULL) {
        if (strncmp(ent->d_name, "event", 5) != 0) continue;
        char path[128];
        snprintf(path, sizeof(path), "/dev/input/%s", ent->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;

        struct input_id id;
        memset(&id, 0, sizeof(id));
        if (ioctl(fd, EVIOCGID, &id) == 0) {
            if (id.bustype == BUS_USB && id.vendor == 0xffff && id.product == 0xffff && has_mt_touch_axes(fd)) {
                found_fd = fd;
                if (out_path) snprintf(out_path, out_len, "%s", path);
                break;
            }
        }
        close(fd);
    }
    closedir(dir);
    return found_fd;
}

static void drain_phone_touch_for_wake(int phone_fd) {
    if (phone_fd < 0) return;
    struct input_event evs[32];
    ssize_t n = read(phone_fd, evs, sizeof(evs));
    if (n <= 0) return;
    int cnt = (int)(n / (ssize_t)sizeof(struct input_event));
    for (int i = 0; i < cnt; i++) {
        if ((evs[i].type == EV_KEY && evs[i].code == BTN_TOUCH && evs[i].value == 1)
                || (evs[i].type == EV_ABS && evs[i].code == ABS_MT_TRACKING_ID && evs[i].value >= 0)) {
            wake_phone_backlight_if_blacked_out();
            break;
        }
    }
}

static void setup_abs(int ufd, int code, int min_v, int max_v) {
    struct uinput_abs_setup abs_setup;
    memset(&abs_setup, 0, sizeof(abs_setup));
    abs_setup.code = (uint16_t)code;
    abs_setup.absinfo.minimum = min_v;
    abs_setup.absinfo.maximum = max_v;
    ioctl(ufd, UI_SET_ABSBIT, code);
    ioctl(ufd, UI_ABS_SETUP, &abs_setup);
}

static int create_uinput_touchscreen(void) {
    int ufd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (ufd < 0) {
        log_msg("ERROR: open(/dev/uinput) failed: %s", strerror(errno));
        return -1;
    }

    ioctl(ufd, UI_SET_EVBIT, EV_SYN);
    ioctl(ufd, UI_SET_EVBIT, EV_KEY);
    ioctl(ufd, UI_SET_EVBIT, EV_ABS);

    ioctl(ufd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(ufd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    setup_abs(ufd, ABS_MT_SLOT, 0, MAX_SLOTS - 1);
    setup_abs(ufd, ABS_MT_TRACKING_ID, 0, 65535);
    setup_abs(ufd, ABS_MT_POSITION_X, 0, AXIS_MAX);
    setup_abs(ufd, ABS_MT_POSITION_Y, 0, AXIS_MAX);
    setup_abs(ufd, ABS_MT_PRESSURE, 0, 255);

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = VIRT_VENDOR;
    usetup.id.product = VIRT_PRODUCT;
    usetup.id.version = VIRT_VERSION;
    snprintf(usetup.name, UINPUT_MAX_NAME_SIZE, "T-LinkX Avanza Touchscreen");

    if (ioctl(ufd, UI_DEV_SETUP, &usetup) < 0 || ioctl(ufd, UI_DEV_CREATE) < 0) {
        log_msg("ERROR: uinput setup/create failed: %s", strerror(errno));
        close(ufd);
        return -1;
    }

    log_msg("Created /dev/uinput direct touchscreen (vendor=0x%04x product=0x%04x INPUT_PROP_DIRECT)",
            VIRT_VENDOR, VIRT_PRODUCT);
    return ufd;
}

static void emit_ev(int ufd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    ssize_t wr = write(ufd, &ev, sizeof(ev));
    (void)wr;
}

static void flush_frame(int ufd, SlotState slots[MAX_SLOTS], bool *btn_touch_down, int *log_budget) {
    int active_count = 0;
    bool emitted_any = false;

    for (int i = 0; i < MAX_SLOTS; i++) {
        if (slots[i].active) active_count++;
        if (!slots[i].dirty) continue;

        emit_ev(ufd, EV_ABS, ABS_MT_SLOT, i);
        if (slots[i].up_edge) {
            emit_ev(ufd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            if (*log_budget > 0) {
                log_msg("TOUCH UP   slot=%d", i);
                (*log_budget)--;
            }
        } else if (slots[i].active) {
            if (slots[i].down_edge) {
                emit_ev(ufd, EV_ABS, ABS_MT_TRACKING_ID, slots[i].tracking_id);
            }
            int ox = 0, oy = 0;
            transform_xy(slots[i].raw_x, slots[i].raw_y, &ox, &oy);
            emit_ev(ufd, EV_ABS, ABS_MT_POSITION_X, ox);
            emit_ev(ufd, EV_ABS, ABS_MT_POSITION_Y, oy);
            emit_ev(ufd, EV_ABS, ABS_MT_PRESSURE, slots[i].pressure > 0 ? slots[i].pressure : 128);
            if (slots[i].down_edge && *log_budget > 0) {
                log_msg("TOUCH DOWN slot=%d raw=(%d,%d) -> out=(%d,%d) rot=%d",
                        i, slots[i].raw_x, slots[i].raw_y, ox, oy, g_cfg.rot_deg);
                (*log_budget)--;
            }
        }
        slots[i].dirty = false;
        slots[i].down_edge = false;
        slots[i].up_edge = false;
        emitted_any = true;
    }

    bool should_down = (active_count > 0);
    if (should_down != *btn_touch_down) {
        emit_ev(ufd, EV_KEY, BTN_TOUCH, should_down ? 1 : 0);
        *btn_touch_down = should_down;
        emitted_any = true;
    }

    if (emitted_any) {
        emit_ev(ufd, EV_SYN, SYN_REPORT, 0);
    }
}

static void release_all_slots(int ufd, SlotState slots[MAX_SLOTS], bool *btn_touch_down) {
    bool had_active = false;
    for (int i = 0; i < MAX_SLOTS; i++) {
        if (slots[i].active) {
            emit_ev(ufd, EV_ABS, ABS_MT_SLOT, i);
            emit_ev(ufd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            slots[i].active = false;
            slots[i].tracking_id = -1;
            had_active = true;
        }
    }
    if (*btn_touch_down || had_active) {
        emit_ev(ufd, EV_KEY, BTN_TOUCH, 0);
        *btn_touch_down = false;
        emit_ev(ufd, EV_SYN, SYN_REPORT, 0);
    }
}

static void bridge_session(int touch_fd, const char *touch_path, int phone_fd, int uevent_fd) {
    if (ioctl(touch_fd, EVIOCGRAB, 1) < 0) {
        log_msg("ERROR: EVIOCGRAB failed on %s: %s", touch_path, strerror(errno));
        return;
    }
    log_msg("Grabbed Avanza touch node %s (EVIOCGRAB=1 -> TouchpadInputMapper silenced)", touch_path);

    int ufd = create_uinput_touchscreen();
    if (ufd < 0) {
        ioctl(touch_fd, EVIOCGRAB, 0);
        return;
    }

    SlotState slots[MAX_SLOTS];
    memset(slots, 0, sizeof(slots));
    for (int i = 0; i < MAX_SLOTS; i++) slots[i].tracking_id = -1;

    int cur_slot = 0;
    bool btn_touch_down = false;
    int log_budget = 60;

    while (g_running) {
        reload_config(false);
        check_pending_disconnect();

        struct pollfd pfds[3];
        int nfds = 0;
        int idx_touch = nfds++;
        pfds[idx_touch].fd = touch_fd;
        pfds[idx_touch].events = POLLIN;

        int idx_phone = -1;
        if (phone_fd >= 0) {
            idx_phone = nfds++;
            pfds[idx_phone].fd = phone_fd;
            pfds[idx_phone].events = POLLIN;
        }

        int idx_uevent = -1;
        if (uevent_fd >= 0) {
            idx_uevent = nfds++;
            pfds[idx_uevent].fd = uevent_fd;
            pfds[idx_uevent].events = POLLIN;
        }

        int rc = poll(pfds, (nfds_t)nfds, 400);
        if (rc < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (rc == 0) continue;

        if (pfds[idx_touch].revents & (POLLHUP | POLLERR | POLLNVAL)) {
            log_msg("Avanza touch node %s disconnected (revents=0x%x)", touch_path, pfds[idx_touch].revents);
            break;
        }

        if (pfds[idx_touch].revents & POLLIN) {
            struct input_event evs[64];
            ssize_t n = read(touch_fd, evs, sizeof(evs));
            if (n < 0) {
                if (errno == EAGAIN || errno == EINTR) continue;
                break;
            }
            if (n == 0) break;

            int count = (int)(n / (ssize_t)sizeof(struct input_event));
            for (int i = 0; i < count; i++) {
                struct input_event *e = &evs[i];
                if (e->type == EV_ABS) {
                    if (e->code == ABS_MT_SLOT) {
                        if (e->value >= 0 && e->value < MAX_SLOTS) cur_slot = e->value;
                    } else if (e->code == ABS_MT_TRACKING_ID) {
                        SlotState *s = &slots[cur_slot];
                        if (e->value >= 0) {
                            if (!s->active) s->down_edge = true;
                            s->active = true;
                            s->tracking_id = e->value;
                            s->up_edge = false;
                            s->dirty = true;
                        } else if (s->active) {
                            s->active = false;
                            s->tracking_id = -1;
                            s->up_edge = true;
                            s->dirty = true;
                        }
                    } else if (e->code == ABS_MT_POSITION_X) {
                        slots[cur_slot].raw_x = e->value;
                        slots[cur_slot].dirty = true;
                    } else if (e->code == ABS_MT_POSITION_Y) {
                        slots[cur_slot].raw_y = e->value;
                        slots[cur_slot].dirty = true;
                    } else if (e->code == ABS_MT_PRESSURE) {
                        slots[cur_slot].pressure = e->value;
                        slots[cur_slot].dirty = true;
                    }
                } else if (e->type == EV_SYN && e->code == SYN_REPORT) {
                    flush_frame(ufd, slots, &btn_touch_down, &log_budget);
                }
            }
        }

        if (idx_phone >= 0 && (pfds[idx_phone].revents & POLLIN)) {
            drain_phone_touch_for_wake(phone_fd);
        }
        if (idx_uevent >= 0 && (pfds[idx_uevent].revents & POLLIN)) {
            process_uevent(uevent_fd);
        }
    }

    release_all_slots(ufd, slots, &btn_touch_down);
    ioctl(ufd, UI_DEV_DESTROY);
    close(ufd);
    ioctl(touch_fd, EVIOCGRAB, 0);
    log_msg("Bridge session ended and virtual touchscreen destroyed.");
}

int main(void) {
    signal(SIGINT, handle_sig);
    signal(SIGTERM, handle_sig);

    g_log = fopen(LOG_PATH, "w");
    log_msg("tlinkx-touchd v2.4.0 starting (pid=%d, zero-touch netlink mode)", getpid());

    FILE *pf = fopen(PID_PATH, "w");
    if (pf) {
        fprintf(pf, "%d\n", getpid());
        fclose(pf);
    }

    int uevent_fd = open_uevent_socket();
    char phone_path[128] = {0};
    int phone_fd = find_phone_touchpanel(phone_path, sizeof(phone_path));
    if (phone_fd >= 0) {
        log_msg("Watching phone touchpanel %s for tap-to-wake OLED backlight", phone_path);
    }
    if (uevent_fd >= 0) {
        log_msg("Listening on NETLINK_KOBJECT_UEVENT (0%% CPU sleep until Avanza USB plug-in)");
    }

    reload_config(true);

    while (g_running) {
        reload_config(false);
        check_pending_disconnect();

        char path[128] = {0};
        int touch_fd = find_avanza_touch(path, sizeof(path));
        if (touch_fd >= 0) {
            bridge_session(touch_fd, path, phone_fd, uevent_fd);
            close(touch_fd);
            continue;
        }

        struct pollfd pfds[2];
        int nfds = 0;
        int idx_uevent = -1;
        int idx_phone = -1;
        if (uevent_fd >= 0) {
            idx_uevent = nfds++;
            pfds[idx_uevent].fd = uevent_fd;
            pfds[idx_uevent].events = POLLIN;
        }
        if (phone_fd >= 0) {
            idx_phone = nfds++;
            pfds[idx_phone].fd = phone_fd;
            pfds[idx_phone].events = POLLIN;
        }

        int timeout = (g_disconnect_pending_ms > 0) ? 300 : 1500;
        if (nfds > 0) {
            int rc = poll(pfds, (nfds_t)nfds, timeout);
            if (rc > 0) {
                if (idx_uevent >= 0 && (pfds[idx_uevent].revents & POLLIN)) {
                    process_uevent(uevent_fd);
                }
                if (idx_phone >= 0 && (pfds[idx_phone].revents & POLLIN)) {
                    drain_phone_touch_for_wake(phone_fd);
                }
            }
        } else {
            usleep(400000);
        }
    }

    if (uevent_fd >= 0) close(uevent_fd);
    if (phone_fd >= 0) close(phone_fd);
    unlink(PID_PATH);
    log_msg("tlinkx-touchd stopped cleanly.");
    if (g_log) fclose(g_log);
    return 0;
}
