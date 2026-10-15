// Finite read-only companion to PowerTimeline; raw values never become product PASS.
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <linux/netlink.h>
#include <mutex>
#include <poll.h>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <time.h>
#include <unistd.h>
#include <vector>

static long long bootNs() {
    timespec t{};
    if (clock_gettime(CLOCK_BOOTTIME, &t)) std::abort();
    return static_cast<long long>(t.tv_sec) * 1000000000LL + t.tv_nsec;
}
static std::string jsonQuote(const std::string &text) {
    std::string result = "\"";
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') { result += '\\'; result += c; }
        else if (c < 32 || c >= 127) {
            char escaped[7]; std::snprintf(escaped, sizeof(escaped), "\\u%04x", c);
            result += escaped;
        } else result += c;
    }
    return result + '"';
}
struct Field { std::string path, unit; long period; long long next = 0; };
static std::mutex output;
static std::atomic<unsigned long> sequence{0};
static std::string identity;
static void emit(const std::string &fields) {
    std::lock_guard<std::mutex> lock(output);
    std::string row = "{" + identity + ",\"seq\":" + std::to_string(sequence++) + fields + "}\n";
    if (std::fwrite(row.data(), 1, row.size(), stdout) != row.size() || std::fflush(stdout)) std::exit(3);
}
int main(int argc, char **argv) {
    if (argc != 5 && argc != 6) return 2;
    char *end = nullptr; long seconds = std::strtol(argv[1], &end, 10);
    if (!end || *end || seconds < 1 || seconds > 1200) return 2;
    long afterDisconnect = 0;
    if (argc == 6) {
        afterDisconnect = std::strtol(argv[5], &end, 10);
        if (*end || afterDisconnect < 30 || afterDisconnect > 240 || seconds > 600) return 2;
    }
    for (int i : {2, 3}) {
        std::string id = argv[i];
        if (id.empty() || id.size() > 96 || id.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-") != std::string::npos) return 2;
    }
    identity = "\"run\":" + jsonQuote(argv[2]) + ",\"case\":" + jsonQuote(argv[3]);
    std::ifstream manifest(argv[4]);
    if (!manifest) return 2;
    std::vector<Field> fields; std::string line;
    while (std::getline(manifest, line)) {
        size_t a = line.find('\t'), b = line.find('\t', a == std::string::npos ? a : a + 1);
        if (a == std::string::npos || b == std::string::npos || line.size() > 1024) return 2;
        Field field{line.substr(0, a), line.substr(a + 1, b - a - 1), 0};
        field.period = std::strtol(line.c_str() + b + 1, &end, 10);
        if (*end || field.path.rfind("/sys/", 0) != 0 || field.path.find("..") != std::string::npos
                || field.period < 1000 || field.period > 60000 || field.unit.size() > 64) return 2;
        fields.push_back(field);
        if (fields.size() > 256) return 2;
    }
    if (fields.empty()) return 2;
    int socketFd = socket(AF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC, NETLINK_KOBJECT_UEVENT);
    sockaddr_nl address{}; address.nl_family = AF_NETLINK; address.nl_groups = 1;
    if (socketFd < 0 || bind(socketFd, reinterpret_cast<sockaddr *>(&address), sizeof(address))) {
        emit(",\"kind\":\"ERROR\",\"source\":\"netlink\",\"errno\":" + std::to_string(errno));
        if (socketFd >= 0) close(socketFd);
        return 4;
    }
    int bufferSize = 1024 * 1024;
    setsockopt(socketFd, SOL_SOCKET, SO_RCVBUF, &bufferSize, sizeof(bufferSize));
    const long long start = bootNs();
    std::atomic<long long> deadline{start + seconds * 1000000000LL};
    std::atomic<bool> barrier{false};
    alarm(static_cast<unsigned>(seconds + afterDisconnect + 15)); // A blocked read is an explicit failed run.
    std::atomic<bool> done{false};
    emit(",\"kind\":\"START\",\"boot_ns\":" + std::to_string(start) + ",\"uid\":" + std::to_string(getuid())
         + ",\"pid\":" + std::to_string(getpid()) + ",\"duration_s\":" + std::to_string(seconds)
         + ",\"field_count\":" + std::to_string(fields.size()) + ",\"power_node_writes\":0");
    std::thread events([&] {
        while (!done.load()) {
            pollfd descriptor{socketFd, POLLIN, 0};
            int ready = poll(&descriptor, 1, 200);
            if (ready < 0 && errno == EINTR) continue;
            if (ready <= 0) continue;
            char data[16384]; sockaddr_nl sender{}; socklen_t length = sizeof(sender);
            ssize_t count = recvfrom(socketFd, data, sizeof(data), MSG_TRUNC, reinterpret_cast<sockaddr *>(&sender), &length);
            long long received = bootNs();
            if (count < 0 || count >= static_cast<ssize_t>(sizeof(data))) {
                emit(",\"kind\":\"EVENT_ERROR\",\"boot_ns\":" + std::to_string(received) + ",\"errno\":" + std::to_string(count < 0 ? errno : EOVERFLOW));
                continue;
            }
            if (sender.nl_pid != 0) continue;
            std::string raw(data, static_cast<size_t>(count));
            if (raw.find("SUBSYSTEM=power_supply") == std::string::npos && raw.find("SUBSYSTEM=thermal") == std::string::npos) continue;
            if (afterDisconnect && raw.find(std::string("POWER_SUPPLY_NAME=usb") + '\0') != std::string::npos
                    && raw.find(std::string("POWER_SUPPLY_ONLINE=0") + '\0') != std::string::npos && !barrier.exchange(true)) {
                deadline = received + afterDisconnect * 1000000000LL;
                emit(",\"kind\":\"BARRIER\",\"boot_ns\":" + std::to_string(received) + ",\"trigger\":\"KERNEL_USB_ONLINE_0\"");
            }
            emit(",\"kind\":\"UEVENT\",\"receive_boot_ns\":" + std::to_string(received)
                 + ",\"hardware_measurement_boot_ns\":null,\"kernel_sender_pid\":0,\"raw\":" + jsonQuote(raw));
        }
    });
    unsigned batches = 0;
    do {
        long long batchStart = bootNs();
        for (Field &field : fields) {
            long long before = bootNs();
            if (before < field.next) continue;
            field.next = before + field.period * 1000000LL;
            char buffer[4097]; int error = 0;
            int fd = open(field.path.c_str(), O_RDONLY | O_CLOEXEC);
            ssize_t bytes = -1;
            if (fd < 0) error = errno;
            else {
                do { bytes = read(fd, buffer, sizeof(buffer)); } while (bytes < 0 && errno == EINTR);
                if (bytes < 0) error = errno;
                else if (bytes == 0) error = ENODATA;
                else if (bytes == static_cast<ssize_t>(sizeof(buffer))) error = EOVERFLOW;
                close(fd);
            }
            long long after = bootNs();
            emit(",\"kind\":\"READ\",\"batch\":" + std::to_string(batches) + ",\"read_start_boot_ns\":" + std::to_string(before)
                 + ",\"read_end_boot_ns\":" + std::to_string(after) + ",\"source\":\"sysfs\",\"path\":" + jsonQuote(field.path)
                 + ",\"unit\":" + jsonQuote(field.unit) + ",\"raw\":" + (error ? "null" : jsonQuote(std::string(buffer, static_cast<size_t>(bytes))))
                 + ",\"errno\":" + std::to_string(error) + ",\"valid\":" + (error ? "false" : "true")
                 + ",\"hardware_measurement_boot_ns\":null");
            if (after >= deadline.load()) break;
        }
        batches++;
        long long remaining = std::min(deadline.load(), batchStart + 1000000000LL) - bootNs();
        if (remaining > 0) std::this_thread::sleep_for(std::chrono::nanoseconds(remaining));
    } while (bootNs() < deadline.load());
    done = true; events.join(); close(socketFd); alarm(0);
    emit(",\"kind\":\"COMPLETE\",\"boot_ns\":" + std::to_string(bootNs()) + ",\"batches\":" + std::to_string(batches)
         + ",\"power_node_writes\":0");
    return 0;
}
