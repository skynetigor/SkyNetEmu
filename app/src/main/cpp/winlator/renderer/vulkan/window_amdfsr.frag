#version 450

// AMD FidelityFX Super Resolution 1.0 in one pass: EASU (edge-adaptive 12-tap upscale) followed by RCAS
// (robust contrast-adaptive sharpening). RCAS reads the upscaled neighbours of the pixel, so EASU runs for the
// pixel and for its four neighbours (5 x 12 source taps). Everything works on the gamma-encoded colours the
// window holds, as FSR 1 expects.
//
// Based on AMD's FidelityFX FSR 1.0 reference (ffx_fsr1.h: FsrEasuF, FsrRcasF), which is MIT licensed:
// Copyright (c) 2021 Advanced Micro Devices, Inc. The gather4 loads of the reference are texelFetch here.

layout(push_constant) uniform PC {
    float ndcX0;
    float ndcY0;
    float ndcX1;
    float ndcY1;
    int   useTexAlpha;
    float srcW;
    float srcH;
    float outW;
    float outH;
    int   effectId;
    float sharpness;
} pc;

layout(binding = 0) uniform sampler2D texSampler;
layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

// How far RCAS may push a pixel (AMD's FSR_RCAS_LIMIT). The sharpness slider (0..1) maps to stops below: 1 is AMD's maximum
// (0 stops), 0 is one stop (half the strength); the default 0.5 is half a stop.
const float RCAS_LIMIT = 0.25 - (1.0 / 16.0);

vec3 loadSrc(ivec2 p, ivec2 maxP) {
    return texelFetch(texSampler, clamp(p, ivec2(0), maxP), 0).rgb;
}

// Luma times two, as the reference approximates it.
float luma2(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

// Filtering for a given tap: accumulates the colour with its lanczos-like weight (negative lobe included).
void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    // Rotate the offset by the edge direction, then apply the anisotropy.
    vec2 v;
    v.x = (off.x * dir.x) + (off.y * dir.y);
    v.y = (off.x * -dir.y) + (off.y * dir.x);
    v *= len;
    // Distance squared, limited to the window (at a corner two taps can be outside it).
    float d2 = min(v.x * v.x + v.y * v.y, clp);
    // Approximation of lanczos2 without sin(), rcp() or sqrt():
    //   (25/16 * (2/5 * x^2 - 1)^2 - (25/16 - 1)) * (1/4 * x^2 - 1)^2
    float wB = 2.0 / 5.0 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

// Accumulates the edge direction and length from the '+' around one of the four texels, bilinearly weighted.
//    a
//  b c d
//    e
void easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
    float lenX = max(abs(lD - lC), abs(lC - lB));
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) / max(lenX, 1e-9), 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;
    float lenY = max(abs(lE - lC), abs(lC - lA));
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) / max(lenY, 1e-9), 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

