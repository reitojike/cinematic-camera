#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"

uniform sampler2D s_mist;
// Two RGBA32F attachments: encoded at 0, bright at 1.
layout(location = 0) out vec4 o_color;
layout(location = 1) out vec4 o_colorBright;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 rgb = texelFetch(s_mist, pixel, 0).rgb;
    vec3 encoded = vec3(cs_linearToUnit(rgb.r), cs_linearToUnit(rgb.g), cs_linearToUnit(rgb.b));
    precise vec3 inverse = vec3(1.0) - encoded;
    precise vec3 bright = vec3(1.0) - inverse * inverse;
    o_color = vec4(encoded, 1.0);
    o_colorBright = vec4(bright, 1.0);
}
