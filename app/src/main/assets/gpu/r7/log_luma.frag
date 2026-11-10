#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;

uniform sampler2D s_input;
layout(location = 0) out vec4 o_color;

void main() {
    vec3 rgb = texelFetch(s_input, ivec2(gl_FragCoord.xy), 0).rgb;
    // R7Base.luma の Float 乗加算順序。出力先は R32F。
    precise float y = rgb.r * 0.2126 + rgb.g * 0.7152 + rgb.b * 0.0722;
    float logLuma = log2(max(y, 1e-5));
    o_color = vec4(logLuma, 0.0, 0.0, 1.0);
}
