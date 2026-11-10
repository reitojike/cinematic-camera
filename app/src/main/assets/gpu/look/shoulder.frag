#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"

uniform sampler2D s_skinCap;
layout(location = 0) out vec4 o_color;

void main() {
    vec3 rgb = texelFetch(s_skinCap, ivec2(gl_FragCoord.xy), 0).rgb;
    precise float luma = rgb.r * SC_STAGECLEAR_LUMA_0 + rgb.g * SC_STAGECLEAR_LUMA_1 +
        rgb.b * SC_STAGECLEAR_LUMA_2;
    float y = max(luma, 1e-6);
    float u = cs_encode(y);
    float start = SC_STAGECLEAR_SHOULDER;
    float span = 1.0 - start;
    precise float rolled = u > start ? start + span * tanh((u - start) / span) : u;
    float factor = cs_decode(rolled) / y;
    // Preserve negative/over-range channels; only the display pass clips.
    o_color = vec4(rgb * factor, 1.0);
}
