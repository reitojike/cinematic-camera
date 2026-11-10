#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

uniform sampler2D s_input;
uniform sampler2D s_smoothLogLuma;
// 原寸の平滑 log 輝度から厳密に選び、Double で補間した p50, p92。
uniform vec2 u_quantiles;
layout(location = 0) out vec4 o_color;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float value = texelFetch(s_smoothLogLuma, pixel, 0).r;
    float high = u_quantiles.y;
    float low = u_quantiles.x;
    // R7Base.localTone。p97 の別の補間規則は使わない。
    precise float delta = -SC_R7_HIGHLIGHTS * max(value - high, 0.0) -
        SC_R7_SHADOWS * min(value - low, 0.0);
    float gain = exp2(delta);
    o_color = vec4(texelFetch(s_input, pixel, 0).rgb * gain, 1.0);
}
