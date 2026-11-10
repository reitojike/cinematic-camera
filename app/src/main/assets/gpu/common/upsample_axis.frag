#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

#include "common/resample.glsl"

uniform sampler2D s_input;
uniform sampler2D s_weights;
uniform isampler2D s_first;
uniform bool u_horizontal;
layout(location = 0) out vec4 o_color;

void main() {
    // (x + 0.5, y + 0.5) を整数の画素アドレスへ変換する。
    o_color = cs_resampleAxis(s_input, s_weights, s_first, ivec2(gl_FragCoord.xy), u_horizontal);
}
