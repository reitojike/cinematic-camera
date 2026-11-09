#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"

uniform sampler2D s_orton;
// Full-image dimensions and top-left source offset; preview uses origin (0, 0).
uniform ivec2 u_imageSize;
uniform ivec2 u_origin;
// min(width.toDouble() / height, 1.0).toFloat(), max(..., 1.0).toFloat().
uniform vec2 u_aspect;
// (StageClearCharacterV1.VIGNETTE/VIBRANCE * strength).toFloat().
uniform float u_vignetteAmount;
uniform float u_vibranceAmount;
layout(location = 0) out vec4 o_color;

// Kotlin % truncates toward zero; GLSL mod floors. Preserve the two wraps.
float cs_characterRemainder(float value, float divisor) {
    return value - trunc(value / divisor) * divisor;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec2 centre = vec2(pixel + u_origin) + vec2(0.5);
    precise float x = (centre.x / float(u_imageSize.x) * 2.0 - 1.0) * u_aspect.x;
    precise float y = (centre.y / float(u_imageSize.y) * 2.0 - 1.0) / u_aspect.y;
    precise float radius = sqrt(x * x + y * y);
    float t = clamp((radius - 0.45) / 0.9, 0.0, 1.0);
    precise float weight = 1.0 - u_vignetteAmount * t * t * (3.0 - 2.0 * t);
    precise vec3 vignette = texelFetch(s_orton, pixel, 0).rgb * weight;

    // Preserve vignette -> Float RGB -> vibrance, including strength == 0.
    vec3 lab = cs_linearToLab(vignette);
    float lightness = lab.x;
    float chroma = length(lab.yz);
    // atan(0, 0) is undefined in GLSL. Kotlin atan2 is finite, even for black.
    float angle = lab.y == 0.0 && lab.z == 0.0 ? 0.0 : degrees(atan(lab.z, lab.y));
    float hue = cs_characterRemainder(cs_characterRemainder(angle, 360.0) + 360.0, 360.0);
    float distance = abs(cs_characterRemainder(hue - 50.0 + 180.0, 360.0) - 180.0);
    t = clamp(1.0 - distance / 40.0, 0.0, 1.0);
    precise float band = t * t * (3.0 - 2.0 * t);
    precise float skin = band * clamp((chroma - 0.015) / 0.03, 0.0, 1.0) *
        clamp(1.0 - (chroma - 0.13) / 0.05, 0.0, 1.0);
    precise float neutralGuard = clamp((chroma - 0.012) / 0.03, 0.0, 1.0) *
        clamp(1.0 - (lightness - 0.72) / 0.2, 0.0, 1.0);
    precise float gain = 1.0 + u_vibranceAmount * neutralGuard * clamp(1.0 - chroma / 0.22, 0.0, 1.0) *
        (1.0 + 0.6 * clamp(1.0 - lightness / 0.6, 0.0, 1.0)) * (1.0 - 0.85 * skin);
    lab.y *= gain;
    lab.z *= gain;
    o_color = vec4(cs_labToLinear(lab), 1.0);
}