// EASU for the output position uv (0..1 across the window).
vec3 easu(vec2 uv) {
    vec2 srcSize = max(vec2(pc.srcW, pc.srcH), vec2(1.0));
    ivec2 maxP = min(ivec2(srcSize) - ivec2(1), textureSize(texSampler, 0) - ivec2(1));

    // Position of 'f' in the source, and the fractional offset of the output pixel inside it.
    vec2 pp = uv * srcSize - vec2(0.5);
    vec2 fp = floor(pp);
    pp -= fp;
    ivec2 f0 = ivec2(fp);

    // 12-tap kernel around f:
    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 b = loadSrc(f0 + ivec2( 0, -1), maxP);
    vec3 c = loadSrc(f0 + ivec2( 1, -1), maxP);
    vec3 e = loadSrc(f0 + ivec2(-1,  0), maxP);
    vec3 f = loadSrc(f0 + ivec2( 0,  0), maxP);
    vec3 g = loadSrc(f0 + ivec2( 1,  0), maxP);
    vec3 h = loadSrc(f0 + ivec2( 2,  0), maxP);
    vec3 i = loadSrc(f0 + ivec2(-1,  1), maxP);
    vec3 j = loadSrc(f0 + ivec2( 0,  1), maxP);
    vec3 k = loadSrc(f0 + ivec2( 1,  1), maxP);
    vec3 l = loadSrc(f0 + ivec2( 2,  1), maxP);
    vec3 n = loadSrc(f0 + ivec2( 0,  2), maxP);
    vec3 o = loadSrc(f0 + ivec2( 1,  2), maxP);

    float bL = luma2(b), cL = luma2(c), eL = luma2(e), fL = luma2(f), gL = luma2(g), hL = luma2(h);
    float iL = luma2(i), jL = luma2(j), kL = luma2(k), lL = luma2(l), nL = luma2(n), oL = luma2(o);

    // Accumulate for bilinear interpolation.
    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    easuSet(dir, len,        pp.x  * (1.0 - pp.y), cL, fL, gL, hL, kL);
    easuSet(dir, len, (1.0 - pp.x) *        pp.y , fL, iL, jL, kL, nL);
    easuSet(dir, len,        pp.x  *        pp.y , gL, jL, kL, lL, oL);

    // Normalize the direction and clean up close to zero.
    float dirR = dir.x * dir.x + dir.y * dir.y;
    bool zro = dirR < (1.0 / 32768.0);
    dirR = zro ? 1.0 : inversesqrt(dirR);
    dir.x = zro ? 1.0 : dir.x;
    dir *= dirR;
    // From {0 to 2} to {0 to 1}, shaped with a square.
    len = len * 0.5;
    len *= len;
    // Stretch the kernel: 1.0 vertical or horizontal, sqrt(2.0) on the diagonal.
    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(max(abs(dir.x), abs(dir.y)), 1e-9);
    // Anisotropic length after the rotation: x from 1.0 towards 'stretch' on edges, y from 1.0 towards 0.5.
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 + -0.5 * len);
    // The window shifts from +/-sqrt(2.0) to slightly beyond 2.0 with the amount of edge.
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    // Distance^2 clipping point: the end of the adjustable window.
    float clp = 1.0 / lob;

    // Min and max of the four nearest texels (f, g, j, k), for the final ringing limit.
    vec3 min4 = min(min(f, g), min(j, k));
    vec3 max4 = max(max(f, g), max(j, k));

    vec3 aC = vec3(0.0);
    float aW = 0.0;
    easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);
    easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);
    easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);
    easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);
    easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);
    easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);
    easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);
    easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);
    easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);
    easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);
    easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, o);
    easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);

    // Normalize and dering.
    return min(max4, max(min4, aC / aW));
}

void main() {
    vec2 outSize = max(vec2(pc.outW, pc.outH), vec2(1.0));
    vec2 step = 1.0 / outSize;

    // RCAS works on a '+' of upscaled pixels:
    //    b
    //  d e f
    //    h
    vec3 e = easu(fragTexCoord);
    vec3 b = easu(fragTexCoord + vec2( 0.0, -step.y));
    vec3 d = easu(fragTexCoord + vec2(-step.x, 0.0));
    vec3 f = easu(fragTexCoord + vec2( step.x, 0.0));
    vec3 h = easu(fragTexCoord + vec2( 0.0,  step.y));

    // Min and max of the ring.
    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));

    // Limiters.
    vec3 hitMin = min(mn4, e) / (4.0 * mx4 + 1e-6);
    vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, -1e-6);
    vec3 lobeRGB = max(-hitMin, hitMax);
    float stops = 1.0 - clamp(pc.sharpness, 0.0, 1.0);
    float lobe = max(-RCAS_LIMIT, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * exp2(-stops);

    // Resolve.
    vec3 rgb = (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
    float alpha = (pc.useTexAlpha == 0) ? 1.0 : texture(texSampler, fragTexCoord).a;
    outColor = vec4(clamp(rgb, 0.0, 1.0), alpha);
}
