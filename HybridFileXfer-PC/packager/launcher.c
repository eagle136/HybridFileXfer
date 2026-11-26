/*
 * 单文件启动器：exe 尾部附加一个“仅存储”的 zip（runtime + app + adb）。
 * 首次运行把 zip 内容拷到 <exe目录>\HybridFileXfer-<版本>（不可写则退到
 * %LOCALAPPDATA%\HybridFileXfer\<版本>），之后直接启动。
 * 无参数启动窗口版；带参数按命令行版（runtime\bin\java.exe -jar app\*.jar）运行。
 *
 * HFX_VER 必须随每个发版改动：解包目录名带版本，旧版本的目录不能被新 exe 复用
 * （复用＝老 jar 继续跑）。pack.py 会在编译前按版本号自动改写这一行，
 * 所以改版本只需要改打包命令，不用手工动这个文件。
 */
#define _WIN32_WINNT 0x0601
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#define HFX_VER L"3.2"
#define APP_DIR_NAME L"HybridFileXfer-" HFX_VER
#define MARKER_NAME L".complete"

static HANDLE g_in = INVALID_HANDLE_VALUE;
static uint64_t g_fileSize = 0;

static BOOL read_at(uint64_t off, void *buf, DWORD len) {
    LARGE_INTEGER li;
    DWORD total = 0;
    li.QuadPart = (LONGLONG) off;
    if (!SetFilePointerEx(g_in, li, NULL, FILE_BEGIN)) return FALSE;
    while (total < len) {
        DWORD got = 0;
        if (!ReadFile(g_in, (BYTE *) buf + total, len - total, &got, NULL) || got == 0) {
            return FALSE;
        }
        total += got;
    }
    return TRUE;
}

static uint16_t le16(const BYTE *p) {
    return (uint16_t) (p[0] | (p[1] << 8));
}

static uint32_t le32(const BYTE *p) {
    return (uint32_t) p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16)
           | ((uint32_t) p[3] << 24);
}

static void exe_dir(wchar_t *out, DWORD cap) {
    DWORD n = GetModuleFileNameW(NULL, out, cap);
    if (n == 0 || n >= cap) {
        out[0] = 0;
        return;
    }
    for (DWORD i = n; i > 0; i--) {
        if (out[i - 1] == L'\\' || out[i - 1] == L'/') {
            out[i - 1] = 0;
            return;
        }
    }
    out[0] = 0;
}

static BOOL make_dirs(const wchar_t *path) {
    wchar_t tmp[MAX_PATH * 2];
    size_t n = wcslen(path);
    if (n == 0 || n >= MAX_PATH * 2) return FALSE;
    wcscpy(tmp, path);
    for (wchar_t *p = tmp + 1; *p; p++) {
        if (*p == L'\\' || *p == L'/') {
            wchar_t c = *p;
            *p = 0;
            CreateDirectoryW(tmp, NULL);
            *p = c;
        }
    }
    if (!CreateDirectoryW(tmp, NULL)) {
        DWORD err = GetLastError();
        if (err == ERROR_ALREADY_EXISTS) return TRUE;
        if (err != ERROR_PATH_NOT_FOUND) return FALSE;
    }
    return TRUE;
}

static wchar_t *utf8_to_wide(const char *s, int len) {
    int wl = MultiByteToWideChar(CP_UTF8, 0, s, len, NULL, 0);
    if (wl <= 0) return NULL;
    wchar_t *w = (wchar_t *) malloc((wl + 1) * sizeof(wchar_t));
    if (!w) return NULL;
    MultiByteToWideChar(CP_UTF8, 0, s, len, w, wl);
    w[wl] = 0;
    return w;
}

static BOOL bad_name(const char *name) {
    if (!name || !*name) return TRUE;
    if (name[0] == '/' || name[0] == '\\') return TRUE;
    if (strchr(name, ':')) return TRUE;
    const char *p = name;
    while ((p = strstr(p, "..")) != NULL) {
        char before = (p == name) ? '/' : p[-1];
        char after = p[2];
        if ((before == '/' || before == '\\') && (after == 0 || after == '/' || after == '\\')) {
            return TRUE;
        }
        p += 2;
    }
    return FALSE;
}

static BOOL write_entry(const wchar_t *target, const wchar_t *rel, uint64_t dataOff,
                        uint64_t size) {
    wchar_t path[MAX_PATH * 2];
    if (wcslen(target) + 1 + wcslen(rel) >= MAX_PATH * 2) return FALSE;
    swprintf(path, MAX_PATH * 2, L"%ls\\%ls", target, rel);
    for (wchar_t *p = path; *p; p++) {
        if (*p == L'/') *p = L'\\';
    }
    wchar_t parent[MAX_PATH * 2];
    wcscpy(parent, path);
    wchar_t *slash = wcsrchr(parent, L'\\');
    if (slash) {
        *slash = 0;
        if (!make_dirs(parent)) return FALSE;
    }
    HANDLE out = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                             FILE_ATTRIBUTE_NORMAL, NULL);
    if (out == INVALID_HANDLE_VALUE) return FALSE;

    BOOL ok = TRUE;
    const DWORD BUF_SIZE = 262144;
    BYTE *buf = (BYTE *) malloc(BUF_SIZE);
    if (!buf) {
        CloseHandle(out);
        return FALSE;
    }
    uint64_t remain = size;
    while (remain > 0 && ok) {
        DWORD chunk = remain > BUF_SIZE ? BUF_SIZE : (DWORD) remain;
        DWORD written = 0;
        if (!read_at(dataOff, buf, chunk)
            || !WriteFile(out, buf, chunk, &written, NULL) || written != chunk) {
            ok = FALSE;
        }
        dataOff += chunk;
        remain -= chunk;
    }
    free(buf);
    CloseHandle(out);
    return ok;
}

