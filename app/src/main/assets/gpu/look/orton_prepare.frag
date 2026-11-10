#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"

uniform sampler2D s_mist;
// One RGBA32F bright field. orton_mix recomputes encoded from s_mist.
layout(location = 0) out vec4 o_color;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 rgb = texelFetch(s_mist, pixel, 0).rgb;
    vec3 encoded = vec3(cs_linearToUnit(rgb.r), cs_linearToUnit(rgb.g), cs_linearToUnit(rgb.b));
    precise vec3 inverseEncoded = vec3(1.0) - encoded;
    precise vec3 bright = vec3(1.0) - inverseEncoded * inverseEncoded;
    o_color = vec4(bright, 1.0);
}
