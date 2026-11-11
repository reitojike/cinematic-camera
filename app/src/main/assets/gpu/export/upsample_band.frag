#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;
#include "common/resample.glsl"
uniform sampler2D s_field;
uniform sampler2D s_xWeights;
uniform isampler2D s_xFirst;
uniform sampler2D s_yWeights;
uniform isampler2D s_yFirst;
uniform int u_originY;
layout(location = 0) out vec4 o_color;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int y = p.y + u_originY;
    int first = texelFetch(s_yFirst, ivec2(0, y), 0).r;
    precise vec4 sum = vec4(0.0);
    for (int i = 0; i < textureSize(s_yWeights, 0).x; ++i) {
        float weight = texelFetch(s_yWeights, ivec2(i, y), 0).r;
        if (weight == 0.0) continue;
        // Each horizontal result is Float32 before the vertical multiplication,
        // just as in common/upsample_axis.frag's two separate render targets.
        precise vec4 row = cs_resampleAxis(s_field, s_xWeights, s_xFirst, ivec2(p.x, first + i), true);
        sum += row * weight;
    }
    o_color = sum;
}