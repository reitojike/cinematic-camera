#include "common/resample.glsl"

// 横パスは行数、縦パスは列数のワークグループを dispatch する。
// 各 invocation が出力画素を担当し、タップは元の順に単精度で累積する。
layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
uniform sampler2D s_input;
uniform sampler2D s_weights;
uniform isampler2D s_first;
uniform bool u_horizontal;
uniform ivec2 u_outputSize;

void main() {
    int line = int(gl_WorkGroupID.x);
    int lineCount = u_horizontal ? u_outputSize.y : u_outputSize.x;
    int extent = u_horizontal ? u_outputSize.x : u_outputSize.y;
    if (line >= lineCount) return;
    for (int pixel = int(gl_LocalInvocationID.x); pixel < extent; pixel += 64) {
        ivec2 outputPixel = u_horizontal ? ivec2(pixel, line) : ivec2(line, pixel);
        imageStore(u_destination, outputPixel,
            cs_resampleAxis(s_input, s_weights, s_first, outputPixel, u_horizontal));
    }
}
