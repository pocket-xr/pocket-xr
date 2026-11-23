// 1.6.9: isochronous USB reads for UVC cameras that stream that way (the XREAL Air 2 Ultra's tracking cameras).
// Android's Java USB API has bulk and interrupt transfers only, so the transfers are submitted with Linux's usbdevfs
// ioctls on the file descriptor of an open UsbDeviceConnection (the interface already claimed and its alternate
// setting chosen from Kotlin). The approach follows rfs2671/Xreal's app/src/main/cpp/iso.c (GPL-3.0, like Pocket XR).
#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>

typedef struct {
    int fd;
    int urb_count;
    int packets;
    int packet_size;
    struct usbdevfs_urb **urbs;
} iso_stream;

static void reset_urb(const iso_stream *s, struct usbdevfs_urb *u) {
    for (int i = 0; i < s->packets; i++) {
        u->iso_frame_desc[i].length = (unsigned int)s->packet_size;
        u->iso_frame_desc[i].actual_length = 0;
        u->iso_frame_desc[i].status = 0;
    }
    u->actual_length = 0;
    u->error_count = 0;
    u->status = 0;
}

static void free_stream(iso_stream *s) {
    if (!s) return;
    if (s->urbs) {
        for (int i = 0; i < s->urb_count; i++) {
            if (s->urbs[i]) {
                free(s->urbs[i]->buffer);
                free(s->urbs[i]);
            }
        }
        free(s->urbs);
    }
    free(s);
}

// Discards [count] submitted URBs and reaps them, so the kernel no longer writes into their buffers.
static void discard(iso_stream *s, int count) {
    for (int i = 0; i < count; i++) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    for (int i = 0; i < count * 2; i++) {
        struct pollfd p = {.fd = s->fd, .events = POLLOUT};
        if (poll(&p, 1, 100) <= 0) break;
        void *done = NULL;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &done) < 0) break;
    }
}

// Submits [urbs] transfers of [packets] packets of [packetSize] bytes each on [endpoint]. Returns a handle, or -errno.
JNIEXPORT jlong JNICALL Java_com_hayeco_flipspaces_hands_uvc_UsbIso_start(
        JNIEnv *env, jclass cls, jint fd, jint endpoint, jint packetSize, jint packets, jint urbs) {
    (void)env;
    (void)cls;
    if (fd < 0 || packetSize <= 0 || packets <= 0 || urbs <= 0) return -EINVAL;
    iso_stream *s = calloc(1, sizeof(*s));
    if (!s) return -ENOMEM;
    s->fd = fd;
    s->urb_count = urbs;
    s->packets = packets;
    s->packet_size = packetSize;
    s->urbs = calloc((size_t)urbs, sizeof(*s->urbs));
    if (!s->urbs) {
        free_stream(s);
        return -ENOMEM;
    }
    for (int i = 0; i < urbs; i++) {
        struct usbdevfs_urb *u = calloc(1, sizeof(*u) + (size_t)packets * sizeof(struct usbdevfs_iso_packet_desc));
        if (!u) {
            free_stream(s);
            return -ENOMEM;
        }
        s->urbs[i] = u;
        u->buffer = malloc((size_t)packets * (size_t)packetSize);
        if (!u->buffer) {
            free_stream(s);
            return -ENOMEM;
        }
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->endpoint = (unsigned char)endpoint;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->buffer_length = packets * packetSize;
        u->number_of_packets = packets;
        reset_urb(s, u);
    }
    for (int i = 0; i < urbs; i++) {
        if (ioctl(fd, USBDEVFS_SUBMITURB, s->urbs[i]) < 0) {
            int e = errno;
            discard(s, i);
            free_stream(s);
            return -e;
        }
    }
    return (jlong)(intptr_t)s;
}

// Waits up to [timeoutMs] for one transfer. Its packets' bytes go one after another into [out] (a direct buffer);
// lengths[0] is the number of packets copied, lengths[1] the packets that failed, lengths[2..] each copied packet's
// length. The transfer is then submitted again. Returns the bytes copied, 0 on a timeout, or -errno.
JNIEXPORT jint JNICALL Java_com_hayeco_flipspaces_hands_uvc_UsbIso_reap(
        JNIEnv *env, jclass cls, jlong handle, jobject out, jintArray lengths, jint timeoutMs) {
    (void)cls;
    iso_stream *s = (iso_stream *)(intptr_t)handle;
    if (!s) return -EINVAL;
    uint8_t *dst = (*env)->GetDirectBufferAddress(env, out);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, out);
    if (!dst || capacity < (jlong)s->packets * s->packet_size) return -EINVAL;
    if ((*env)->GetArrayLength(env, lengths) < s->packets + 2) return -EINVAL;

    struct pollfd p = {.fd = s->fd, .events = POLLOUT};
    int ready = poll(&p, 1, timeoutMs);
    if (ready < 0) return errno == EINTR ? 0 : -errno;
    if (ready == 0) return 0;
    if ((p.revents & (POLLERR | POLLHUP | POLLNVAL)) && !(p.revents & POLLOUT)) return -ENODEV;
    struct usbdevfs_urb *u = NULL;
    if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &u) < 0) return errno == EAGAIN ? 0 : -errno;
    if (!u) return 0;

    jint *len = (*env)->GetIntArrayElements(env, lengths, NULL);
    if (!len) return -ENOMEM;
    int total = 0, copied = 0, failed = 0;
    const uint8_t *src = u->buffer;
    for (int i = 0; i < u->number_of_packets; i++) {
        const struct usbdevfs_iso_packet_desc *d = &u->iso_frame_desc[i];
        if (d->status != 0) {
            failed++;
        } else if (d->actual_length > 0 && d->actual_length <= (unsigned int)s->packet_size) {
            memcpy(dst + total, src + (size_t)i * (size_t)s->packet_size, d->actual_length);
            len[2 + copied++] = (jint)d->actual_length;
            total += (int)d->actual_length;
        }
    }
    len[0] = copied;
    len[1] = failed;
    (*env)->ReleaseIntArrayElements(env, lengths, len, 0);

    reset_urb(s, u);
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, u) < 0) return -errno;
    return total;
}

JNIEXPORT void JNICALL Java_com_hayeco_flipspaces_hands_uvc_UsbIso_stop(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    iso_stream *s = (iso_stream *)(intptr_t)handle;
    if (!s) return;
    discard(s, s->urb_count);
    free_stream(s);
}
