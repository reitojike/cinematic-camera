#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

uniform sampler2D s_glowed;
layout(location = 0) out vec4 o_color;

void main() {
    vec3 rgb = texelFetch(s_glowed, ivec2(gl_FragCoord.xy), 0).rgb;
    precise float luminance = rgb.r * SC_STAGECLEAR_LUMA_0 +
        rgb.g * SC_STAGECLEAR_LUMA_1 + rgb.b * SC_STAGECLEAR_LUMA_2;
    float y = max(luminance, 0.0);
    float weight = pow(clamp((y - SC_MIST_SOURCE_START) / SC_MIST_SOURCE_SPAN, 0.0, 1.0),
        SC_MIST_SOURCE_POWER);
    o_color = vec4(rgb * weight, 1.0);
}
