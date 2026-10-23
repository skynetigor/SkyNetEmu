
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif

#include <winsock2.h>
#include <windows.h>
#include <psapi.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <tlhelp32.h>


#define SERVER_PORT 7946
#define CLIENT_PORT 7947
#define BUFFER_SIZE 64

#define RC_EXIT 0
#define RC_INIT 1
#define RC_EXEC 2
#define RC_KILL_PROCESS 3
#define RC_LIST_PROCESSES 4
#define RC_GET_PROCESS 5
#define RC_SET_PROCESS_AFFINITY 6
#define RC_MOUSE_EVENT 7
#define RC_GET_GAMEPAD                                                         \
  8
#define RC_GET_GAMEPAD_STATE                                                   \
  9
#define RC_RELEASE_GAMEPAD                                                     \
  10
#define RC_KEYBOARD_EVENT 11
#define RC_BRING_TO_FRONT 12
#define RC_CURSOR_POS_FEEDBACK 13

#pragma pack(push, 1)
typedef struct {
  BYTE code;
  DWORD padding;
  WORD numProcesses;
  WORD index;
  DWORD pid;
  DWORD64 memory;
  DWORD affinity;
  BYTE isWow64;
  char name[32];
} ProcessPacket;
#pragma pack(pop)

// These offsets are the Java WinHandler protocol, independent of the Windows ABI.
_Static_assert(sizeof(ProcessPacket) == 58, "ProcessPacket must match WinHandler");
_Static_assert(offsetof(ProcessPacket, memory) == 13, "Unexpected memory offset");
_Static_assert(offsetof(ProcessPacket, name) == 26, "Unexpected name offset");

static int readInt(const char *buffer) {
  int value;
  memcpy(&value, buffer, sizeof(value));
  return value;
}

static short readShort(const char *buffer) {
  short value;
  memcpy(&value, buffer, sizeof(value));
  return value;
}

// Apply the Windows command-line quoting rules, including quotes and trailing
// backslashes. Fixed-size strcat buffers corrupt long game launch arguments.
static char *quoteArguments(int argc, char **argv, int first) {
  size_t capacity = 1;
  for (int i = first; i < argc; i++) {
    size_t length = strlen(argv[i]);
    if (length > (SIZE_MAX - capacity - 3) / 2)
      return NULL;
    capacity += length * 2 + 3;
  }
  char *result = (char *)malloc(capacity);
  if (!result)
    return NULL;
  char *out = result;
  for (int i = first; i < argc; i++) {
    if (i != first)
      *out++ = ' ';
    // Some games parse GetCommandLine directly and require plain switches.
    // Quote only arguments which need it, while preserving CRT round-tripping.
    if (argv[i][0] && !strpbrk(argv[i], " \t\r\n\v\f\"")) {
      size_t length = strlen(argv[i]);
      memcpy(out, argv[i], length);
      out += length;
      continue;
    }
    *out++ = '"';
    const char *arg = argv[i];
    while (*arg) {
      size_t slashes = 0;
      while (*arg == '\\') {
        slashes++;
        arg++;
      }
      if (*arg == '"' || *arg == '\0')
        slashes *= 2;
      while (slashes--)
        *out++ = '\\';
      if (*arg == '"')
        *out++ = '\\';
      if (*arg)
        *out++ = *arg++;
    }
    *out++ = '"';
  }
  *out = '\0';
  return result;
}

SOCKET sock;
HANDLE serverReadyEvent;
struct sockaddr_in clientAddr;
int clientAddrLen = sizeof(clientAddr);
volatile BOOL running = TRUE;

typedef struct PidNode {
  DWORD pid;
  struct PidNode *next;
} PidNode;

PidNode *seenPidsHead = NULL;

void addSeenPid(DWORD pid) {
  PidNode *newNode = (PidNode *)malloc(sizeof(PidNode));
  if (!newNode)
    return;
  newNode->pid = pid;
  newNode->next = seenPidsHead;
  seenPidsHead = newNode;
}