static int extract_all(uint64_t payloadStart, uint64_t payloadEnd, const wchar_t *target) {
    BYTE tail[65557];
    uint64_t tailLen = g_fileSize < sizeof(tail) ? g_fileSize : sizeof(tail);
    if (tailLen < 22) return -1;
    if (!read_at(g_fileSize - tailLen, tail, (DWORD) tailLen)) return -1;

    long eocd = -1;
    for (long i = (long) tailLen - 22; i >= 0; i--) {
        if (tail[i] == 'P' && tail[i + 1] == 'K' && tail[i + 2] == 5 && tail[i + 3] == 6) {
            eocd = i;
            break;
        }
    }
    if (eocd < 0) return -1;
    uint32_t cdSize = le32(tail + eocd + 12);
    uint32_t cdOffset = le32(tail + eocd + 16);
    uint16_t count = le16(tail + eocd + 10);
    uint64_t eocdAbs = g_fileSize - tailLen + (uint64_t) eocd;
    if ((uint64_t) cdSize + cdOffset > eocdAbs) return -1;
    uint64_t zipStart = eocdAbs - cdSize - cdOffset;
    if (zipStart < payloadStart || zipStart >= payloadEnd) return -1;
    if (cdSize == 0 || cdSize > 16 * 1024 * 1024) return -1;

    BYTE *cd = (BYTE *) malloc(cdSize);
    if (!cd) return -1;
    if (!read_at(zipStart + cdOffset, cd, cdSize)) {
        free(cd);
        return -1;
    }

    int extracted = 0;
    uint32_t pos = 0;
    for (uint16_t i = 0; i < count && pos + 46 <= cdSize; i++) {
        if (le32(cd + pos) != 0x02014b50) break;
        uint16_t method = le16(cd + pos + 10);
        uint32_t compSize = le32(cd + pos + 20);
        uint32_t uncompSize = le32(cd + pos + 24);
        uint16_t nameLen = le16(cd + pos + 28);
        uint16_t extraLen = le16(cd + pos + 30);
        uint16_t commentLen = le16(cd + pos + 32);
        uint32_t localOff = le32(cd + pos + 42);
        const char *nameUtf8 = (const char *) (cd + pos + 46);
        if (pos + 46 + nameLen + extraLen + commentLen > cdSize) break;

        char name[1024];
        if (nameLen < sizeof(name)) {
            memcpy(name, nameUtf8, nameLen);
            name[nameLen] = 0;
            if (!bad_name(name) && method == 0 && compSize == uncompSize) {
                wchar_t *wrel = utf8_to_wide(name, nameLen);
                if (wrel) {
                    if (name[nameLen - 1] == '/' || name[nameLen - 1] == '\\') {
                        wchar_t full[MAX_PATH * 2];
                        swprintf(full, MAX_PATH * 2, L"%ls\\%ls", target, wrel);
                        make_dirs(full);
                    } else {
                        BYTE lh[30];
                        if (read_at(zipStart + localOff, lh, 30) && le32(lh) == 0x04034b50) {
                            uint16_t lName = le16(lh + 26);
                            uint16_t lExtra = le16(lh + 28);
                            uint64_t dataOff = zipStart + localOff + 30 + lName + lExtra;
                            if (write_entry(target, wrel, dataOff, compSize)) {
                                extracted++;
                            }
                        }
                    }
                    free(wrel);
                }
            }
        }
        pos += 46 + nameLen + extraLen + commentLen;
    }
    free(cd);
    return extracted;
}

static void size_stamp(char *out, size_t cap) {
    snprintf(out, cap, "%llu", (unsigned long long) g_fileSize);
}

