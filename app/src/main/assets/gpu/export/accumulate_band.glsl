// One invocation owns one reduced-field pixel. Bands arrive in increasing source-y
// order. Load the previous Float32 accumulator, then continue the SAME sequence
// of adds; do not first sum a band, which changes rounding at every seam.
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
uniform sampler2D s_band;
uniform sampler2D s_previous;
uniform sampler2D s_weights;
uniform highp isampler2D s_first;
uniform int u_originY;
uniform int u_bandHeight;
uniform ivec2 u_fieldSize;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_fieldSize))) return;
    int first = texelFetch(s_first, ivec2(0, p.y), 0).r;
    int taps = textureSize(s_weights, 0).x;
    int begin = max(first, u_originY);
    int end = min(first + taps, u_originY + u_bandHeight);
    precise vec4 sum = texelFetch(s_previous, p, 0);
    for (int y = begin; y < end; ++y) {
        float weight = texelFetch(s_weights, ivec2(y - first, p.y), 0).r;
        if (weight != 0.0) sum += texelFetch(s_band, ivec2(p.x, y - u_originY), 0) * weight;
    }
    imageStore(u_field, p, sum);
}
