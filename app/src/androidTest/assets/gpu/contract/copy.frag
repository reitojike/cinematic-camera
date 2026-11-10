#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
#include "contract/identity.glsl"
uniform sampler2D s_input;
layout(location = 0) out vec4 o_color;
void main() { o_color = cs_identity(texelFetch(s_input, ivec2(gl_FragCoord.xy), 0)); }
