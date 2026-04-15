#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/wait.h>
#include <vector>

namespace {

struct CommandResult {
    bool success;
    std::string id_output;
    std::string details;
    bool daemon_running;
};

std::string shellQuote(const std::string &value) {
    std::string quoted = "'";
    for (char c : value) {
        if (c == '\'') {
            quoted += "'\\''";
        } else {
            quoted.push_back(c);
        }
    }
    quoted += "'";
    return quoted;
}

CommandResult runRootShellFlow(const std::string &daemon_private_path) {
    int to_child[2];
    int from_child[2];

    if (pipe(to_child) != 0 || pipe(from_child) != 0) {
        return {false, "", "无法创建管道（pipe）", false};
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(to_child[0]);
        close(to_child[1]);
        close(from_child[0]);
        close(from_child[1]);
        return {false, "", "fork 失败", false};
    }

    if (pid == 0) {
        dup2(to_child[0], STDIN_FILENO);
        dup2(from_child[1], STDOUT_FILENO);
        dup2(from_child[1], STDERR_FILENO);

        close(to_child[1]);
        close(from_child[0]);

        execl("/dev/kgstsu", "/dev/kgstsu", static_cast<char *>(nullptr));
        _exit(127);
    }

    close(to_child[0]);
    close(from_child[1]);

    const std::string quoted_path = shellQuote(daemon_private_path);
    const std::string script =
        "if pidof daemon >/dev/null 2>&1; then\n"
        "  echo DAEMON_ALREADY_RUNNING\n"
        "else\n"
        "  rm -f /data/daemon\n"
        "  cp " + quoted_path + " /data/daemon\n"
        "  chmod 777 /data/daemon\n"
        "  setsid /data/daemon </dev/null >/dev/null 2>&1 &\n"
        "  echo DAEMON_STARTED\n"
        "fi\n"
        "id\n"
        "exit\n";

    ssize_t written = write(to_child[1], script.c_str(), script.size());
    (void)written;
    close(to_child[1]);

    std::string output;
    char buffer[512];
    ssize_t nread = 0;
    while ((nread = read(from_child[0], buffer, sizeof(buffer))) > 0) {
        output.append(buffer, static_cast<size_t>(nread));
    }
    close(from_child[0]);

    int status = 0;
    waitpid(pid, &status, 0);

    if (WIFEXITED(status)) {
        int exit_code = WEXITSTATUS(status);
        if (exit_code == 127) {
            return {false, "", "执行失败：/dev/kgstsu 不存在或不可执行", false};
        }
        if (exit_code != 0) {
            return {false, "", "执行失败：/dev/kgstsu 退出码 = " + std::to_string(exit_code), false};
        }
    } else {
        return {false, "", "执行失败：子进程未正常退出", false};
    }

    bool daemon_running = output.find("DAEMON_ALREADY_RUNNING") != std::string::npos ||
                          output.find("DAEMON_STARTED") != std::string::npos;

    bool looks_like_id = output.find("uid=") != std::string::npos;
    if (!looks_like_id) {
        return {false, output, "执行失败：未检测到 id 输出（可能未进入 root shell）", daemon_running};
    }

    std::string details = "执行成功";
    if (output.find("DAEMON_ALREADY_RUNNING") != std::string::npos) {
        details = "执行成功：daemon 已在运行";
    } else if (output.find("DAEMON_STARTED") != std::string::npos) {
        details = "执行成功：daemon 已启动";
    }

    return {true, output, details, daemon_running};
}

jobject toKotlinResult(JNIEnv *env, const CommandResult &result) {
    jclass resultCls = env->FindClass("com/kgking/setupapp/RootResult");
    jmethodID ctor = env->GetMethodID(resultCls, "<init>", "(ZLjava/lang/String;Ljava/lang/String;Z)V");

    jstring idOutput = env->NewStringUTF(result.id_output.c_str());
    jstring details = env->NewStringUTF(result.details.c_str());

    return env->NewObject(
        resultCls,
        ctor,
        static_cast<jboolean>(result.success),
        idOutput,
        details,
        static_cast<jboolean>(result.daemon_running)
    );
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_kgking_setupapp_RootBridge_runRootCommand(JNIEnv *env, jobject /*thiz*/, jstring daemonPrivatePath) {
    const char *path_chars = env->GetStringUTFChars(daemonPrivatePath, nullptr);
    std::string path = path_chars != nullptr ? path_chars : "";
    if (path_chars != nullptr) {
        env->ReleaseStringUTFChars(daemonPrivatePath, path_chars);
    }

    return toKotlinResult(env, runRootShellFlow(path));
}
