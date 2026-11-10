#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/color.glsl"
#include "common/display.glsl"

uniform sampler2D s_linear;
// Render to RGBA8 with GL_DITHER disabled. No sRGB attachment conversion.
layout(location = 0) out vec4 o_color;

void main() {
    o_color = vec4(cs_display8(texelFetch(s_linear, ivec2(gl_FragCoord.xy), 0).rgb), 1.0);
}
