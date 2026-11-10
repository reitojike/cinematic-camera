#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

uniform sampler2D s_previous;
uniform sampler2D s_blurred;
// StageClearGlowV1.BLOOM_KERNELS[kernel].second, already a Kotlin Float.
uniform float u_kernelWeight;
layout(location = 0) out vec4 o_color;

// StageClearGlow.addLight: start with a zero-cleared RGBA32F accumulator, then
// run exactly once per kernel in declaration order (0, 1, 2). Read the previous
// sum and write the other ping-pong texture: never sample the active framebuffer.
// Release each s_blurred after its addition. Only the final sum is retained for
// strength changes; skin/halation fields need not be live during accumulation.
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    precise vec3 bloom = texelFetch(s_previous, pixel, 0).rgb;
    bloom += u_kernelWeight * texelFetch(s_blurred, pixel, 0).rgb;
    // RGBA32F stores a Float result after every addition, just like bloom[i] +=.
    o_color = vec4(bloom, 1.0);
}