BOOL isPidSeen(DWORD pid) {
  PidNode *current = seenPidsHead;
  while (current) {
    if (current->pid == pid)
      return TRUE;
    current = current->next;
  }
  return FALSE;
}

void sendCursorFeedback() {
  POINT ptr;
  if (GetCursorPos(&ptr)) {
    BYTE buffer[5];
    buffer[0] = RC_CURSOR_POS_FEEDBACK;
    short x = (short)ptr.x;
    short y = (short)ptr.y;
    memcpy(buffer + 1, &x, sizeof(x));
    memcpy(buffer + 3, &y, sizeof(y));
    sendto(sock, (const char *)buffer, 5, 0, (struct sockaddr *)&clientAddr,
           clientAddrLen);
  }
}

void handleListProcesses() {
  HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (hSnap == INVALID_HANDLE_VALUE)
    return;

  PROCESSENTRY32 pe32;
  pe32.dwSize = sizeof(PROCESSENTRY32);

  int count = 0;
  if (Process32First(hSnap, &pe32)) {
    do {
      count++;
    } while (Process32Next(hSnap, &pe32));
  }

  if (count == 0) {
    CloseHandle(hSnap);
    return;
  }

  Process32First(hSnap, &pe32);
  int index = 0;
  do {

    ProcessPacket packet;
    ZeroMemory(&packet, sizeof(packet));
    packet.code = RC_GET_PROCESS;
    packet.numProcesses = (WORD)count;
    packet.index = (WORD)index;
    packet.pid = pe32.th32ProcessID;
    strncpy(packet.name, pe32.szExeFile, 31);
    packet.name[31] = '\0';

    HANDLE hProcess = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ,
                                  FALSE, pe32.th32ProcessID);
    if (hProcess) {
      PROCESS_MEMORY_COUNTERS pmc;
      if (GetProcessMemoryInfo(hProcess, &pmc, sizeof(pmc))) {
        packet.memory = pmc.WorkingSetSize;
      }

      DWORD_PTR procAffinity, sysAffinity;
      if (GetProcessAffinityMask(hProcess, &procAffinity, &sysAffinity)) {
        packet.affinity = (DWORD)procAffinity;
      }

      BOOL isWow64 = FALSE;
      IsWow64Process(hProcess, &isWow64);
      packet.isWow64 = (BYTE)isWow64;

      CloseHandle(hProcess);
    }

    sendto(sock, (const char *)&packet, sizeof(packet), 0,
           (struct sockaddr *)&clientAddr, clientAddrLen);
    index++;
  } while (Process32Next(hSnap, &pe32) && index < count);

  CloseHandle(hSnap);
}

void handleExec(const char *payload, int len) {

  if (len < 8)
    return;
  int filenameLen = readInt(payload);
  int paramsLen = readInt(payload + 4);

  if (filenameLen <= 0 || paramsLen < 0 || filenameLen > len - 8 ||
      paramsLen > len - 8 - filenameLen)
    return;

  char *filename = (char *)malloc(filenameLen + 1);
  char *params = (char *)malloc(paramsLen + 1);

  if (!filename || !params) {
    free(filename);
    free(params);
    return;
  }
  memcpy(filename, payload + 8, filenameLen);
  filename[filenameLen] = '\0';
  memcpy(params, payload + 8 + filenameLen, paramsLen);
  params[paramsLen] = '\0';

  ShellExecuteA(NULL, "open", filename, params, NULL, SW_SHOW);

  if (filename)
    free(filename);
  if (params)
    free(params);
}

