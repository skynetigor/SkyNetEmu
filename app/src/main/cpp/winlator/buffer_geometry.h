#pragma once
#include <stdint.h>
#include <limits.h>

static inline int valid_ahb_geometry(uint32_t width, uint32_t height, uint32_t stride,
                                     uint32_t layers, uint32_t format) {
    return width > 0 && height > 0 && width <= INT16_MAX && height <= INT16_MAX
        && stride >= width && stride <= INT16_MAX && layers == 1
        && (format == 1 || format == 2 || format == 5);
}

static inline int valid_dma_geometry(int width, int height, int stride, int offset,
                                     uint32_t limit, uint64_t size) {
    if (width <= 0 || height <= 0 || (uint32_t)width > limit || (uint32_t)height > limit
        || stride <= 0 || offset < 0 || (uint64_t)stride < (uint64_t)width * 4
        || stride % 4 || offset % 4) return 0;
    uint64_t end = (uint64_t)offset + (uint64_t)(height - 1) * stride + (uint64_t)width * 4;
    return size == 0 || end <= size;
}
