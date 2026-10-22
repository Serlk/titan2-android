// titan2-bootconsole: "verbose boot" like on regular Linux: the kernel log and logcat as text
// over the boot animation until the system has booted (sys.boot_completed=1).
// Enabled by persist.sys.titan2.verbose_boot=1 (Titan2Parts -> "Performance").
// Draws in software (CPU) into a SurfaceFlinger layer above bootanimation; VGA 8x16 font, x2 scale.

#include <android-base/properties.h>
#include <android/native_window.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>
#include <ui/DisplayMode.h>
#include <utils/String8.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <string>
#include <thread>

#include "font8x16.h"

using namespace android;

namespace {

constexpr int kScale = 2;
constexpr int kGlyphW = 8 * kScale, kGlyphH = 16 * kScale;
constexpr uint32_t kGrey = 0xffb0b0b0, kWhite = 0xffffffff, kRed = 0xff5050ff, kYellow = 0xff30d0ff,
                   kGreen = 0xff60e060;  // ABGR (RGBA_8888 in little-endian memory)

struct Line {
    std::string text;
    uint32_t color;
};

std::mutex gMutex;
std::condition_variable gCv;
std::deque<Line> gLines;
bool gDirty = false;
std::atomic<bool> gDone{false};

void push(std::string text, uint32_t color) {
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) text.pop_back();
    std::lock_guard<std::mutex> l(gMutex);
    gLines.push_back({std::move(text), color});
    if (gLines.size() > 400) gLines.pop_front();
    gDirty = true;
    gCv.notify_one();
}

// /dev/kmsg: "prio,seq,usec,flags;message"
void kmsgThread() {
    FILE* f = fopen("/dev/kmsg", "re");
    if (!f) return;
    char buf[1024];
    while (!gDone && fgets(buf, sizeof(buf), f)) {
        char* msg = strchr(buf, ';');
        if (!msg) continue;
        int prio = atoi(buf) & 7;
        long long usec = 0;
        sscanf(buf, "%*d,%*d,%lld", &usec);
        char head[32];
        snprintf(head, sizeof(head), "[%5lld.%03lld] ", usec / 1000000, (usec / 1000) % 1000);
        push(std::string(head) + (msg + 1), prio <= 3 ? kRed : prio == 4 ? kYellow : kGrey);
    }
    fclose(f);
}

// logcat: init, services, system_server: what comes after the kernel
void logcatThread() {
    while (!gDone && access("/dev/socket/logdr", F_OK) != 0) usleep(200 * 1000);
    FILE* p = popen("/system/bin/logcat -b main,system,crash -v brief -T 1 2>/dev/null", "re");
    if (!p) return;
    char buf[1024];
    while (!gDone && fgets(buf, sizeof(buf), p)) {
        uint32_t c = buf[0] == 'E' || buf[0] == 'F' ? kRed : buf[0] == 'W' ? kYellow : kWhite;
        push(buf, c);
    }
    pclose(p);
}

void drawChar(uint32_t* px, int stride, int x, int y, unsigned char ch, uint32_t color) {
    const uint8_t* g = &kFont8x16[ch * 16];
    for (int row = 0; row < 16; row++) {
        uint8_t bits = g[row];
        for (int col = 0; col < 8; col++) {
            if (!(bits & (0x80 >> col))) continue;
            for (int sy = 0; sy < kScale; sy++) {
                uint32_t* d = px + (y + row * kScale + sy) * stride + x + col * kScale;
                for (int sx = 0; sx < kScale; sx++) d[sx] = color;
            }
        }
    }
}

void render(ANativeWindow_Buffer& b, const std::deque<Line>& lines) {
    auto* px = static_cast<uint32_t*>(b.bits);
    for (int y = 0; y < b.height; y++) {
        uint32_t* row = px + y * b.stride;
        for (int x = 0; x < b.width; x++) row[x] = 0xff000000;
    }
    const int cols = b.width / kGlyphW, rows = b.height / kGlyphH;
    // wrap long lines, bottom up: the screen shows the last rows visual lines
    std::deque<std::pair<std::string, uint32_t>> vis;
    for (auto it = lines.rbegin(); it != lines.rend() && (int)vis.size() < rows; ++it) {
        const std::string& t = it->text;
        std::deque<std::string> parts;
        for (size_t i = 0; i < t.size() || parts.empty(); i += cols) parts.push_back(t.substr(i, cols));
        for (auto pit = parts.rbegin(); pit != parts.rend() && (int)vis.size() < rows; ++pit)
            vis.push_front({*pit, it->color});
    }
    int y = 0;
    for (auto& [s, c] : vis) {
        int x = 0;
        for (unsigned char ch : s) {
            if (ch >= 0x80) ch = '?';  // UTF-8 (e.g. non-Latin text): the font is ASCII
            drawChar(px, b.stride, x, y, ch, c);
            x += kGlyphW;
        }
        y += kGlyphH;
    }
}

}  // namespace

int main() {
    // the trigger also fires when the toggle is enabled in an already booted system
    if (base::GetProperty("sys.boot_completed", "") == "1") return 0;
    sp<SurfaceComposerClient> client = new SurfaceComposerClient();
    // SurfaceFlinger comes up slightly after us
    for (int i = 0; i < 200 && client->initCheck() != NO_ERROR; i++) {
        usleep(100 * 1000);
        client = new SurfaceComposerClient();
    }
    auto ids = SurfaceComposerClient::getPhysicalDisplayIds();
    if (ids.empty()) return 1;
    sp<IBinder> token = SurfaceComposerClient::getPhysicalDisplayToken(ids.front());
    ui::DisplayMode mode;
    if (!token || SurfaceComposerClient::getActiveDisplayMode(token, &mode) != NO_ERROR) return 1;
    const int w = mode.resolution.getWidth(), h = mode.resolution.getHeight();

    sp<SurfaceControl> sc = client->createSurface(String8("titan2-bootconsole"), w, h,
                                                  PIXEL_FORMAT_RGBA_8888);
    if (!sc) return 1;
    // above bootanimation (0x40000000)
    SurfaceComposerClient::Transaction().setLayer(sc, 0x7ffffff0).show(sc).apply();
    sp<Surface> surface = sc->getSurface();

    push("titan2-bootconsole: verbose boot (persist.sys.titan2.verbose_boot=1)", kGreen);
    std::thread(kmsgThread).detach();
    std::thread(logcatThread).detach();

    using namespace std::chrono_literals;
    while (base::GetProperty("sys.boot_completed", "") != "1") {
        std::deque<Line> snapshot;
        {
            std::unique_lock<std::mutex> l(gMutex);
            gCv.wait_for(l, 500ms, [] { return gDirty; });
            if (!gDirty) continue;
            gDirty = false;
            snapshot = gLines;
        }
        ANativeWindow_Buffer buf;
        if (surface->lock(&buf, nullptr) == NO_ERROR) {
            render(buf, snapshot);
            surface->unlockAndPost();
        }
        std::this_thread::sleep_for(50ms);  // at most 20 frames/s
    }
    push("boot completed", kGreen);
    gDone = true;
    SurfaceComposerClient::Transaction().hide(sc).apply();
    return 0;
}