void handleKillProcess(const char *payload, int len) {

  if (len < 4)
    return;
  int nameLen = readInt(payload);
  if (nameLen <= 0 || nameLen > len - 4)
    return;
  char *name = (char *)malloc(nameLen + 1);
  if (!name)
    return;
  memcpy(name, payload + 4, nameLen);
  name[nameLen] = '\0';

  HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (hSnap != INVALID_HANDLE_VALUE) {
    PROCESSENTRY32 pe32;
    pe32.dwSize = sizeof(PROCESSENTRY32);
    if (Process32First(hSnap, &pe32)) {
      do {
        if (_stricmp(pe32.szExeFile, name) == 0) {
          HANDLE hProcess =
              OpenProcess(PROCESS_TERMINATE, FALSE, pe32.th32ProcessID);
          if (hProcess) {
            TerminateProcess(hProcess, 1);
            CloseHandle(hProcess);
          }
        }
      } while (Process32Next(hSnap, &pe32));
    }
    CloseHandle(hSnap);
  }
  free(name);
}

void handleSetAffinity(const char *buffer, int len) {

  if (len < 14)
    return;
  int pid = readInt(buffer + 5);
  int mask = readInt(buffer + 9);

  if (pid != 0) {
    HANDLE hProcess = OpenProcess(PROCESS_SET_INFORMATION, FALSE, pid);
    if (hProcess) {
      SetProcessAffinityMask(hProcess, mask);
      CloseHandle(hProcess);
    }
  } else {
    int nameLen = (unsigned char)buffer[13];
    if (nameLen <= 0 || nameLen > len - 14)
      return;
    char *name = (char *)malloc(nameLen + 1);
    if (!name)
      return;
    memcpy(name, buffer + 14, nameLen);
    name[nameLen] = '\0';

    HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnap != INVALID_HANDLE_VALUE) {
      PROCESSENTRY32 pe32;
      pe32.dwSize = sizeof(PROCESSENTRY32);
      if (Process32First(hSnap, &pe32)) {
        do {
          if (_stricmp(pe32.szExeFile, name) == 0) {
            HANDLE hProcess =
                OpenProcess(PROCESS_SET_INFORMATION, FALSE, pe32.th32ProcessID);
            if (hProcess) {
              SetProcessAffinityMask(hProcess, mask);
              CloseHandle(hProcess);
            }
          }
        } while (Process32Next(hSnap, &pe32));
      }
      CloseHandle(hSnap);
    }
    free(name);
  }
}

struct FindWindowData {
  DWORD pid;
  HWND hWnd;
};

BOOL CALLBACK EnumWindowsProc(HWND hWnd, LPARAM lParam) {
  struct FindWindowData *data = (struct FindWindowData *)lParam;
  DWORD currPid = 0;
  GetWindowThreadProcessId(hWnd, &currPid);
  if (currPid == data->pid && IsWindowVisible(hWnd) &&
      GetWindow(hWnd, GW_OWNER) == NULL) {
    data->hWnd = hWnd;
    return FALSE;
  }
  return TRUE;
}

void handleBringToFront(const char *buffer, int len) {
  if (len < 5)
    return;
  int nameLen = readInt(buffer + 1);
  if (nameLen <= 0 || nameLen > len - 5)
    return;
  char *name = (char *)malloc(nameLen + 1);
  if (!name)
    return;
  memcpy(name, buffer + 5, nameLen);
  name[nameLen] = '\0';

  DWORD pid = 0;
  HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (hSnap != INVALID_HANDLE_VALUE) {
    PROCESSENTRY32 pe32;
    pe32.dwSize = sizeof(PROCESSENTRY32);
    if (Process32First(hSnap, &pe32)) {
      do {
        if (_stricmp(pe32.szExeFile, name) == 0) {
          pid = pe32.th32ProcessID;
          break;
        }
      } while (Process32Next(hSnap, &pe32));
    }
    CloseHandle(hSnap);
  }

  if (pid != 0) {
    struct FindWindowData winData = {pid, NULL};
    EnumWindows(EnumWindowsProc, (LPARAM)&winData);
    if (winData.hWnd) {
      if (IsIconic(winData.hWnd))
        ShowWindow(winData.hWnd, SW_RESTORE);
      else
        ShowWindow(winData.hWnd, SW_SHOW);
      SetForegroundWindow(winData.hWnd);
    }
  }

  free(name);
}

