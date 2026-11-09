#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"

uniform sampler2D s_linear;
// Render to RGBA8 with GL_DITHER disabled. No sRGB attachment conversion.
layout(location = 0) out vec4 o_color;

void main() {
    vec3 value = clamp(texelFetch(s_linear, ivec2(gl_FragCoord.xy), 0).rgb, 0.0, 1.0);
    vec3 encoded = vec3(cs_encode(value.r), cs_encode(value.g), cs_encode(value.b));
    // Non-negative Kotlin roundToInt: ties go up. GLSL round leaves ties
    // implementation-defined, so spell out the same floor(x + 0.5) rule.
    vec3 bytes = clamp(floor(encoded * 255.0 + 0.5), 0.0, 255.0);
    o_color = vec4(bytes / 255.0, 1.0);
}
