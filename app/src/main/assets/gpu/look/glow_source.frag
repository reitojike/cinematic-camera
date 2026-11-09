#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

uniform sampler2D s_base;
layout(location = 0) out vec4 o_color;

// StageClearGlow.addLight. RGB is the bloom source; A is the halation excess.
// Neither the scene-linear input nor the excess is clipped to an upper bound.
void main() {
    vec3 rgb = texelFetch(s_base, ivec2(gl_FragCoord.xy), 0).rgb;
    precise float luminance = rgb.r * SC_STAGECLEAR_LUMA_0 +
        rgb.g * SC_STAGECLEAR_LUMA_1 + rgb.b * SC_STAGECLEAR_LUMA_2;
    float y = max(0.0, luminance);
    float excess = pow(max(0.0, (y - SC_GLOW_THRESHOLD) / (1.0 - SC_GLOW_THRESHOLD)), 1.5);
    float scale = excess / max(y, 1e-6);
    o_color = vec4(rgb * scale, excess);
}