static BOOL files_ready(const wchar_t *target) {
    wchar_t marker[MAX_PATH * 2];
    wchar_t app[MAX_PATH * 2];
    swprintf(marker, MAX_PATH * 2, L"%ls\\%ls", target, MARKER_NAME);
    swprintf(app, MAX_PATH * 2, L"%ls\\HybridFileXfer.exe", target);
    if (GetFileAttributesW(app) == INVALID_FILE_ATTRIBUTES) return FALSE;

    HANDLE h = CreateFileW(marker, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                           NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return FALSE;
    char buf[64] = {0};
    DWORD read = 0;
    ReadFile(h, buf, sizeof(buf) - 1, &read, NULL);
    CloseHandle(h);
    char stamp[64];
    size_stamp(stamp, sizeof(stamp));
    return strncmp(buf, stamp, strlen(stamp)) == 0;
}

static void write_marker(const wchar_t *target) {
    wchar_t marker[MAX_PATH * 2];
    swprintf(marker, MAX_PATH * 2, L"%ls\\%ls", target, MARKER_NAME);
    HANDLE h = CreateFileW(marker, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, NULL);
    if (h != INVALID_HANDLE_VALUE) {
        char stamp[64];
        DWORD written = 0;
        size_stamp(stamp, sizeof(stamp));
        WriteFile(h, stamp, (DWORD) strlen(stamp), &written, NULL);
        CloseHandle(h);
    }
}

static BOOL choose_target(wchar_t *target, DWORD cap) {
    wchar_t dir[MAX_PATH];
    exe_dir(dir, MAX_PATH);
    if (dir[0] && swprintf(target, cap, L"%ls\\%ls", dir, APP_DIR_NAME) > 0) {
        if (CreateDirectoryW(target, NULL) || GetLastError() == ERROR_ALREADY_EXISTS) {
            return TRUE;
        }
    }
    wchar_t appData[MAX_PATH];
    DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", appData, MAX_PATH);
    if (n > 0 && n < MAX_PATH) {
        swprintf(target, cap, L"%ls\\HybridFileXfer\\" HFX_VER, appData);
        return make_dirs(target);
    }
    return FALSE;
}

static BOOL has_arguments(const wchar_t *tail) {
    while (*tail == L' ' || *tail == L'\t') tail++;
    return *tail != 0;
}

int main(void) {
    SetConsoleOutputCP(CP_UTF8);
    const wchar_t *cmd = GetCommandLineW();
    const wchar_t *tail = cmd;
    if (*tail == L'"') {
        tail++;
        while (*tail && *tail != L'"') tail++;
        if (*tail == L'"') tail++;
    } else {
        while (*tail && *tail != L' ' && *tail != L'\t') tail++;
    }
    BOOL cli = has_arguments(tail);
    // 打开自身
    {
        wchar_t self[MAX_PATH];
        GetModuleFileNameW(NULL, self, MAX_PATH);
        g_in = CreateFileW(self, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING,
                           FILE_ATTRIBUTE_NORMAL, NULL);
    }
    if (g_in == INVALID_HANDLE_VALUE) {
        MessageBoxW(NULL, L"无法打开自身文件。", L"多轨快传", MB_ICONERROR);
        return 1;
    }
    LARGE_INTEGER li;
    if (!GetFileSizeEx(g_in, &li)) {
        CloseHandle(g_in);
        MessageBoxW(NULL, L"无法读取文件大小。", L"多轨快传", MB_ICONERROR);
        return 1;
    }
    g_fileSize = (uint64_t) li.QuadPart;

    wchar_t target[MAX_PATH * 2];
    if (!choose_target(target, MAX_PATH * 2)) {
        CloseHandle(g_in);
        MessageBoxW(NULL, L"无法创建运行目录，请把程序放到可写的文件夹后重试。",
                    L"多轨快传", MB_ICONERROR);
        return 1;
    }

    if (!files_ready(target)) {
        fprintf(stdout, "首次运行：正在准备运行环境（约 50MB，仅拷贝解包）...\n");
        fflush(stdout);
        int n = extract_all(0, g_fileSize, target);
        if (n <= 0) {
            CloseHandle(g_in);
            MessageBoxW(NULL,
                        L"运行环境释放失败。请关闭正在运行的多轨快传后重试，"
                        L"或把程序移到可写目录。",
                        L"多轨快传", MB_ICONERROR);
            return 1;
        }
        write_marker(target);
        fprintf(stdout, "准备完成（%d 个文件）。\n", n);
        fflush(stdout);
    }
    CloseHandle(g_in);

    wchar_t exe[MAX_PATH * 2];
    wchar_t cmdline[MAX_PATH * 4];
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    if (!cli) {
        swprintf(exe, MAX_PATH * 2, L"%ls\\HybridFileXfer.exe", target);
        swprintf(cmdline, MAX_PATH * 4, L"\"%ls\"", exe);
        BOOL ok = CreateProcessW(NULL, cmdline, NULL, NULL, FALSE, 0, NULL, target, &si, &pi);
        if (!ok) {
            MessageBoxW(NULL, L"启动窗口程序失败，运行环境可能被拦截或损坏。",
                        L"多轨快传", MB_ICONERROR);
            return 1;
        }
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        FreeConsole();
        return 0;
    }

    swprintf(exe, MAX_PATH * 2, L"%ls\\runtime\\bin\\java.exe", target);
    swprintf(cmdline, MAX_PATH * 4,
             L"\"%ls\" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "
             L"-jar \"%ls\\app\\HybridFileXfer-PC.jar\"%ls",
             exe, target, tail);
    if (!CreateProcessW(NULL, cmdline, NULL, NULL, TRUE, 0, NULL, target, &si, &pi)) {
        fprintf(stderr, "启动命令行程序失败。\n");
        return 1;
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return (int) code;
}
