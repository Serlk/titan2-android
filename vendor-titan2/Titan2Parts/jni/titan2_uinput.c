/*
 * Virtual mouse (uinput) for the Titan2Parts touchpad mouse modes. Loaded into system_server
 * (System.load from org.titan2.parts.VirtualMouse): system_server can open /dev/uinput
 * (uhid_device, like VirtualDeviceManager), but ioctl is not available from Java.
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/uinput.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int emit(int fd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    return write(fd, &ev, sizeof(ev)) == sizeof(ev) ? 0 : -errno;
}

JNIEXPORT jint JNICALL Java_org_titan2_parts_VirtualMouse_nativeOpen(JNIEnv *env, jclass cls,
                                                                      jstring jname) {
    (void)cls;
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;
    /* a device without one of these bits would not be classified as a mouse: fail instead */
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_REL) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0 || ioctl(fd, UI_SET_KEYBIT, BTN_LEFT) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT) < 0 || ioctl(fd, UI_SET_RELBIT, REL_X) < 0 ||
        ioctl(fd, UI_SET_RELBIT, REL_Y) < 0 || ioctl(fd, UI_SET_RELBIT, REL_WHEEL) < 0 ||
        ioctl(fd, UI_SET_RELBIT, REL_HWHEEL) < 0) {
        int err = -errno;
        close(fd);
        return err;
    }

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x7432;   /* 't2' */
    setup.id.product = 0x0001;
    const char *name = jname ? (*env)->GetStringUTFChars(env, jname, NULL) : NULL;
    strncpy(setup.name, name ? name : "Titan2 Touchpad Mouse", UINPUT_MAX_NAME_SIZE - 1);
    if (name) (*env)->ReleaseStringUTFChars(env, jname, name);
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        int err = -errno;
        close(fd);
        return err;
    }
    return fd;
}

JNIEXPORT jint JNICALL Java_org_titan2_parts_VirtualMouse_nativeMove(JNIEnv *env, jclass cls,
                                                                      jint fd, jint dx, jint dy) {
    (void)env;
    (void)cls;
    if (dx) emit(fd, EV_REL, REL_X, dx);
    if (dy) emit(fd, EV_REL, REL_Y, dy);
    return emit(fd, EV_SYN, SYN_REPORT, 0);
}

JNIEXPORT jint JNICALL Java_org_titan2_parts_VirtualMouse_nativeButton(JNIEnv *env, jclass cls,
                                                                        jint fd, jint button,
                                                                        jboolean down) {
    (void)env;
    (void)cls;
    emit(fd, EV_KEY, button == 1 ? BTN_RIGHT : BTN_LEFT, down ? 1 : 0);
    return emit(fd, EV_SYN, SYN_REPORT, 0);
}

JNIEXPORT void JNICALL Java_org_titan2_parts_VirtualMouse_nativeClose(JNIEnv *env, jclass cls,
                                                                       jint fd) {
    (void)env;
    (void)cls;
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
}

/*
 * The "double tap on the touchpad wakes the screen" gesture: kb_touchpad.ko, /dev/touch_pad_gesture,
 * a single ioctl 0x40044303 (_IOW('C', 3, int), AGOLD_TPD_GESTURE_SWITCH): 1 on, 0 off.
 */
JNIEXPORT jint JNICALL Java_org_titan2_parts_TouchpadController_nativeGestureSwitch(JNIEnv *env,
                                                                                      jclass cls,
                                                                                      jint on) {
    (void)env;
    (void)cls;
    int fd = open("/dev/touch_pad_gesture", O_RDWR | O_CLOEXEC);
    if (fd < 0) return -errno;
    int v = on ? 1 : 0;
    int ret = ioctl(fd, 0x40044303, &v) < 0 ? -errno : 0;
    close(fd);
    return ret;
}
