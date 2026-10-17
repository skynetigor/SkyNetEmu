/*
 * skynogl: an x86_64 library installed under the names of the OpenGL libraries (libGL.so.1, libGLX.so.0, libEGL.so.1, ...) for a program run
 * by the Android Box64 (see LinuxBionicSession). The x86-64 Mesa of the root filesystem is a software renderer on LLVM that cannot work there, and an
 * embedded Chromium's gtk/epoxy only look for OpenGL: this library says there is none (no GLX extension, no EGL display), which both handle.
 *
 * MIT licence, written for this project.
 */
#include <stddef.h>

#define STUB_NULL(name) void *name() { return NULL; }
#define STUB_ZERO(name) int name() { return 0; }

/* GLX: glXQueryExtension and glXQueryVersion fail, which is the answer "no GLX". */
STUB_ZERO(glXQueryExtension)
STUB_ZERO(glXQueryVersion)
STUB_ZERO(glXIsDirect)
STUB_ZERO(glXMakeCurrent)
STUB_ZERO(glXMakeContextCurrent)
STUB_NULL(glXGetCurrentContext)
STUB_NULL(glXGetCurrentDisplay)
STUB_NULL(glXGetCurrentDrawable)
STUB_NULL(glXChooseFBConfig)
STUB_NULL(glXChooseVisual)
STUB_NULL(glXGetFBConfigs)
STUB_NULL(glXGetVisualFromFBConfig)
STUB_ZERO(glXGetFBConfigAttrib)
STUB_NULL(glXCreateContext)
STUB_NULL(glXCreateNewContext)
STUB_NULL(glXCreateContextAttribsARB)
STUB_NULL(glXCreateWindow)
STUB_NULL(glXCreatePbuffer)
STUB_NULL(glXQueryExtensionsString)
STUB_NULL(glXQueryServerString)
STUB_NULL(glXGetClientString)
STUB_NULL(glXGetProcAddress)
STUB_NULL(glXGetProcAddressARB)
void glXDestroyContext() {}
void glXSwapBuffers() {}
void glXWaitGL() {}
void glXWaitX() {}
void glXDestroyWindow() {}
void glXDestroyPbuffer() {}
STUB_ZERO(glXQueryContext)

/* EGL: no display. */
STUB_NULL(eglGetDisplay)
STUB_NULL(eglGetPlatformDisplay)
STUB_NULL(eglGetPlatformDisplayEXT)
STUB_NULL(eglGetProcAddress)
STUB_NULL(eglQueryString)
STUB_NULL(eglGetCurrentContext)
STUB_NULL(eglGetCurrentDisplay)
STUB_ZERO(eglInitialize)
STUB_ZERO(eglBindAPI)
STUB_ZERO(eglGetError)
STUB_ZERO(eglMakeCurrent)
STUB_ZERO(eglTerminate)