void handleInput(const char *buffer, int len) {
  BYTE code = buffer[0];
  if (code == RC_MOUSE_EVENT) {
    if (len < 16)
      return;

    int flags = readInt(buffer + 5);
    short dx = readShort(buffer + 9);
    short dy = readShort(buffer + 11);
    short wheel = readShort(buffer + 13);
    BYTE feedback = (BYTE)buffer[15];

    mouse_event(flags, dx, dy, wheel, 0);
    if (feedback)
      sendCursorFeedback();
  } else if (code == RC_KEYBOARD_EVENT) {
    if (len < 6)
      return;

    BYTE vkey = (BYTE)buffer[1];
    int flags = readInt(buffer + 2);
    keybd_event(vkey, 0, flags, 0);
  }
}

DWORD WINAPI ServerThread(LPVOID lpParam) {
  WSADATA wsaData;
  int error = WSAStartup(MAKEWORD(2, 2), &wsaData);
  if (error != 0) {
    fprintf(stderr, "winhandler: helper startup failed: WSAStartup error %d\n", error);
    running = FALSE;
    SetEvent(serverReadyEvent);
    return 1;
  }

  sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (sock == INVALID_SOCKET) {
    fprintf(stderr, "winhandler: helper startup failed: socket error %d\n", WSAGetLastError());
    WSACleanup();
    running = FALSE;
    SetEvent(serverReadyEvent);
    return 1;
  }

  struct sockaddr_in serverAddr;
  serverAddr.sin_family = AF_INET;
  serverAddr.sin_port = htons(SERVER_PORT);
  serverAddr.sin_addr.s_addr = inet_addr("127.0.0.1");

  if (bind(sock, (struct sockaddr *)&serverAddr, sizeof(serverAddr)) ==
      SOCKET_ERROR) {
    fprintf(stderr, "winhandler: helper startup failed: bind error %d\n", WSAGetLastError());
    closesocket(sock);
    WSACleanup();
    running = FALSE;
    SetEvent(serverReadyEvent);
    return 1;
  }

  clientAddr.sin_family = AF_INET;
  clientAddr.sin_port = htons(CLIENT_PORT);
  clientAddr.sin_addr.s_addr = inet_addr("127.0.0.1");

  char initBuffer[64];
  initBuffer[0] = RC_INIT;
  sendto(sock, initBuffer, 1, 0, (struct sockaddr *)&clientAddr, clientAddrLen);
  SetEvent(serverReadyEvent);

  char buffer[BUFFER_SIZE];
  while (running) {
    struct sockaddr_in sender;
    int senderLen = sizeof(sender);
    int len = recvfrom(sock, buffer, BUFFER_SIZE, 0, (struct sockaddr *)&sender,
                       &senderLen);

    if (len > 0) {
      BYTE command = buffer[0];

      switch (command) {
      case RC_EXIT:
        running = FALSE;
        ExitProcess(0);
        break;
      case RC_INIT:
        break;
      case RC_EXEC:
        if (len >= 5)
          handleExec(buffer + 5, len - 5);
        break;
      case RC_KILL_PROCESS:
        handleKillProcess(buffer + 1, len - 1);
        break;
      case RC_LIST_PROCESSES:
        handleListProcesses();
        break;
      case RC_SET_PROCESS_AFFINITY:
        handleSetAffinity(buffer, len);
        break;
      case RC_MOUSE_EVENT:
      case RC_KEYBOARD_EVENT:
        handleInput(buffer, len);
        break;
      case RC_BRING_TO_FRONT:
        handleBringToFront(buffer, len);
        break;
      }
    }
  }

  closesocket(sock);
  WSACleanup();
  return 0;
}

