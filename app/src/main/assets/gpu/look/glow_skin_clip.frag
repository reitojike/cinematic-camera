#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

uniform sampler2D s_blurredSkin;
layout(location = 0) out vec4 o_color;

// StageClearGlow.smoothSkin: clip after, not before, the Pillow-compatible blur.
void main() {
    float weight = texelFetch(s_blurredSkin, ivec2(gl_FragCoord.xy), 0).r;
    o_color = vec4(clamp(weight, 0.0, 1.0), 0.0, 0.0, 1.0);
}
