#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

uniform sampler2D s_source;
layout(location = 0) out vec4 o_color;

// Extract the unblurred source alpha to R32F before the HALATION_RADIUS blur.
void main() {
    o_color = vec4(texelFetch(s_source, ivec2(gl_FragCoord.xy), 0).a, 0.0, 0.0, 1.0);
}
