#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/wait.h>
#include <cstring>

namespace {

struct CommandResult {
    bool success;
    std::string id_output;
    std::string details;
};

CommandResult runRootShellFlow() {
    int to_child[2];
    int from_child[2];

    if (pipe(to_child) != 0 || pipe(from_child) != 0) {
        return {false, "", "无法创建管道（pipe）"};
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(to_child[0]);
        close(to_child[1]);
        close(from_child[0]);
        close(from_child[1]);
        return {false, "", "fork 失败"};
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

    const std::string script = "id\nexit\n";
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
            return {false, "", "执行失败：/dev/kgstsu 不存在或不可执行"};
        }
        if (exit_code != 0) {
            return {false, "", "执行失败：/dev/kgstsu 退出码 = " + std::to_string(exit_code)};
        }
    } else {
        return {false, "", "执行失败：子进程未正常退出"};
    }

    bool looks_like_id = output.find("uid=") != std::string::npos;
    if (!looks_like_id) {
        return {false, output, "执行失败：未检测到 id 输出（可能未进入 root shell）"};
    }

    return {true, output, "执行成功"};
}

jobject toKotlinResult(JNIEnv *env, const CommandResult &result) {
    jclass resultCls = env->FindClass("com/kgking/setupapp/RootResult");
    jmethodID ctor = env->GetMethodID(resultCls, "<init>", "(ZLjava/lang/String;Ljava/lang/String;)V");

    jstring idOutput = env->NewStringUTF(result.id_output.c_str());
    jstring details = env->NewStringUTF(result.details.c_str());

    return env->NewObject(resultCls, ctor, static_cast<jboolean>(result.success), idOutput, details);
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_kgking_setupapp_NativeBridge_runRootCommand(JNIEnv *env, jobject /*thiz*/) {
    return toKotlinResult(env, runRootShellFlow());
}
