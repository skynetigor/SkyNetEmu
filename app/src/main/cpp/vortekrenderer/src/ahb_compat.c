#include <android/hardware_buffer.h>

#include "native_handle.h"

/* The hardware buffer's dma-buf descriptor, as the winlator library of Winlator provides it (not public API). */
extern const native_handle_t* AHardwareBuffer_getNativeHandle(const AHardwareBuffer* buffer);

int AHardwareBuffer_getFd(AHardwareBuffer* hardwareBuffer) {
    const native_handle_t* nativeHandle = AHardwareBuffer_getNativeHandle(hardwareBuffer);
    return nativeHandle && nativeHandle->numFds > 0 ? nativeHandle->data[0] : -1;
}