void handleChildProcesses(int affinityMask) {
  if (affinityMask <= 0)
    return;

  DWORD myPid = GetCurrentProcessId();

  while (running) {
    HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnap != INVALID_HANDLE_VALUE) {
      PROCESSENTRY32 pe32;
      pe32.dwSize = sizeof(PROCESSENTRY32);
      if (Process32First(hSnap, &pe32)) {
        do {
          if (pe32.th32ProcessID > myPid) {
            if (!isPidSeen(pe32.th32ProcessID)) {
              HANDLE hProcess = OpenProcess(PROCESS_SET_INFORMATION, FALSE,
                                            pe32.th32ProcessID);
              if (hProcess) {
                SetProcessAffinityMask(hProcess, affinityMask);
                CloseHandle(hProcess);
              }
              addSeenPid(pe32.th32ProcessID);
            }
          }
        } while (Process32Next(hSnap, &pe32));
      }
      CloseHandle(hSnap);
    }
    Sleep(1000);
  }
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance,
                   LPSTR lpCmdLine, int nCmdShow) {
  int affinity = 0;
  char *directory = NULL;
  char *executable = "wfm.exe";

  int argc = __argc;
  char **argv = __argv;

  int argIdx = 1;
  while (argIdx < argc) {
    if (strcmp(argv[argIdx], "/affinity") == 0) {
      if (argIdx + 1 < argc) {
        affinity = (int)strtol(argv[argIdx + 1], NULL, 16);
        argIdx += 2;
      } else {
        argIdx++;
      }
    } else if (strcmp(argv[argIdx], "/dir") == 0) {
      if (argIdx + 1 < argc) {
        directory = argv[argIdx + 1];
        argIdx += 2;
      } else {
        argIdx++;
      }
    } else {
      executable = argv[argIdx];
      argIdx++;
      break;
    }
  }

  char *execArgs = quoteArguments(argc, argv, argIdx);
  if (!execArgs) {
    fprintf(stderr, "winhandler: helper startup failed: cannot allocate game arguments\n");
    return 1;
  }

  // The input server must be available while ShellExecute starts the game;
  // shell extensions and startup dialogs can block that call for some time.
  serverReadyEvent = CreateEventA(NULL, TRUE, FALSE, NULL);
  if (!serverReadyEvent) {
    fprintf(stderr, "winhandler: helper startup failed: CreateEvent error %lu\n",
            (unsigned long)GetLastError());
    free(execArgs);
    return 1;
  }
  HANDLE hThread = CreateThread(NULL, 0, ServerThread, NULL, 0, NULL);
  if (!hThread) {
    fprintf(stderr, "winhandler: helper startup failed: CreateThread error %lu\n",
            (unsigned long)GetLastError());
    CloseHandle(serverReadyEvent);
    free(execArgs);
    return 1;
  }
  DWORD readyStatus = WaitForSingleObject(serverReadyEvent, 5000);
  CloseHandle(serverReadyEvent);
  if (readyStatus != WAIT_OBJECT_0 || !running) {
    if (readyStatus != WAIT_OBJECT_0)
      fprintf(stderr, "winhandler: helper startup failed: input server did not become ready\n");
    CloseHandle(hThread);
    free(execArgs);
    return 1;
  }

  SHELLEXECUTEINFOA sei = {0};
  sei.cbSize = sizeof(SHELLEXECUTEINFOA);
  sei.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_FLAG_NO_UI;
  sei.lpFile = executable;
  sei.lpParameters = execArgs[0] ? execArgs : NULL;
  sei.lpDirectory = directory;
  sei.nShow = SW_SHOW;

  if (!ShellExecuteExA(&sei)) {
    fprintf(stderr, "winhandler: could not start '%s' (Windows error %lu)\n",
            executable, (unsigned long)GetLastError());
    free(execArgs);
    CloseHandle(hThread);
    return 1;
  }
  free(execArgs);
  if (sei.hProcess)
    CloseHandle(sei.hProcess);

  if (affinity > 0) {
    handleChildProcesses(affinity);
  }
  WaitForSingleObject(hThread, INFINITE);

  DWORD serverStatus = 0;
  GetExitCodeThread(hThread, &serverStatus);
  CloseHandle(hThread);

  return (int)serverStatus;
}
