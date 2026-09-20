#ifndef R2_MINIMAL_COMMON
#define R2_MINIMAL_COMMON

float r2Luminance(float3 value) { return dot(value, float3(0.2126, 0.7152, 0.0722)); }

float3 r2SrgbToLinear(float3 value) {
    return select(value <= 0.04045, value / 12.92, pow(max(value + 0.055, 0.0) / 1.055, 2.4));
}

float3 r2LinearToSrgb(float3 value) {
    value = max(value, 0.0);
    return select(value <= 0.0031308, value * 12.92, 1.055 * pow(value, 1.0 / 2.4) - 0.055);
}

#endif
