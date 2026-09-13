#include <jni.h>
#include <node.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {
constexpr const char* TAG = "AIHub-Node";
int pipe_stdout[2];
int pipe_stderr[2];
pthread_t stdout_thread;
pthread_t stderr_thread;
bool redirected = false;

void* stderr_loop(void*) {
    char buffer[2048];
    ssize_t size;
    while ((size = read(pipe_stderr[0], buffer, sizeof(buffer) - 1)) > 0) {
        if (buffer[size - 1] == '\n') --size;
        buffer[size] = 0;
        __android_log_write(ANDROID_LOG_ERROR, TAG, buffer);
    }
    return nullptr;
}

void* stdout_loop(void*) {
    char buffer[2048];
    ssize_t size;
    while ((size = read(pipe_stdout[0], buffer, sizeof(buffer) - 1)) > 0) {
        if (buffer[size - 1] == '\n') --size;
        buffer[size] = 0;
        __android_log_write(ANDROID_LOG_INFO, TAG, buffer);
    }
    return nullptr;
}

void redirect_logs_once() {
    if (redirected) return;
    redirected = true;
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(pipe_stdout) == 0) {
        dup2(pipe_stdout[1], STDOUT_FILENO);
        if (pthread_create(&stdout_thread, nullptr, stdout_loop, nullptr) == 0) {
            pthread_detach(stdout_thread);
        }
    }
    if (pipe(pipe_stderr) == 0) {
        dup2(pipe_stderr[1], STDERR_FILENO);
        if (pthread_create(&stderr_thread, nullptr, stderr_loop, nullptr) == 0) {
            pthread_detach(stderr_thread);
        }
    }
}

std::string from_jstring(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yagay_aihub_runtime_EmbeddedNodeRuntime_startNode(
        JNIEnv* env,
        jclass,
        jstring entry_path,
        jstring data_dir) {
    const std::string entry = from_jstring(env, entry_path);
    const std::string data = from_jstring(env, data_dir);
    if (entry.empty() || data.empty()) return -2;

    const std::string store = data + "/gateway-auth-profiles.json";
    setenv("HOME", data.c_str(), 1);
    setenv("TFG_STORE_PATH", store.c_str(), 1);
    setenv("TFG_CDP_URL", "http://127.0.0.1:9222", 1);
    setenv("TFG_PORT", "3456", 1);
    setenv("TFG_REQUEST_TIMEOUT_SEC", "300", 1);

    redirect_logs_once();

    const char* arg0 = "node";
    const size_t total = std::strlen(arg0) + 1 + entry.size() + 1;
    char* buffer = static_cast<char*>(std::calloc(total, 1));
    if (!buffer) return -3;

    char* argv[2];
    argv[0] = buffer;
    std::memcpy(argv[0], arg0, std::strlen(arg0));
    argv[1] = buffer + std::strlen(arg0) + 1;
    std::memcpy(argv[1], entry.c_str(), entry.size());

    __android_log_write(ANDROID_LOG_INFO, TAG, "Starting embedded Node.js Mobile runtime");
    const int result = node::Start(2, argv);
    std::free(buffer);
    return result;
}
