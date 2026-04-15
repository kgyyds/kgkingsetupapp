#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/wait.h>

namespace {

enum class KernelStatus {
    RUNNING = 0,
    DELINKED = 1,
    FAILED = 2,
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

CommandResult runRootShellFlow(const std::string &daemon_private_path, int whitelist_uid) {
    int to_child[2];
    int from_child[2];

    if (pipe(to_child) != 0 || pipe(from_child) != 0) {
        return {KernelStatus::FAILED, "失败", "可能是内核模块没有加载完成或者已经激活了脱链脱表模式。"};
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(to_child[0]);
        close(to_child[1]);
        close(from_child[0]);
        close(from_child[1]);
        return {KernelStatus::FAILED, "失败", "可能是内核模块没有加载完成或者已经激活了脱链脱表模式。"};
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
    const std::string uid_str = std::to_string(whitelist_uid);
    const std::string script =
        "if pidof daemon >/dev/null 2>&1; then\n"
        "  echo DAEMON_RUNNING\n"
        "else\n"
        "  rm -f /data/daemon\n"
        "  cp " + quoted_path + " /data/daemon\n"
        "  chmod 777 /data/daemon\n"
        "  setsid /data/daemon </dev/null >/dev/null 2>&1 &\n"
        "  if pidof daemon >/dev/null 2>&1; then\n"
        "    echo DAEMON_RUNNING\n"
        "  else\n"
        "    echo DAEMON_FAILED\n"
        "  fi\n"
        "fi\n"
        "if ls /dev/kgking >/dev/null 2>&1; then\n"
        "  echo " + uid_str + " > /dev/kgking\n"
        "  echo WHITE_OK\n"
        "else\n"
        "  echo WHITE_MISSING\n"
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

    bool shell_ok = false;
    if (WIFEXITED(status)) {
        int exit_code = WEXITSTATUS(status);
        shell_ok = (exit_code == 0);
    }

    if (!shell_ok || output.find("uid=") == std::string::npos) {
        return {KernelStatus::FAILED, "失败", "可能是内核模块没有加载完成或者已经激活了脱链脱表模式。"};
    }

    const bool daemon_running = output.find("DAEMON_RUNNING") != std::string::npos;
    const bool delinked = output.find("WHITE_MISSING") != std::string::npos;

    if (daemon_running && delinked) {
        return {KernelStatus::DELINKED, "daemon已成功运行，并且脱链脱表模式已启动", ""};
    }

    if (daemon_running) {
        return {KernelStatus::RUNNING, "内核模块正在运行", ""};
    }

    return {KernelStatus::FAILED, "失败", "可能是内核模块没有加载完成或者已经激活了脱链脱表模式。"};
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
