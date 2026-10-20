// Small Windows GUI fixture for the device launch/input/exit checks. Not shipped.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>

#if defined(__aarch64__)
#define ARCH "ARM64"
#elif defined(__x86_64__)
#define ARCH "x64"
#else
#define ARCH "x86"
#endif

static const char *logPath;
static unsigned keys, clicks;

static void record(const char *event, unsigned value) {
  if (!logPath)
    return;
  FILE *file = fopen(logPath, "a");
  if (file) {
    fprintf(file, "%s %s %u\n", ARCH, event, value);
    fclose(file);
  }
}

static LRESULT CALLBACK windowProc(HWND window, UINT message, WPARAM wParam,
                                   LPARAM lParam) {
  switch (message) {
  case WM_KEYDOWN:
    record("key", (unsigned)wParam);
    keys++;
    InvalidateRect(window, NULL, TRUE);
    return 0;
  case WM_LBUTTONDOWN:
    record("click", ++clicks);
    InvalidateRect(window, NULL, TRUE);
    return 0;
  case WM_PAINT: {
    PAINTSTRUCT paint;
    HDC dc = BeginPaint(window, &paint);
    char text[128];
    int length = snprintf(text, sizeof(text), ARCH " native Windows probe: keys=%u clicks=%u", keys, clicks);
    TextOutA(dc, 24, 24, text, length);
    EndPaint(window, &paint);
    return 0;
  }
  case WM_CLOSE:
    record("close", 0);
    DestroyWindow(window);
    return 0;
  case WM_DESTROY:
    record("exit", 0);
    PostQuitMessage(0);
    return 0;
  }
  return DefWindowProcA(window, message, wParam, lParam);
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command, int show) {
  (void)previous;
  (void)command;
  if (__argc > 1)
    logPath = __argv[1];
  if (logPath) {
    FILE *file = fopen(logPath, "a");
    if (file) {
      fprintf(file, ARCH " command %s\n", GetCommandLineA());
      for (int i = 0; i < __argc; i++)
        fprintf(file, ARCH " argv%d %s\n", i, __argv[i]);
      fclose(file);
    }
  }
  record("start", (unsigned)GetCurrentProcessId());
  WNDCLASSA klass = {0};
  klass.lpfnWndProc = windowProc;
  klass.hInstance = instance;
  klass.hCursor = LoadCursor(NULL, IDC_ARROW);
  klass.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
  klass.lpszClassName = "SkyNetWindowsProbe";
  if (!RegisterClassA(&klass))
    return 1;
  HWND window = CreateWindowA(klass.lpszClassName, "SkyNet " ARCH " launch probe",
      WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 680, 240,
      NULL, NULL, instance, NULL);
  if (!window)
    return 2;
  ShowWindow(window, show);
  UpdateWindow(window);
  record("ready", 0);
  MSG message = {0};
  while (GetMessageA(&message, NULL, 0, 0) > 0) {
    TranslateMessage(&message);
    DispatchMessageA(&message);
  }
  return (int)message.wParam;
}
