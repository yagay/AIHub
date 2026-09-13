#include <arpa/inet.h>
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

static bool write_all(int fd, const char* data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        ssize_t written = write(fd, data + offset, size - offset);
        if (written < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        offset += static_cast<size_t>(written);
    }
    return true;
}

static int connect_abstract(const char* name) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    size_t length = strlen(name);
    if (length + 1 >= sizeof(address.sun_path)) {
        close(fd);
        errno = ENAMETOOLONG;
        return -1;
    }
    address.sun_path[0] = '\0';
    memcpy(address.sun_path + 1, name, length);
    socklen_t address_length = static_cast<socklen_t>(
        offsetof(sockaddr_un, sun_path) + 1 + length);
    if (connect(fd, reinterpret_cast<sockaddr*>(&address), address_length) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void bridge_client(int client, const char* abstract_name) {
    int remote = connect_abstract(abstract_name);
    if (remote < 0) {
        dprintf(2, "connect @%s failed: %s\n", abstract_name, strerror(errno));
        close(client);
        _exit(2);
    }

    bool client_read = true;
    bool remote_read = true;
    char buffer[32768];
    while (client_read || remote_read) {
        pollfd fds[2] = {
            {client, static_cast<short>(client_read ? POLLIN : 0), 0},
            {remote, static_cast<short>(remote_read ? POLLIN : 0), 0},
        };
        int ready = poll(fds, 2, -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (client_read && (fds[0].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t count = read(client, buffer, sizeof(buffer));
            if (count > 0) {
                if (!write_all(remote, buffer, static_cast<size_t>(count))) break;
            } else {
                client_read = false;
                shutdown(remote, SHUT_WR);
            }
        }
        if (remote_read && (fds[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t count = read(remote, buffer, sizeof(buffer));
            if (count > 0) {
                if (!write_all(client, buffer, static_cast<size_t>(count))) break;
            } else {
                remote_read = false;
                shutdown(client, SHUT_WR);
            }
        }
    }

    close(remote);
    close(client);
    _exit(0);
}

int main(int argc, char** argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s <tcp-port> <abstract-socket-name>\n", argv[0]);
        return 64;
    }
    int port = atoi(argv[1]);
    if (port < 1 || port > 65535) return 64;

    signal(SIGCHLD, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);

    int server = socket(AF_INET, SOCK_STREAM, 0);
    if (server < 0) return 1;
    int reuse = 1;
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<uint16_t>(port));
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(server, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        dprintf(2, "bind 127.0.0.1:%d failed: %s\n", port, strerror(errno));
        return 1;
    }
    if (listen(server, 16) != 0) return 1;

    for (;;) {
        int client = accept(server, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR) continue;
            break;
        }
        pid_t pid = fork();
        if (pid == 0) {
            close(server);
            bridge_client(client, argv[2]);
        }
        close(client);
    }
    close(server);
    return 0;
}
