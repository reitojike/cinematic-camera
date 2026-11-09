#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"

uniform sampler2D s_mist;
uniform sampler2D s_encoded;
uniform sampler2D s_bright;
// blurRgb(bright, StageClearOrtonV1.RADIUS), not a blur of encoded.
uniform sampler2D s_soft;
layout(location = 0) out vec4 o_color;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 rgb = texelFetch(s_mist, pixel, 0).rgb;
    vec3 encoded = texelFetch(s_encoded, pixel, 0).rgb;
    vec3 bright = texelFetch(s_bright, pixel, 0).rgb;
    vec3 soft = texelFetch(s_soft, pixel, 0).rgb;
    precise float y = encoded.r * SC_STAGECLEAR_LUMA_0 + encoded.g * SC_STAGECLEAR_LUMA_1 +
        encoded.b * SC_STAGECLEAR_LUMA_2;
    precise float fade = 1.0 - clamp((y - SC_ORTON_HIGHLIGHT_START) / SC_ORTON_HIGHLIGHT_SPAN, 0.0, 1.0);
    precise vec3 display = encoded + SC_ORTON_AMOUNT * fade *
        (bright * clamp(soft, 0.0, 1.0) - encoded);
    vec3 value = vec3(cs_unitToLinear(display.r), cs_unitToLinear(display.g), cs_unitToLinear(display.b));
    // Restore over-range channels BEFORE the Lab skin-protection pass.
    vec3 mixed = vec3(
        rgb.r > 1.0 ? max(rgb.r, value.r) : value.r,
        rgb.g > 1.0 ? max(rgb.g, value.g) : value.g,
        rgb.b > 1.0 ? max(rgb.b, value.b) : value.b);
    o_color = vec4(mixed, 1.0);
}
