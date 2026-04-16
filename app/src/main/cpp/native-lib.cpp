#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/wait.h>

namespace {

enum class KernelStatus {
    SUCCESS = 0,
    FAILED = 1,
};

struct CommandResult {
    KernelStatus status;
    std::string title;
    std::string subtitle;
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

struct ShellResult {
    bool success;
    std::string output;
};

ShellResult runSingleShell(const std::string &script) {
    int to_child[2];
    int from_child[2];

    if (pipe(to_child) != 0 || pipe(from_child) != 0) {
        return {false, ""};
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(to_child[0]);
        close(to_child[1]);
        close(from_child[0]);
        close(from_child[1]);
        return {false, ""};
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

    bool shell_ok = false;
    if (WIFEXITED(status)) {
        int exit_code = WEXITSTATUS(status);
        shell_ok = (exit_code == 0);
    }

    return {shell_ok, output};
}

CommandResult runRootShellFlow(const std::string &daemon_private_path, int whitelist_uid) {
    const std::string quoted_path = shellQuote(daemon_private_path);

    // Shell 1: 检查/启动 daemon
    const std::string daemon_script =
        "if pidof daemon >/dev/null 2>&1; then\n"
        "  echo DAEMON_RUNNING\n"
        "else\n"
        "  rm -f /data/daemon\n"
        "  cp " + quoted_path + " /data/daemon\n"
        "  chmod 777 /data/daemon\n"
        "  setsid /data/daemon </dev/null >/dev/null 2>&1 &\n"
        "  sleep 1\n"
        "  if pidof daemon >/dev/null 2>&1; then\n"
        "    echo DAEMON_RUNNING\n"
        "  else\n"
        "    echo DAEMON_FAILED\n"
        "  fi\n"
        "fi\n"
        "id\n"
        "exit\n";

    ShellResult daemon_result = runSingleShell(daemon_script);

    if (!daemon_result.success || daemon_result.output.find("uid=") == std::string::npos) {
        return {KernelStatus::FAILED, "BOOT加载失败", "可能是内核模块没有加载完成或者已经激活了脱链脱表模式。"};
    }

    const bool daemon_running = daemon_result.output.find("DAEMON_RUNNING") != std::string::npos;

    if (!daemon_running) {
        return {KernelStatus::FAILED, "BOOT加载失败", "可能是内核模块没有加载完成或者已经激活了脱链脱表模式。"};
    }

    // Shell 2: 独立设置 UID 到 /dev/kgking
    const std::string uid_str = std::to_string(whitelist_uid);
    const std::string whitelist_script =
        "if ls /dev/kgking >/dev/null 2>&1; then\n"
        "  echo " + uid_str + " > /dev/kgking\n"
        "fi\n"
        "exit\n";

    // 独立执行，不影响主判断
    runSingleShell(whitelist_script);

    return {KernelStatus::SUCCESS, "BOOT加载成功", ""};
}

jobject toKotlinResult(JNIEnv *env, const CommandResult &result) {
    jclass statusCls = env->FindClass("com/kgking/setupapp/KernelStatus");
    jmethodID valuesMethod = env->GetStaticMethodID(statusCls, "values", "()[Lcom/kgking/setupapp/KernelStatus;");
    jobjectArray statusValues = static_cast<jobjectArray>(env->CallStaticObjectMethod(statusCls, valuesMethod));
    jobject statusObj = env->GetObjectArrayElement(statusValues, static_cast<jsize>(result.status));

    jclass resultCls = env->FindClass("com/kgking/setupapp/RootResult");
    jmethodID ctor = env->GetMethodID(resultCls, "<init>", "(Lcom/kgking/setupapp/KernelStatus;Ljava/lang/String;Ljava/lang/String;)V");

    jstring title = env->NewStringUTF(result.title.c_str());
    jstring subtitle = env->NewStringUTF(result.subtitle.c_str());

    return env->NewObject(resultCls, ctor, statusObj, title, subtitle);
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_kgking_setupapp_RootBridge_runRootCommand(JNIEnv *env, jobject /*thiz*/, jstring daemonPrivatePath, jint whitelistUid) {
    const char *path_chars = env->GetStringUTFChars(daemonPrivatePath, nullptr);
    std::string path = path_chars != nullptr ? path_chars : "";
    if (path_chars != nullptr) {
        env->ReleaseStringUTFChars(daemonPrivatePath, path_chars);
    }

    return toKotlinResult(env, runRootShellFlow(path, whitelistUid));
}
